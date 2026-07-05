package com.rfizzle.prosperity.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.attachment.InstancedLootData;
import com.rfizzle.prosperity.attachment.LootStatsData;
import com.rfizzle.prosperity.attachment.ProsperityAttachments;
import com.rfizzle.prosperity.config.DistanceTier;
import com.rfizzle.prosperity.loot.LootScaling;
import com.rfizzle.prosperity.network.ProsperityNetworking;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

/**
 * The {@code /prosperity} command tree (SPEC §15). {@code info} and {@code stats} are open to any
 * player for themselves; querying another player and every mutating verb require operator
 * level 2. All feedback resolves through {@code command.prosperity.*} translation keys.
 *
 * <p>The core operations — {@link #resolveTier} and {@link #clearContainer} — are exposed
 * (package-private) and free of command plumbing so gametests can assert them directly.
 */
public final class ProsperityCommand {

    public static final String ROOT = "prosperity";

    /** Radius (blocks) used by {@code reset/refresh around} when none is given. */
    static final int DEFAULT_RADIUS = 128;

    /** Hard cap on the {@code around} radius — bounds the chunk scan an operator can trigger. */
    static final int MAX_RADIUS = 256;

    private ProsperityCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(ROOT)
                .then(Commands.literal("info")
                        .executes(ProsperityCommand::runInfoSelf)
                        .then(Commands.argument("player", EntityArgument.player())
                                .requires(src -> src.hasPermission(2))
                                .executes(ProsperityCommand::runInfoOther)))
                .then(Commands.literal("stats")
                        .executes(ProsperityCommand::runStatsSelf)
                        .then(Commands.argument("player", EntityArgument.player())
                                .requires(src -> src.hasPermission(2))
                                .executes(ProsperityCommand::runStatsOther)))
                .then(Commands.literal("reset")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> clearCommand(ctx, false, false))
                                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                        .executes(ctx -> clearCommand(ctx, false, true))))
                        .then(radiusBranch(false)))
                .then(Commands.literal("refresh")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> clearCommand(ctx, true, false))
                                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                        .executes(ctx -> clearCommand(ctx, true, true))))
                        .then(radiusBranch(true)))
                .then(Commands.literal("reload")
                        .requires(src -> src.hasPermission(2))
                        .executes(ProsperityCommand::runReload)));
    }

    // ---- info ----

    private static int runInfoSelf(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        sendInfo(ctx.getSource(), ctx.getSource().getPlayerOrException());
        return Command.SINGLE_SUCCESS;
    }

    private static int runInfoOther(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        sendInfo(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"));
        return Command.SINGLE_SUCCESS;
    }

    private static void sendInfo(CommandSourceStack src, ServerPlayer who) {
        ServerLevel level = who.serverLevel();
        double x = who.getX();
        double z = who.getZ();
        DistanceTier tier = resolveTier(level, x, z);
        double distance = Math.sqrt(x * x + z * z);
        Component tierName = Component.translatableWithFallback(
                "prosperity.tier." + tier.name(), capitalize(tier.name()));
        Component modifiers = Component.translatable("command.prosperity.info.modifiers",
                TierFormat.multiplier(tier.stackMultiplier()), tier.qualityModifier());
        Component message = Component.translatable("command.prosperity.info",
                TierFormat.distance(distance), tierName, modifiers);
        src.sendSuccess(() -> message, false);
    }

    /**
     * Resolves the distance tier for a position, delegating to the shared generation-side resolver
     * ({@link LootScaling#resolveTier}) so {@code info} and loot generation never drift. Exposed for
     * gametests.
     */
    public static DistanceTier resolveTier(ServerLevel level, double x, double z) {
        return LootScaling.resolveTier(level, x, z);
    }

    // ---- stats ----

    private static int runStatsSelf(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        sendStats(ctx.getSource(), ctx.getSource().getPlayerOrException());
        return Command.SINGLE_SUCCESS;
    }

    private static int runStatsOther(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        sendStats(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Send {@code who}'s loot statistics (issue #52) to the command source: total containers, one
     * row per tier with a recorded count, distinct structure types, and injected rewards received.
     * A player with no recorded stats (no attachment) reads as all zeros — counting starts when the
     * feature ships, with no backfill from existing container attachments.
     */
    private static void sendStats(CommandSourceStack src, ServerPlayer who) {
        LootStatsData stats = ProsperityAttachments.stats(who);
        long total = stats == null ? 0L : stats.containersLooted();
        Map<String, Long> tiers = stats == null ? Map.of() : stats.tierCounts();
        int structures = stats == null ? 0 : stats.distinctStructures();
        long injected = stats == null ? 0L : stats.injectedRewards();
        final String name = who.getName().getString();

        src.sendSuccess(() -> Component.translatable("command.prosperity.stats.header", name), false);
        src.sendSuccess(() -> Component.translatable("command.prosperity.stats.total", total), false);
        for (String tierName : statsTierOrder(Prosperity.getConfig().distanceTiers, tiers.keySet())) {
            final long count = tiers.getOrDefault(tierName, 0L);
            final Component display = Component.translatableWithFallback(
                    "prosperity.tier." + tierName, capitalize(tierName));
            src.sendSuccess(() -> Component.translatable("command.prosperity.stats.tier", display, count),
                    false);
        }
        src.sendSuccess(() -> Component.translatable("command.prosperity.stats.structures", structures), false);
        src.sendSuccess(() -> Component.translatable("command.prosperity.stats.injected", injected), false);
    }

    /**
     * The tier names to display in a stats readout: every recorded name, configured tiers first in
     * their configured (distance-ladder) order, then any remaining recorded names alphabetically.
     * The leftovers keep a readout complete when a recorded tier has since been renamed out of the
     * config, or when the {@code local} sentinel was recorded under an empty tier list — the buckets
     * always sum to the container total. Pure and exposed for unit tests.
     */
    static List<String> statsTierOrder(@Nullable List<DistanceTier> configTiers, Set<String> recorded) {
        List<String> out = new ArrayList<>();
        Set<String> remaining = new TreeSet<>(recorded);
        if (configTiers != null) {
            for (DistanceTier tier : configTiers) {
                if (tier != null && remaining.remove(tier.name())) {
                    out.add(tier.name());
                }
            }
        }
        out.addAll(remaining);
        return out;
    }

    // ---- reset / refresh ----

    /**
     * The {@code around [radius] [player]} branch shared by {@code reset} and {@code refresh}: clears
     * instanced loot for every container within the radius of the command source's position, defaulting
     * to {@link #DEFAULT_RADIUS} when no radius is given and capped at {@link #MAX_RADIUS}.
     */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> radiusBranch(
            boolean refresh) {
        return Commands.literal("around")
                .executes(ctx -> radiusCommand(ctx, refresh, DEFAULT_RADIUS, false))
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, MAX_RADIUS))
                        .executes(ctx -> radiusCommand(ctx, refresh,
                                IntegerArgumentType.getInteger(ctx, "radius"), false))
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .executes(ctx -> radiusCommand(ctx, refresh,
                                        IntegerArgumentType.getInteger(ctx, "radius"), true))));
    }

    private static int clearCommand(CommandContext<CommandSourceStack> ctx, boolean refresh, boolean perPlayer)
            throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");

        Collection<GameProfile> profiles = perPlayer ? GameProfileArgument.getGameProfiles(ctx, "player") : null;
        Collection<UUID> uuids = profiles == null ? null : profileUuids(profiles);
        final int removed = clearContainer(level, pos, uuids);
        if (removed < 0) {
            // No proxy-managed loot container at the position (missing, or plain storage).
            src.sendFailure(Component.translatable("command.prosperity.no_container", formatPos(pos)));
            return 0;
        }
        final String posStr = formatPos(pos);

        if (perPlayer) {
            final String names = joinNames(profiles);
            src.sendSuccess(() -> Component.translatable(
                    refresh ? "command.prosperity.refresh.player" : "command.prosperity.reset.player",
                    names, posStr, removed), true);
        } else {
            src.sendSuccess(() -> Component.translatable(
                    refresh ? "command.prosperity.refresh.all" : "command.prosperity.reset.all",
                    posStr, removed), true);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int radiusCommand(CommandContext<CommandSourceStack> ctx, boolean refresh, int radius,
            boolean perPlayer) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        BlockPos center = BlockPos.containing(src.getPosition());

        Collection<GameProfile> profiles = perPlayer ? GameProfileArgument.getGameProfiles(ctx, "player") : null;
        Collection<UUID> uuids = profiles == null ? null : profileUuids(profiles);
        final int removed = clearRadius(level, center, radius, uuids);
        final int blocks = radius;

        if (perPlayer) {
            final String names = joinNames(profiles);
            src.sendSuccess(() -> Component.translatable(
                    refresh ? "command.prosperity.refresh.radius.player" : "command.prosperity.reset.radius.player",
                    names, blocks, removed), true);
        } else {
            src.sendSuccess(() -> Component.translatable(
                    refresh ? "command.prosperity.refresh.radius.all" : "command.prosperity.reset.radius.all",
                    blocks, removed), true);
        }
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Clears instanced loot at {@code pos}. A {@code null} {@code uuids} clears every player;
     * otherwise only the listed players are cleared. Returns the number of player instances
     * removed (or {@code -1} when no instanced container is present), and resends the chunk's
     * indicator set so the now-unlooted container re-lights on tracking clients. Exposed for gametests.
     */
    public static int clearContainer(ServerLevel level, BlockPos pos, @Nullable Collection<UUID> uuids) {
        if (!(level.getBlockEntity(pos) instanceof RandomizableContainerBlockEntity container)) {
            return -1;
        }
        int removed = clearInstances(container, uuids);
        if (removed > 0) {
            ProsperityNetworking.resendUnlootedChunk(level, new ChunkPos(pos), uuids);
        }
        return removed;
    }

    /**
     * Clears instanced loot for every proxy-managed container within {@code radius} blocks of
     * {@code center} in loaded chunks (the scan never force-loads). A {@code null} {@code uuids} clears
     * every player; otherwise only the listed players. Returns the total instance count removed, and
     * resends the indicator set for each affected chunk so the now-unlooted containers re-light on
     * tracking clients. Exposed for gametests.
     */
    public static int clearRadius(ServerLevel level, BlockPos center, int radius,
            @Nullable Collection<UUID> uuids) {
        double maxDistSq = (double) radius * radius;
        int chunkRadius = (radius >> 4) + 1;
        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;
        int totalRemoved = 0;
        Set<ChunkPos> affected = new HashSet<>();
        for (int cx = centerChunkX - chunkRadius; cx <= centerChunkX + chunkRadius; cx++) {
            for (int cz = centerChunkZ - chunkRadius; cz <= centerChunkZ + chunkRadius; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;
                }
                for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                    if (!(entry.getValue() instanceof RandomizableContainerBlockEntity container)) {
                        continue;
                    }
                    BlockPos pos = entry.getKey();
                    if (center.distSqr(pos) > maxDistSq) {
                        continue;
                    }
                    int removed = clearInstances(container, uuids);
                    if (removed > 0) {
                        totalRemoved += removed;
                        affected.add(new ChunkPos(pos));
                    }
                }
            }
        }
        for (ChunkPos chunkPos : affected) {
            ProsperityNetworking.resendUnlootedChunk(level, chunkPos, uuids);
        }
        return totalRemoved;
    }

    /**
     * Clears the instanced loot held by {@code container} for the given players (or every player when
     * {@code uuids} is {@code null}), dirtying the block entity. Returns the number of instances
     * removed, or {@code -1} when the container is not proxy-managed (plain storage). Does not touch
     * the client — callers resend the affected chunk(s) once.
     */
    private static int clearInstances(RandomizableContainerBlockEntity container,
            @Nullable Collection<UUID> uuids) {
        InstancedLootData data = ProsperityAttachments.get(container);
        // A plain storage container (no loot table, no generated attachment) is not proxy-managed.
        if (container.getLootTable() == null && (data == null || !data.isGenerated())) {
            return -1;
        }
        if (data == null) {
            // A loot container nobody has opened yet: managed, but no instances to clear.
            return 0;
        }
        int removed;
        if (uuids == null) {
            removed = data.playerIds().size();
            data.clearAll();
        } else {
            removed = 0;
            for (UUID id : uuids) {
                if (data.hasGenerated(id)) {
                    removed++;
                }
                data.clearForPlayer(id);
            }
        }
        container.setChanged();
        return removed;
    }

    // ---- reload ----

    private static int runReload(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        try {
            Prosperity.reloadConfig();
        } catch (Exception e) {
            Prosperity.LOGGER.error("Config reload failed via command", e);
            src.sendFailure(Component.translatable("command.prosperity.reload_failed", String.valueOf(e.getMessage())));
            return 0;
        }
        final int synced = ProsperityNetworking.syncConfigToAll(src.getServer());
        src.sendSuccess(() -> Component.translatable("command.prosperity.reload", synced), true);
        return Command.SINGLE_SUCCESS;
    }

    // ---- shared helpers ----

    private static Collection<UUID> profileUuids(Collection<GameProfile> profiles) {
        List<UUID> ids = new ArrayList<>(profiles.size());
        for (GameProfile profile : profiles) {
            ids.add(profile.getId());
        }
        return ids;
    }

    private static String joinNames(Collection<GameProfile> profiles) {
        List<String> names = new ArrayList<>(profiles.size());
        for (GameProfile profile : profiles) {
            names.add(profile.getName());
        }
        return String.join(", ", names);
    }

    private static String formatPos(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    private static String capitalize(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
