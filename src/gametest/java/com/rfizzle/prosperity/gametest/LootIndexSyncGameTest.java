package com.rfizzle.prosperity.gametest;

import com.rfizzle.prosperity.loot.index.LootIndexDataSource;
import com.rfizzle.prosperity.network.LootIndexS2CPayload;
import com.rfizzle.prosperity.network.ProsperityNetworking;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Light smoke coverage for the loot-index sync (S-047). A headless mock player's {@code canSend} is
 * false and there are no client recipe viewers in a gametest, so packet receipt and viewer reload
 * cannot be observed here — the wire format is covered by {@code LootIndexPayloadCodecTest} and the
 * remote display is the manual two-instance check. These assert the server build + send paths are
 * wired and run without error.
 */
public class LootIndexSyncGameTest implements FabricGameTest {

    /** The index builds from the server's loot tables and the broadcast/payload paths run cleanly. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void indexBuildsAndSyncsWithoutError(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();

        LootIndexDataSource.rebuild(server);
        helper.assertTrue(!LootIndexDataSource.snapshot().isEmpty(),
                "the loot index should build from the server's chest loot tables");

        // of(...) bounds the payload to the wire cap without dropping anything under it.
        LootIndexS2CPayload payload = LootIndexS2CPayload.of(LootIndexDataSource.snapshot());
        helper.assertTrue(payload.rows().size()
                        == Math.min(LootIndexDataSource.snapshot().size(), LootIndexS2CPayload.MAX_ENTRIES),
                "the payload should carry the snapshot rows, capped at MAX_ENTRIES");

        // No connected/registered clients, so this admits none — it must simply not throw.
        helper.assertTrue(ProsperityNetworking.syncLootIndexToAll(server) == 0,
                "broadcast to a player-less server should send to nobody");
        helper.succeed();
    }

    /** Joining a mock player drives the join sync (config + index) without error; canSend is false. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    @SuppressWarnings("removal")
    public void joinSyncRunsForMockPlayer(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        ProsperityNetworking.sendJoinSync(player);
        helper.succeed();
    }
}
