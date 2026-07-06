package com.rfizzle.prosperity.gametest;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.api.PartyGroupProvider;
import com.rfizzle.prosperity.api.ProsperityAPI;
import com.rfizzle.prosperity.config.ProsperityConfig;
import com.rfizzle.prosperity.loot.PartyLootKeys;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

/**
 * Runtime checks for the {@link PartyGroupProvider} machinery that a party-mod adapter (the Open
 * Parties and Claims adapter is the first) feeds: an API-supplied group key overrides the scoreboard
 * team, a {@code null}/blank key defers through the #53 chain (team, then individual), and a provider
 * that throws is isolated so it cannot break resolution. The provider is <strong>synthetic</strong> —
 * OPAC itself is not on the gametest classpath, so this exercises the generic seam the adapter plugs
 * into, exactly as {@code OpacPartyCompat}'s own provider would drive it, without depending on OPAC.
 *
 * <p>Runs in its own batch because it mutates the global {@code partyLootMode} config and a shared
 * scoreboard team. The synthetic provider is registered once and delegates to a per-test
 * {@link #DELEGATE} that every test clears in a {@code finally}: with no delegate installed the
 * provider returns {@code null}, so it is inert for every other test and batch (the API has no
 * unregister hook — a leaked non-null key would corrupt the scoreboard-team tests in
 * {@code PartyLootGameTest}).
 */
public class PartyGroupProviderGameTest implements FabricGameTest {

    private static final String BATCH = "party_provider";
    private static final String TEAM = "explorers";

    /** The key the synthetic provider yields while a test has one installed; {@code null} otherwise. */
    private static final AtomicReference<PartyGroupProvider> DELEGATE = new AtomicReference<>();
    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    /**
     * Register the synthetic provider exactly once for the server's lifetime. It forwards to whatever
     * {@link #DELEGATE} a test installs and returns {@code null} when none is — so outside an active
     * test it defers, leaving scoreboard-team resolution untouched.
     */
    private static void ensureProvider() {
        if (REGISTERED.compareAndSet(false, true)) {
            ProsperityAPI.registerPartyGroupProvider(player -> {
                PartyGroupProvider delegate = DELEGATE.get();
                return delegate == null ? null : delegate.groupKey(player);
            });
        }
    }

    @SuppressWarnings("removal")
    private ServerPlayer spawnPlayer(GameTestHelper helper) {
        return helper.makeMockServerPlayerInLevel();
    }

    private void joinTeam(GameTestHelper helper, ServerPlayer player) {
        Scoreboard scoreboard = helper.getLevel().getScoreboard();
        PlayerTeam team = scoreboard.getPlayerTeam(TEAM);
        if (team == null) {
            team = scoreboard.addPlayerTeam(TEAM);
        }
        scoreboard.addPlayerToTeam(player.getScoreboardName(), team);
    }

    /** Restore every piece of shared state this batch touches: the delegate, the team, and the config. */
    private void cleanup(GameTestHelper helper, ServerPlayer player, boolean savedMode, int savedGrace) {
        DELEGATE.set(null);
        Scoreboard scoreboard = helper.getLevel().getScoreboard();
        PlayerTeam team = scoreboard.getPlayerTeam(TEAM);
        if (team != null) {
            scoreboard.removePlayerTeam(team);
        }
        ProsperityConfig cfg = Prosperity.getConfig();
        cfg.partyLootMode = savedMode;
        cfg.teamLeaveGraceMinutes = savedGrace;
        player.discard();
    }

    /** An API-supplied key overrides the scoreboard team: the player resolves to the party, not the team. */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    public void overrideKeyWinsOverScoreboardTeam(GameTestHelper helper) {
        ProsperityConfig cfg = Prosperity.getConfig();
        boolean savedMode = cfg.partyLootMode;
        int savedGrace = cfg.teamLeaveGraceMinutes;
        cfg.partyLootMode = true;
        ensureProvider();
        ServerPlayer player = spawnPlayer(helper);
        joinTeam(helper, player);
        DELEGATE.set(p -> "opac:party-a");

        try {
            helper.assertTrue("opac:party-a".equals(ProsperityAPI.partyGroupOverride(player)),
                    "the provider's key must be the resolved override");
            helper.assertTrue(PartyLootKeys.currentGroupKey(player).equals(PartyLootKeys.teamKey("opac:party-a")),
                    "the player must resolve to the API party key");
            helper.assertFalse(PartyLootKeys.currentGroupKey(player).equals(PartyLootKeys.teamKey(TEAM)),
                    "the API key must win over the scoreboard team");
            helper.assertTrue(PartyLootKeys.resolve(player, null).equals(PartyLootKeys.teamKey("opac:party-a")),
                    "a fresh container must resolve to the API party key");
        } finally {
            cleanup(helper, player, savedMode, savedGrace);
        }
        helper.succeed();
    }

