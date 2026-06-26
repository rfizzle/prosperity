package com.rfizzle.prosperity.gametest;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.compat.LootTooltip;
import com.rfizzle.prosperity.compat.LootTooltip.Status;
import com.rfizzle.prosperity.loot.BlockEntityContainerAdapter;
import com.rfizzle.prosperity.loot.InstancedLootInteraction;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * Runtime checks for the container probe tooltip (S-026). {@link LootTooltip#writeServerData} packs the
 * per-look state for a given player; these drive it directly with a placed container and a mock player,
 * then assert the status and the rendered line set through the layer's public surface ({@code readStatus}
 * / {@code buildLines}). The status is resolved for the <em>looking</em> player, so the per-player case
 * is the core assertion. Jade itself is not exercised headlessly — the data layer is loader-agnostic by
 * design, so the gametest covers it without the probe mod.
 *
 * <p>The refresh and blacklist cases mutate the global config, so they run in their own batch (batches
 * run sequentially) and restore it in a {@code finally}.
 */
public class ContainerTooltipGameTest implements FabricGameTest {

    private static final String BATCH = "tooltip_config";
    private static final ResourceKey<LootTable> TABLE = BuiltInLootTables.SIMPLE_DUNGEON;
    private static final long SEED = 7777L;

    private RandomizableContainerBlockEntity placeLootContainer(GameTestHelper helper, BlockPos rel,
            Block block) {
        helper.setBlock(rel, block);
        RandomizableContainerBlockEntity be =
                (RandomizableContainerBlockEntity) helper.getBlockEntity(rel);
        be.setLootTable(TABLE);
        be.setLootTableSeed(SEED);
        return be;
    }

