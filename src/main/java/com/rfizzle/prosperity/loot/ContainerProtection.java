package com.rfizzle.prosperity.loot;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.attachment.InstancedLootData;
import com.rfizzle.prosperity.attachment.ProsperityAttachments;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Optional anti-grief break protection for instanced loot containers (S-017, SPEC §12). When
 * {@code enableContainerProtection} is on, a Prosperity-managed loot container that still has
 * unclaimed loot &mdash; a never-opened container, or a generated one some online player has not yet
 * opened &mdash; breaks {@code protectionBreakMultiplier}x slower, so it cannot be quickly erased
 * before its loot is collected. Protection is a speed bump, not a wall: creative players bypass it,
 * and it lifts once every online player has opened the container (so an emptied container breaks at
 * vanilla speed). A blacklisted loot table is never managed, so it is never protected.
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

    /** Minimum ticks between protection cues for one player, so mashing attack does not spam it. */
    private static final long CUE_COOLDOWN_TICKS = 10L;

    /** Per-player tick of the last protection cue (server-thread confined; cleared on disconnect). */
    private static final Map<UUID, Long> lastCueTick = new HashMap<>();

    /** Wire the client's queried-multiplier lookup so the common mixin can slow the animation. */
    public static void setClientView(@Nullable ClientProtectionView view) {
        clientView = view;
    }

    /** Register the server-side break-start cue (an action-bar warning, quiet anvil-land, particles). */
    public static void register() {
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            if (!level.isClientSide && level instanceof ServerLevel serverLevel
                    && player instanceof ServerPlayer serverPlayer
                    && isProtectedServer(serverLevel, pos, player)) {
                playProtectionCue(serverLevel, pos, serverPlayer);
            }
            return InteractionResult.PASS;
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                lastCueTick.remove(handler.getPlayer().getUUID()));
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

    /**
     * The server's break multiplier for {@code pos}: {@code 1.0} when unprotected, the configured
     * {@code protectionBreakMultiplier} when protected, or {@link Float#POSITIVE_INFINITY} when
     * {@code protectionUnbreakable} is on — the mixin turns an infinite multiplier into a
     * {@code getDestroyProgress} of {@code 0}, making the container unbreakable like bedrock.
     */
    public static float protectionMultiplierFor(ServerLevel level, BlockPos pos, @Nullable Player player) {
        return protectionMultiplierFor(level, pos, player, onlineUuids(level));
    }

    /** Testable seam for {@link #protectionMultiplierFor(ServerLevel, BlockPos, Player)} with an explicit online set. */
    public static float protectionMultiplierFor(ServerLevel level, BlockPos pos, @Nullable Player player,
            Collection<UUID> onlinePlayers) {
        if (!isProtectedServer(level, pos, player, onlinePlayers)) {
            return 1.0f;
        }
        return Prosperity.getConfig().protectionUnbreakable
                ? Float.POSITIVE_INFINITY
                : Prosperity.getConfig().protectionBreakMultiplier;
    }

    /**
     * Whether the container at {@code pos} is currently break-protected, derived from the live online
     * player list. Protected iff: the feature is enabled, the breaker is not creative, the block is a
     * Prosperity-managed (non-blacklisted) loot container, and it still has unclaimed loot &mdash; a
     * never-opened container (no instance generated yet) or a generated one some online player has not
     * yet opened. An emptied container (every online player has opened it) is not protected.
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
        if (!isManagedLootContainer(container, data)) {
            return false;
        }
        return anyLootPending(data, onlinePlayers);
    }

    /**
     * Whether this container is a loot source Prosperity manages: it is (or once was) a loot-table
     * container and its effective loot table is not blacklisted. The live loot-table field is nulled
     * after generation (S-006), so the preserved key on the attachment stands in for the blacklist
     * check on return visits.
     */
    private static boolean isManagedLootContainer(RandomizableContainerBlockEntity container,
            @Nullable InstancedLootData data) {
        if (!InstancedLootInteraction.isLootContainer(container, data)) {
            return false;
        }
        ResourceKey<LootTable> table = container.getLootTable();
        if (table == null && data != null) {
            table = data.getOriginalLootTable();
        }
        return !InstancedLootInteraction.isBlacklisted(table);
    }

    /**
     * Whether the container still has loot no one has claimed yet. A container that has never generated
     * an instance is pending for everyone; once generated, it is pending while any online player has
     * not opened it.
     */
    static boolean anyLootPending(@Nullable InstancedLootData data, Collection<UUID> onlinePlayers) {
        if (data == null || !data.isGenerated()) {
            return true;
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

    /**
     * Play the "this is protected" cue for the breaker: an action-bar warning plus a quiet anvil-land
     * sound and a small particle puff. Per-player throttled by {@link #CUE_COOLDOWN_TICKS} so mashing
     * attack does not spam the message or sound.
     */
    private static void playProtectionCue(ServerLevel level, BlockPos pos, ServerPlayer player) {
        long now = level.getGameTime();
        Long last = lastCueTick.get(player.getUUID());
        if (last != null && now - last < CUE_COOLDOWN_TICKS) {
            return;
        }
        lastCueTick.put(player.getUUID(), now);
        player.displayClientMessage(protectionMessage(Prosperity.getConfig().protectionUnbreakable), true);
        Vec3 center = Vec3.atCenterOf(pos);
        level.playSound(null, pos, SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 0.3f, 1.5f);
        level.sendParticles(ParticleTypes.CRIT, center.x, center.y + 0.5, center.z, 8, 0.25, 0.25, 0.25, 0.0);
    }

    /**
     * The action-bar warning shown when a player starts breaking a protected loot container: a
     * "can't be broken" form when {@code unbreakable}, otherwise the "breaks slower" form.
     */
    public static Component protectionMessage(boolean unbreakable) {
        return unbreakable
                ? Component.translatableWithFallback("prosperity.notification.protected_unbreakable",
                        "⚠ This loot container can't be broken — open it to claim its loot")
                : Component.translatableWithFallback("prosperity.notification.protected",
                        "⚠ Protected loot container — open it instead of breaking it");
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
