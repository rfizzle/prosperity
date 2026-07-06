package com.rfizzle.prosperity.api;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.config.DistanceTier;
import com.rfizzle.prosperity.config.ProsperityConfig;
import com.rfizzle.prosperity.loot.LootScaling;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jetbrains.annotations.Nullable;

/**
 * Public, read-only API for Prosperity (Concord API Standard v1).
 *
 * <p>The geographic distance-tier accessors ({@link #getDistanceTier}, {@link #getTierForPlayer},
 * {@link #getDistanceTiers}) let sibling and third-party mods read "which loot tier is this position
 * / player in" — the reward-axis counterpart to a difficulty tier. They are server-authoritative and
 * delegate to the same resolution path generation and the HUD use, so they reflect a
 * {@code /prosperity reload} automatically and never drift.
 *
 * <p>Also the HUD coordination accessors of the Concord HUD Standard (§6): a lower-priority HUD slot
 * in a sibling mod queries {@link #isHudVisible}/{@link #getHudHeight} to offset past Prosperity's
 * slot-3 tier badge without hardcoding its height. Reflection-backed into the client overlay so this
 * common-side class never references client-only code.
 *
 * <p>Safe to use as a soft dependency: compile with {@code modCompileOnly} and guard call sites with
 * {@code FabricLoader.getInstance().isModLoaded("prosperity")}.
 */
@Stable
public final class ProsperityAPI {

    private ProsperityAPI() {
    }

    /**
     * The geographic distance tier of {@code pos} in {@code level}: Euclidean XZ distance from world
     * origin selects the band, the Nether reads those raw coordinates unchanged (no &times;8), and the
     * End forces the highest tier when {@code endAlwaysMaxTier} is configured. Reflects live config, so
     * a {@code /prosperity reload} that moves a boundary is honored on the next call.
     *
     * <p>This is the <em>geographic</em> tier only: structure overrides (SPEC §6) are not applied — a
     * monument or stronghold's per-structure adjustment is a separate, generation-time concern and is
     * deliberately not folded in here. The position is read at block coordinates.
     *
     * <p>Never returns {@code null}: off the tier ladder (empty tier list, or a position below the
     * lowest band) it returns the {@code local} sentinel — {@code index 0}, {@code minDistance 0},
     * {@code stackMultiplier 1.0}, {@code qualityModifier 0}.
     *
     * @param level the server level supplying the dimension rules
     * @param pos   the position to resolve, read at block coordinates
     * @return the geographic tier, or the {@code local} sentinel off the ladder
     */
    public static DistanceTierInfo getDistanceTier(ServerLevel level, BlockPos pos) {
        ProsperityConfig cfg = Prosperity.getConfig();
        DistanceTier tier =
                LootScaling.resolveTier(cfg, level.dimension() == Level.END, pos.getX(), pos.getZ());
        return toInfo(tier, cfg);
    }

    /**
     * The geographic distance tier at {@code player}'s current position, resolved at the player's
     * fractional X/Z (full precision) in their current dimension. Same rules and sentinel as
     * {@link #getDistanceTier(ServerLevel, BlockPos)}; structure overrides are likewise not applied.
     *
     * @param player the player whose position is resolved
     * @return the geographic tier at the player's position, or the {@code local} sentinel off the ladder
     */
    public static DistanceTierInfo getTierForPlayer(ServerPlayer player) {
        ProsperityConfig cfg = Prosperity.getConfig();
        ServerLevel level = player.serverLevel();
        DistanceTier tier =
                LootScaling.resolveTier(cfg, level.dimension() == Level.END, player.getX(), player.getZ());
        return toInfo(tier, cfg);
    }

    /**
     * The configured distance tiers, ascending by {@link DistanceTierInfo#minDistance()} so element
     * {@code i} carries {@code index() == i}. Lets a consumer enumerate the ladder instead of
     * hardcoding boundaries or names. Reflects live config; the returned list is unmodifiable.
     *
     * @return the configured tiers, ascending by distance (empty when none are configured)
     */
    public static List<DistanceTierInfo> getDistanceTiers() {
        return distanceTiers(Prosperity.getConfig());
    }

