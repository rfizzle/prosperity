package com.rfizzle.prosperity.loot;

import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Tells a fake {@link ServerPlayer} (automation mods opening containers through a synthetic player)
 * apart from a genuine opener, so the instanced-loot adapters can pass automation through to vanilla
 * untouched (SPEC §1 Container Adapters — Fake-player guard). Centralised here because every loot
 * shape gates on it: the block-entity path (S-034), the minecart entity path (S-035), and the
 * brushable path (S-036).
 *
 * <p>No single field is a reliable tell, so the check is the union of three:
 * <ul>
 *   <li><b>{@link FakePlayer} type</b> — Fabric's fake player carries a non-null synthetic
 *       {@code connection}, so the connection check below does not catch it.</li>
 *   <li><b>Null {@code connection}</b> — fake players from other implementations (and directly
 *       constructed {@code ServerPlayer}s) have no network handler.</li>
 *   <li><b>Absent from the player list</b> — the universal catch: a genuine opener is always
 *       registered in {@link MinecraftServer#getPlayerList()}; a fake one never is, even when it
 *       borrows a real player's profile (identity comparison, not UUID lookup, distinguishes the
 *       two).</li>
 * </ul>
 */
public final class FakePlayers {

    private FakePlayers() {
    }

    /** Whether {@code player} is a synthetic opener that should be passed through to vanilla. */
    public static boolean isFakePlayer(ServerPlayer player) {
        if (player instanceof FakePlayer) {
            return true;
        }
        if (player.connection == null) {
            return true;
        }
        MinecraftServer server = player.getServer();
        return server == null || !server.getPlayerList().getPlayers().contains(player);
    }
}