    @SuppressWarnings("removal")
    private ServerPlayer spawnPlayerAt(GameTestHelper helper, BlockPos rel) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos abs = helper.absolutePos(rel);
        player.teleportTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);
        return player;
    }

    private CompoundTag writeData(GameTestHelper helper, BlockPos rel, ServerPlayer player) {
        CompoundTag tag = new CompoundTag();
        LootTooltip.writeServerData(tag, helper.getLevel(), player, helper.absolutePos(rel),
                helper.getBlockEntity(rel));
        return tag;
    }

    private void generate(GameTestHelper helper, BlockPos rel, RandomizableContainerBlockEntity be,
            ServerPlayer player) {
        InstancedLootInteraction.generateAndStore(
                new BlockEntityContainerAdapter(helper.getLevel(), helper.absolutePos(rel), be), player);
    }

    /** The tooltip line carrying the given translation key, or {@code null} if none is present. */
    private static Component lineWithKey(List<Component> lines, String key) {
        for (Component line : lines) {
            if (line.getContents() instanceof TranslatableContents t && key.equals(t.getKey())) {
                return line;
            }
        }
        return null;
    }

    private void withRefresh(boolean enabled, int days, Runnable body) {
        boolean savedEnabled = Prosperity.getConfig().enableLootRefresh;
        int savedDays = Prosperity.getConfig().lootRefreshDays;
        try {
            Prosperity.getConfig().enableLootRefresh = enabled;
            Prosperity.getConfig().lootRefreshDays = days;
            body.run();
        } finally {
            Prosperity.getConfig().enableLootRefresh = savedEnabled;
            Prosperity.getConfig().lootRefreshDays = savedDays;
        }
    }

    private void withBlacklist(String id, Runnable body) {
        List<String> saved = Prosperity.getConfig().lootTableBlacklist;
        try {
            Prosperity.getConfig().lootTableBlacklist = List.of(id);
            Prosperity.getConfig().clamp();
            body.run();
        } finally {
            Prosperity.getConfig().lootTableBlacklist = saved;
            Prosperity.getConfig().clamp();
        }
    }

    /** A never-opened loot container reads Unlooted, with the live distance-tier line present. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void unlootedWritesGoldStatusAndTier(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        placeLootContainer(helper, rel, Blocks.CHEST);
        ServerPlayer player = spawnPlayerAt(helper, rel);

        CompoundTag tag = writeData(helper, rel, player);
        helper.assertTrue(LootTooltip.readStatus(tag) == Status.UNLOOTED,
                "a never-opened loot container reads Unlooted");
        List<Component> lines = LootTooltip.buildLines(tag);
        helper.assertTrue(lineWithKey(lines, "prosperity.jade.tier") != null,
                "the distance-tier line is present with scaling enabled");
        helper.assertTrue(lineWithKey(lines, "prosperity.jade.refresh") == null,
                "no refresh timer with refresh disabled");
        player.discard();
        helper.succeed();
    }

    /** After generating, the same player reads Looted. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void lootedAfterGenerate(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        RandomizableContainerBlockEntity be = placeLootContainer(helper, rel, Blocks.CHEST);
        ServerPlayer player = spawnPlayerAt(helper, rel);
        generate(helper, rel, be, player);

        helper.assertTrue(LootTooltip.readStatus(writeData(helper, rel, player)) == Status.LOOTED,
                "a generated container reads Looted for the player who opened it");
        player.discard();
        helper.succeed();
    }

    /** Status is resolved for the looking player: A (generated) reads Looted, B (untouched) Unlooted. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void statusIsPerLookingPlayer(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        RandomizableContainerBlockEntity be = placeLootContainer(helper, rel, Blocks.CHEST);
        ServerPlayer playerA = spawnPlayerAt(helper, rel);
        ServerPlayer playerB = spawnPlayerAt(helper, rel);
        generate(helper, rel, be, playerA);

        helper.assertTrue(LootTooltip.readStatus(writeData(helper, rel, playerA)) == Status.LOOTED,
                "player A generated, so A sees Looted");
        helper.assertTrue(LootTooltip.readStatus(writeData(helper, rel, playerB)) == Status.UNLOOTED,
                "player B never opened it, so B still sees Unlooted");
        playerA.discard();
        playerB.discard();
        helper.succeed();
    }

    /** Plain player-placed storage (no loot table) writes no tooltip data at all. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void plainStorageWritesNothing(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        helper.setBlock(rel, Blocks.CHEST);
        ServerPlayer player = spawnPlayerAt(helper, rel);

        CompoundTag tag = writeData(helper, rel, player);
        helper.assertTrue(LootTooltip.readStatus(tag) == null, "plain storage must not write tooltip data");
        helper.assertTrue(LootTooltip.buildLines(tag).isEmpty(), "and so renders no lines");
        player.discard();
        helper.succeed();
    }

    /** An expired instance reads Refreshed (green status), with no countdown line. */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    public void expiredReadsRefreshed(GameTestHelper helper) {
        withRefresh(true, 0, () -> {
            BlockPos rel = new BlockPos(1, 1, 1);
            RandomizableContainerBlockEntity be = placeLootContainer(helper, rel, Blocks.CHEST);
            ServerPlayer player = spawnPlayerAt(helper, rel);
            generate(helper, rel, be, player);

            CompoundTag tag = writeData(helper, rel, player);
            helper.assertTrue(LootTooltip.readStatus(tag) == Status.REFRESHED,
                    "a past-cooldown instance reads Refreshed");
            helper.assertTrue(lineWithKey(LootTooltip.buildLines(tag), "prosperity.jade.refresh") == null,
                    "an expired instance shows no running countdown");
            player.discard();
            helper.succeed();
        });
    }

    /** A looted instance within its cooldown reads Looted and carries a running refresh countdown. */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    public void runningCountdownWritesRefreshLine(GameTestHelper helper) {
        withRefresh(true, 1, () -> {
            BlockPos rel = new BlockPos(1, 1, 1);
            RandomizableContainerBlockEntity be = placeLootContainer(helper, rel, Blocks.CHEST);
            ServerPlayer player = spawnPlayerAt(helper, rel);
            generate(helper, rel, be, player);

            CompoundTag tag = writeData(helper, rel, player);
            helper.assertTrue(LootTooltip.readStatus(tag) == Status.LOOTED, "within cooldown reads Looted");
            Component refresh = lineWithKey(LootTooltip.buildLines(tag), "prosperity.jade.refresh");
            helper.assertTrue(refresh != null, "an unexpired looted instance carries a refresh countdown");
            // generate and the read happen on the same tick, so the full one-day cooldown remains.
            Object arg = ((TranslatableContents) refresh.getContents()).getArgs()[0];
            helper.assertTrue("1d 0h".equals(arg), "a fresh one-day cooldown formats as '1d 0h', was " + arg);
            player.discard();
            helper.succeed();
        });
    }

    /** A blacklisted loot table reads Vanilla and renders only that one line. */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    public void blacklistedReadsVanilla(GameTestHelper helper) {
        withBlacklist(TABLE.location().toString(), () -> {
            BlockPos rel = new BlockPos(1, 1, 1);
            placeLootContainer(helper, rel, Blocks.CHEST);
            ServerPlayer player = spawnPlayerAt(helper, rel);

            CompoundTag tag = writeData(helper, rel, player);
            helper.assertTrue(LootTooltip.readStatus(tag) == Status.VANILLA,
                    "a blacklisted container reads Vanilla");
            List<Component> lines = LootTooltip.buildLines(tag);
            helper.assertTrue(lineWithKey(lines, "prosperity.jade.tier") == null,
                    "vanilla loot shows no scaling lines");
            helper.assertTrue(lines.size() == 1, "vanilla renders only the status line");
            player.discard();
            helper.succeed();
        });
    }
}