    /**
     * The ladder for {@code cfg} as public infos, ascending by distance. Package-private so the unit
     * test can assert shape and immutability against a hand-built config without the server singleton.
     */
    static List<DistanceTierInfo> distanceTiers(ProsperityConfig cfg) {
        List<DistanceTier> sorted = sortedTiers(cfg);
        List<DistanceTierInfo> infos = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            DistanceTier tier = sorted.get(i);
            infos.add(new DistanceTierInfo(tier.name(), i, tier.minDistance(),
                    tier.stackMultiplier(), tier.qualityModifier()));
        }
        return Collections.unmodifiableList(infos);
    }

    /**
     * Wraps an internal {@link DistanceTier} as the public {@link DistanceTierInfo}, computing
     * {@code index} from the tier's position in the config ladder sorted ascending by
     * {@link DistanceTier#minDistance()}. A tier not present in the ladder (the {@code local} sentinel
     * off an empty or sentinel-less config) maps to {@code index 0}. Package-private so the unit test
     * can exercise the real conversion against a hand-built config without the server singleton.
     */
    static DistanceTierInfo toInfo(DistanceTier tier, ProsperityConfig cfg) {
        List<DistanceTier> sorted = sortedTiers(cfg);
        int index = 0;
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).name().equals(tier.name())) {
                index = i;
                break;
            }
        }
        return new DistanceTierInfo(tier.name(), index, tier.minDistance(),
                tier.stackMultiplier(), tier.qualityModifier());
    }

    /** The config's non-null tiers, ascending by {@link DistanceTier#minDistance()}. */
    private static List<DistanceTier> sortedTiers(ProsperityConfig cfg) {
        List<DistanceTier> tiers = new ArrayList<>();
        if (cfg.distanceTiers != null) {
            for (DistanceTier tier : cfg.distanceTiers) {
                if (tier != null) {
                    tiers.add(tier);
                }
            }
        }
        tiers.sort(Comparator.comparingInt(DistanceTier::minDistance));
        return tiers;
    }

    // Party group-key providers (issue #53), consulted in registration order, first non-null wins.
    // Copy-on-write so registration (mod init) and the server-thread query never contend.
    private static final List<PartyGroupProvider> PARTY_GROUP_PROVIDERS = new CopyOnWriteArrayList<>();

    /**
     * Register a {@link PartyGroupProvider} so a social/party mod's own grouping drives party loot mode
     * (issue #53) in place of vanilla scoreboard teams. Providers are consulted in registration order
     * and the first non-null, non-blank {@link PartyGroupProvider#groupKey key} wins; when every
     * provider defers, Prosperity falls back to the player's scoreboard team. Idempotent-safe to call at
     * mod initialization. Only consulted while {@code partyLootMode} is enabled.
     *
     * @param provider the group-key provider to add; must not be {@code null}
     */
    public static void registerPartyGroupProvider(PartyGroupProvider provider) {
        if (provider != null) {
            PARTY_GROUP_PROVIDERS.add(provider);
        }
    }

    /**
     * The overriding party group key for {@code player} from the registered {@link PartyGroupProvider}s,
     * or {@code null} when none supplies one (so the caller falls back to the scoreboard team). The
     * first non-null, non-blank result wins. Each provider is invoked under host-side error isolation:
     * one that throws is logged once and skipped, never breaking loot resolution. Server-thread only.
     *
     * @param player the player whose group is being resolved
     * @return the overriding group key, or {@code null} to defer to the scoreboard-team default
     */
    @Nullable
    public static String partyGroupOverride(ServerPlayer player) {
        for (PartyGroupProvider provider : PARTY_GROUP_PROVIDERS) {
            String key;
            try {
                key = provider.groupKey(player);
            } catch (Throwable t) {
                if (PARTY_PROVIDER_FAILURE_LOGGED.compareAndSet(false, true)) {
                    Prosperity.LOGGER.warn("A party group provider threw; ignoring it for this and any"
                            + " further failing queries", t);
                }
                continue;
            }
            if (key != null && !key.isBlank()) {
                return key;
            }
        }
        return null;
    }

    private static final java.util.concurrent.atomic.AtomicBoolean PARTY_PROVIDER_FAILURE_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    // Render-thread only — resolved once on the first ENV=CLIENT call.
    private static boolean hudHandlesResolved;
    private static MethodHandle isHudVisibleHandle;
    private static MethodHandle getHudHeightHandle;

    private static void resolveHudHandles() {
        if (hudHandlesResolved) {
            return;
        }
        hudHandlesResolved = true;
        try {
            Class<?> overlay = Class.forName("com.rfizzle.prosperity.client.hud.ProsperityHudOverlay");
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            isHudVisibleHandle = lookup.findStatic(overlay, "isHudVisible", MethodType.methodType(boolean.class));
            getHudHeightHandle = lookup.findStatic(overlay, "getHudHeight", MethodType.methodType(int.class));
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
            isHudVisibleHandle = null;
            getHudHeightHandle = null;
        }
    }

    /**
     * HUD coordination accessor (Concord HUD-STANDARD §6): whether Prosperity's tier HUD badge is
     * currently being drawn. Safe to call unconditionally from common code on either side.
     *
     * <p>Reflection-backed into the client overlay so this class never references client-only code.
     * Documented sentinel: {@code false} on a dedicated server, when the HUD is config-disabled, or
     * when it is currently hidden (F1, open screen, spectator, death screen). Rendering coordination
     * only — never use for gameplay logic.
     *
     * @return true if the tier HUD badge is currently visible
     */
    public static boolean isHudVisible() {
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            resolveHudHandles();
            if (isHudVisibleHandle == null) {
                return false;
            }
            try {
                return (boolean) isHudVisibleHandle.invokeExact();
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    /**
     * HUD coordination accessor (Concord HUD-STANDARD §6): this element's current height contribution
     * in pixels (element + stacking gap), for a lower-priority HUD slot to offset past. Safe to call
     * unconditionally from common code on either side.
     *
     * <p>Reflection-backed into the client overlay. Documented sentinel: {@code 0} on a dedicated
     * server or whenever {@link #isHudVisible} is false; {@code 22} (20px standard element + 2px gap)
     * when visible.
     *
     * @return the element's height contribution in px, or 0 if not visible
     */
    public static int getHudHeight() {
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            resolveHudHandles();
            if (getHudHeightHandle == null) {
                return 0;
            }
            try {
                return (int) getHudHeightHandle.invokeExact();
            } catch (Throwable t) {
                return 0;
            }
        }
        return 0;
    }
}
