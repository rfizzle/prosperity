package com.rfizzle.concord.gametest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;

import java.util.List;
import java.util.UUID;

/**
 * Connected mock-player factory for gametests, shared by every Concord member. HUB-OWNED: this file
 * is propagated verbatim from the concord hub into each member's gametest source set, so edit it in
 * {@code rfizzle/concord} ({@code propagate/src/gametest/java/...}), never in a member.
 *
 * <p>It is the faithful replica of the deprecated {@code GameTestHelper.makeMockServerPlayerInLevel()}
 * built from public, non-deprecated APIs: a real {@link Connection} backed by an
 * {@link EmbeddedChannel} (which absorbs sent packets), fully registered in the player list via
 * {@code placeNewPlayer}. The channel is handed back so tests can read the packets the server sent.
 *
 * <p>This is build/test-time code only. It lives in the gametest source set, which is never part of
 * a shipped jar and never co-loaded with another member's, so a fixed package is safe here. Runtime
 * classes must never be shared this way: every mod loads into one classloader in-game.
 *
 * <p>This is the connected replica only. {@code GameTestHelper#makeMockPlayer} returns a
 * lightweight stub that is never added to the level or the player list, so it accrues none of the
 * cost {@link #retire} releases and keeps its plain {@code discard()}.
 */
public final class MockPlayers {

    /**
     * The mock profile name, scoped to the mod under test. {@code concord-common.gradle} sets the
     * {@code concord.modid} system property on {@code runGametest}. A bare
     * {@code "test-mock-player"} is shared by every mod's helper, so {@link #retireLeaked} in one
     * mod would retire another mod's live mock when both are tested into the same level.
     */
    public static final String MOCK_NAME = System.getProperty("concord.modid", "concord") + "-test-mock-player";

    /** A connected player plus the embedded channel its outbound packets land in. */
    public record Connected(ServerPlayer player, EmbeddedChannel channel) {
    }

    private MockPlayers() {
    }

    /** The connected {@link ServerPlayer} replica; spawns near world spawn — teleport as needed. */
    public static ServerPlayer serverPlayerInLevel(GameTestHelper helper) {
        return connectedServerPlayerInLevel(helper).player();
    }

    /** Same replica, with the packet-absorbing channel exposed for outbound assertions. */
    public static Connected connectedServerPlayerInLevel(GameTestHelper helper) {
        return connectedInLevel(helper, false);
    }

    /**
     * A connected replica that reports as a spectator — for tests that assert a
     * spectator is left out of a feature's accounting or its broadcasts.
     *
     * <p>It is a spectator through {@code isSpectator()} only: it is still placed
     * with the server's default {@code GameType} and still reports creative, so
     * code gating on {@code GameType.SPECTATOR} or on {@code !isCreative()} runs
     * the wrong branch against it. Check which predicate the code under test uses.
     */
    public static Connected spectatorServerPlayerInLevel(GameTestHelper helper) {
        return connectedInLevel(helper, true);
    }

    /**
     * Fully retires a connected mock: woken if asleep, out of the player list,
     * entity discarded — so entries do not accumulate across the shared test
     * server.
     *
     * <p>{@code discard()} alone releases the entity tick and the chunk ticket
     * but leaves the player-list entry, and that entry is not inert: its
     * advancement listeners stay registered, so every criterion the mod fires
     * keeps evaluating against a player no test is watching.
     * {@code PlayerList#remove} is the only path that clears it.
     *
     * <p><strong>Idempotent.</strong> {@code PlayerList#remove} calls
     * {@code save(player)} and awards {@code Stats.LEAVE_GAME} with no
     * {@code isRemoved()} guard of its own, so a second call rewrites the player
     * {@code .dat}, the stats JSON, and the advancements JSON, and inflates the
     * leave-game stat. Retiring is the kind of thing a {@code finally} and a
     * leak sweep both reach for, so the guard belongs here rather than being
     * re-derived at every call site.
     */
    public static void retire(ServerPlayer player) {
        if (player.isRemoved()) {
            return;
        }
        wake(player);
        MinecraftServer server = player.getServer();
        if (server != null) {
            server.getPlayerList().remove(player);
        }
        player.discard();
    }

    /**
     * Retires any mock player a previously failed test left in the helper's
     * level, so player-count-sensitive tests start from a clean player list.
     *
     * <p><strong>Not safe to call from a test in the default batch.</strong> It
     * reads the whole level, and same-batch gametests run concurrently in one
     * level — {@code GameTestRunner.runBatch} spawns a whole batch's structures
     * and then ticks them together. A sweep from a test sharing the default batch
     * would retire a sibling's live player out from under it. Give the test a
     * {@code batch} of its own before calling this.
     */
    public static void retireLeaked(GameTestHelper helper) {
        for (ServerPlayer player : List.copyOf(helper.getLevel().players())) {
            // Matched against the constant, never a bare literal: the name is
            // mod-scoped precisely so this sweep cannot retire a sibling mod's
            // live mock when two members are tested into the same level.
            if (MOCK_NAME.equals(player.getGameProfile().getName())) {
                retire(player);
            }
        }
    }

    /**
     * Wakes a sleeping mock before it leaves the player list.
     *
     * <p>The second argument is the one that matters: it gates
     * {@code ServerLevel.updateSleepingPlayerList()}. Passing {@code false}
     * clears the player's own sleep flag but leaves {@code SleepStatus} still
     * counting someone who is gone, so {@code areEnoughSleeping(...)} reads
     * stale for the rest of the run. Vanilla's own {@code stopSleeping()} passes
     * {@code (true, true)}.
     */
    private static void wake(ServerPlayer player) {
        if (player.isSleeping()) {
            player.stopSleepInBed(true, true);
        }
    }

    private static Connected connectedInLevel(GameTestHelper helper, boolean spectator) {
        GameProfile profile = new GameProfile(UUID.randomUUID(), MOCK_NAME);
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);

        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer player = new ServerPlayer(server, level, cookie.gameProfile(), cookie.clientInformation()) {
            @Override
            public boolean isSpectator() {
                return spectator;
            }

            @Override
            public boolean isCreative() {
                return true;
            }
        };

        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        return new Connected(player, channel);
    }
}