    /** A null key defers: a team member falls back to the scoreboard team. */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    public void nullKeyFallsBackToScoreboardTeam(GameTestHelper helper) {
        ProsperityConfig cfg = Prosperity.getConfig();
        boolean savedMode = cfg.partyLootMode;
        int savedGrace = cfg.teamLeaveGraceMinutes;
        cfg.partyLootMode = true;
        ensureProvider();
        ServerPlayer player = spawnPlayer(helper);
        joinTeam(helper, player);
        DELEGATE.set(p -> null);

        try {
            helper.assertTrue(ProsperityAPI.partyGroupOverride(player) == null,
                    "a null-returning provider must supply no override");
            helper.assertTrue(PartyLootKeys.currentGroupKey(player).equals(PartyLootKeys.teamKey(TEAM)),
                    "a deferring provider must fall back to the scoreboard team");
        } finally {
            cleanup(helper, player, savedMode, savedGrace);
        }
        helper.succeed();
    }

    /** A null key with no scoreboard team defers all the way to individual instancing (the player's UUID). */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    public void nullKeyTeamlessFallsBackToIndividual(GameTestHelper helper) {
        ProsperityConfig cfg = Prosperity.getConfig();
        boolean savedMode = cfg.partyLootMode;
        int savedGrace = cfg.teamLeaveGraceMinutes;
        cfg.partyLootMode = true;
        cfg.teamLeaveGraceMinutes = 0;
        ensureProvider();
        ServerPlayer player = spawnPlayer(helper);
        DELEGATE.set(p -> null);

        try {
            helper.assertTrue(PartyLootKeys.currentGroupKey(player) == null,
                    "a teamless player with no override must have no group key");
            helper.assertTrue(PartyLootKeys.resolve(player, null).equals(player.getUUID()),
                    "a teamless, un-overridden player must resolve to their own individual instance");
        } finally {
            cleanup(helper, player, savedMode, savedGrace);
        }
        helper.succeed();
    }

    /** A blank key is treated as a deferral, not a real group, so a team member falls back to the team. */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    public void blankKeyDefers(GameTestHelper helper) {
        ProsperityConfig cfg = Prosperity.getConfig();
        boolean savedMode = cfg.partyLootMode;
        int savedGrace = cfg.teamLeaveGraceMinutes;
        cfg.partyLootMode = true;
        ensureProvider();
        ServerPlayer player = spawnPlayer(helper);
        joinTeam(helper, player);
        DELEGATE.set(p -> "   ");

        try {
            helper.assertTrue(ProsperityAPI.partyGroupOverride(player) == null,
                    "a blank key must be treated as a deferral, not an override");
            helper.assertTrue(PartyLootKeys.currentGroupKey(player).equals(PartyLootKeys.teamKey(TEAM)),
                    "a blank-key provider must fall back to the scoreboard team");
        } finally {
            cleanup(helper, player, savedMode, savedGrace);
        }
        helper.succeed();
    }

    /** A provider that throws is caught by the API and treated as a deferral; resolution still falls back. */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    public void throwingProviderIsIsolated(GameTestHelper helper) {
        ProsperityConfig cfg = Prosperity.getConfig();
        boolean savedMode = cfg.partyLootMode;
        int savedGrace = cfg.teamLeaveGraceMinutes;
        cfg.partyLootMode = true;
        ensureProvider();
        ServerPlayer player = spawnPlayer(helper);
        joinTeam(helper, player);
        DELEGATE.set(p -> {
            throw new IllegalStateException("synthetic provider failure");
        });

        try {
            helper.assertTrue(ProsperityAPI.partyGroupOverride(player) == null,
                    "a throwing provider must be isolated, yielding no override rather than propagating");
            helper.assertTrue(PartyLootKeys.currentGroupKey(player).equals(PartyLootKeys.teamKey(TEAM)),
                    "resolution must still fall back to the scoreboard team after a provider throws");
        } finally {
            cleanup(helper, player, savedMode, savedGrace);
        }
        helper.succeed();
    }
}
