package com.rfizzle.prosperity.loot;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.attachment.InstancedLootData;
import com.rfizzle.prosperity.attachment.ProsperityAttachments;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Optional anti-grief break protection for instanced loot containers (S-017, SPEC §12). When
 * {@code enableContainerProtection} is on, a world-gen loot container that some online player still
 * has pending (has not generated their instance) breaks {@code protectionBreakMultiplier}x slower, so
 * one player cannot quickly erase everyone else's loot. Protection is a speed bump, not a wall:
 * creative players bypass it, and it lifts once every online player has opened the container.
 *
 * <p><b>Server-authoritative, client-synced visual.</b> The {@link InstancedLootData} attachment is
 * server-only, so the common {@code BlockBehaviour#getDestroyProgress} mixin can only evaluate
 * protection where {@code level} is a {@link ServerLevel}. The server independently gates the actual
 * block removal (it requires {@code getDestroyProgress x (ticks+1) >= 0.7} before accepting the
 * break), so the slowdown is genuinely enforced even against an unmodified client. To slow the
 * client's <em>cracking animation</em> to match, the client queries the server for a target's
 * multiplier at break-start ({@code QueryProtectionC2S} -&gt; {@code ProtectionResultS2C}) and the
 * mixin's client branch divides by the {@link ClientProtectionView} answer.
 *
 * <p><b>Minecarts are out of scope:</b> chest/hopper minecarts (S-035) are entities with no
 * {@code getDestroyProgress}, so this block-only protection does not cover them.
 */
public final class ContainerProtection {

    private ContainerProtection() {
    }

    /** Supplies the client-side break multiplier for a position; set by the client entrypoint. */
    public interface ClientProtectionView {
        /** The break-speed multiplier to apply at {@code pos} ({@code 1.0} = unprotected). */
        float multiplierFor(BlockPos pos);
    }

    @Nullable
    private static volatile ClientProtectionView clientView;

    /** Wire the client's queried-multiplier lookup so the common mixin can slow the animation. */
    public static void setClientView(@Nullable ClientProtectionView view) {
        clientView = view;
    }

    /** Register the server-side break-start cue (a single quiet anvil-land + particle burst). */
    public static void register() {
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            if (!level.isClientSide && level instanceof ServerLevel serverLevel
                    && isProtectedServer(serverLevel, pos, player)) {
                playProtectionCue(serverLevel, pos);
            }
            return InteractionResult.PASS;
        });
    }

    /**
     * The break-speed multiplier the {@code getDestroyProgress} mixin should divide by ({@code 1.0}
     * leaves vanilla speed). Server-side it is evaluated authoritatively from the attachment and the
     * online-player list; client-side it defers to the queried {@link ClientProtectionView}. Creative
     * players and a missing client view yield {@code 1.0}.
     */
    public static float breakMultiplier(BlockGetter level, BlockPos pos, @Nullable Player player) {
        if (player != null && player.isCreative()) {
            return 1.0f;
        }
        if (level instanceof ServerLevel serverLevel) {
            // getDestroyProgress is hot while mining; skip the online-player scan when the feature is off.
            if (!Prosperity.getConfig().enableContainerProtection) {
                return 1.0f;
            }
            return protectionMultiplierFor(serverLevel, pos, player);
        }
        // Client branch: trust the server's queried answer rather than the client's local config — the
        // server replies 1.0 whenever it is not protecting, so no local-config gate is needed here.
        ClientProtectionView view = clientView;
        return view != null ? view.multiplierFor(pos) : 1.0f;
    }

    /** The server's break multiplier for {@code pos}: the configured value when protected, else {@code 1.0}. */
    public static float protectionMultiplierFor(ServerLevel level, BlockPos pos, @Nullable Player player) {
        return protectionMultiplierFor(level, pos, player, onlineUuids(level));
    }

    /** Testable seam for {@link #protectionMultiplierFor(ServerLevel, BlockPos, Player)} with an explicit online set. */
    public static float protectionMultiplierFor(ServerLevel level, BlockPos pos, @Nullable Player player,
            Collection<UUID> onlinePlayers) {
        return isProtectedServer(level, pos, player, onlinePlayers)
                ? Prosperity.getConfig().protectionBreakMultiplier
                : 1.0f;
    }

    /**
     * Whether the container at {@code pos} is currently break-protected, derived from the live online
     * player list. Protected iff: the feature is enabled, the breaker is not creative, the block is a
     * generated instanced container (a blacklisted container is never instanced, so it is never
     * {@code generated}), and at least one online player has not yet generated their instance.
     */
    public static boolean isProtectedServer(ServerLevel level, BlockPos pos, @Nullable Player player) {
        return isProtectedServer(level, pos, player, onlineUuids(level));
    }

    /** Testable seam for {@link #isProtectedServer(ServerLevel, BlockPos, Player)} with an explicit online set. */
    public static boolean isProtectedServer(ServerLevel level, BlockPos pos, @Nullable Player player,
            Collection<UUID> onlinePlayers) {
        if (!Prosperity.getConfig().enableContainerProtection) {
            return false;
        }
        if (player != null && player.isCreative()) {
            return false;
        }
        if (!(level.getBlockEntity(pos) instanceof RandomizableContainerBlockEntity container)) {
            return false;
        }
        InstancedLootData data = ProsperityAttachments.get(container);
        if (data == null || !data.isGenerated()) {
            return false;
        }
        return anyOnlinePlayerPending(data, onlinePlayers);
    }

    /** Whether any online player has not generated their instance here (so protection still holds). */
    static boolean anyOnlinePlayerPending(InstancedLootData data, Collection<UUID> onlinePlayers) {
        for (UUID id : onlinePlayers) {
            if (!data.hasInventory(id)) {
                return true;
            }
        }
        return false;
    }

    /** Play the one-shot "this is protected" cue: a quiet anvil-land sound and a small particle puff. */
    private static void playProtectionCue(ServerLevel level, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        level.playSound(null, pos, SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 0.3f, 1.5f);
        level.sendParticles(ParticleTypes.CRIT, center.x, center.y + 0.5, center.z, 8, 0.25, 0.25, 0.25, 0.0);
    }

    private static Collection<UUID> onlineUuids(ServerLevel level) {
        List<ServerPlayer> players = level.getServer().getPlayerList().getPlayers();
        List<UUID> ids = new ArrayList<>(players.size());
        for (ServerPlayer player : players) {
            ids.add(player.getUUID());
        }
        return ids;
    }
}
