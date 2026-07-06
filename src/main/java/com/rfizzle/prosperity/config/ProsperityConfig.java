package com.rfizzle.prosperity.config;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rfizzle.prosperity.Prosperity;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Server + client config surface from SPEC §Configuration. Serialized to
 * {@code config/prosperity.json} via GSON. Server fields are top-level; client-only keys
 * live under the nested {@link #client} block so the server/client boundary is structural
 * (the synced server view is everything except {@code client}).
 *
 * <p>This is a plain bean: {@link Prosperity} owns the live volatile instance and reloads it
 * through {@link #load()}. A {@link #clamp()} pass sanitizes ranges and null fields after
 * every deserialize, so a config missing keys (or with out-of-range values) loads safely.
 */
public class ProsperityConfig {

    static final Gson GSON = new GsonBuilder().setPrettyPrinting().setLenient().create();

    /**
     * Serializer for the config-sync wire form. Non-pretty (no indentation whitespace — roughly a
     * third smaller than {@link #toJson()}) and drops the {@link #client} block, which the client
     * reads only from its own local config and never from the synced view. Together this keeps the
     * synced JSON comfortably inside
     * {@link com.rfizzle.prosperity.network.ConfigSyncS2CPayload#MAX_CONFIG_JSON_CHARS}.
     */
    private static final Gson SYNC_GSON = new GsonBuilder()
            .setLenient()
            .addSerializationExclusionStrategy(new ExclusionStrategy() {
                @Override
                public boolean shouldSkipField(FieldAttributes f) {
                    return ProsperityConfig.class.equals(f.getDeclaringClass()) && "client".equals(f.getName());
                }

                @Override
                public boolean shouldSkipClass(Class<?> clazz) {
                    return false;
                }
            })
            .create();

    /** Returned by {@link #tierFor(double)} when no tier matches (empty list / below tier 0). */
    public static final DistanceTier LOCAL_SENTINEL = new DistanceTier("local", 0, 1.0, 0);

    public enum Anchor {
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
    }

    /** Client-only keys (SPEC §Configuration "Client Config"). Never synced from the server. */
    public static class ClientConfig {
        public boolean showIndicators = true;
        public boolean enableTierHud = true;
        public Anchor hudAnchor = Anchor.TOP_LEFT;
        public int hudOffsetX = 4;
        public int hudOffsetY = 4;
        /**
         * Set once the player first opens the peek loot detail panel (S-082). Until then a chat hint
         * naming the bound key is posted every fifth eligible world join; after the first peek it never
         * returns.
         */
        public boolean peekHintDismissed = false;
        /**
         * Count of eligible world joins (hint pending, key bound) seen so far (S-082) — drives the
         * "once per five sessions" cadence of the peek discovery hint. Frozen once
         * {@link #peekHintDismissed} is set.
         */
        public int peekHintJoins = 0;
    }

    // --- Server Config (SPEC §Configuration "Server Config") ---

    /**
     * Schema version for {@link ProsperityConfigMigrator}. Owned by the migrator (not
     * {@link #clamp()}); raised whenever a migration is appended. A loaded file carrying an
     * older version is migrated up and re-saved on {@link #load}.
     */
    public int configVersion = ProsperityConfigMigrator.CURRENT_VERSION;
    public boolean enableInstancedLoot = true;
    public boolean enableVisualIndicators = true;
    public int indicatorRenderDistance = 48;
    public int indicatorXrayDistance = 8;
    public boolean enableDistanceScaling = true;
    public List<DistanceTier> distanceTiers = defaultDistanceTiers();
    public List<StructureOverride> structureOverrides = defaultStructureOverrides();
    public List<String> lootTableBlacklist = new ArrayList<>();
    public boolean enableLootInjection = true;
    public boolean enableStructureCompletionBonus = true;
    public boolean enableLootNotifications = true;
    public boolean enableLootRefresh = false;
    public int lootRefreshDays = 7;
    public boolean randomizeLootOnRefresh = false;
    /** Absent-player eviction (issue #43): reclaim per-player container entries of long-gone players. */
    public boolean evictAbsentPlayerData = false;
    public int absentPlayerEvictionDays = 60;
    public boolean enableContainerProtection = false;
    public float protectionBreakMultiplier = 4.0f;
    public boolean protectionUnbreakable = false;
    /**
     * Party loot mode (issue #53): when enabled, players on the same vanilla scoreboard team share a
     * single loot instance per container (one inventory, one generation, one refresh cooldown) instead
     * of each getting their own. Teamless players still get individual instances. Off by default.
     */
    public boolean partyLootMode = false;
    /**
     * Grace window, in real minutes, during which a player who just left a team keeps generating
     * <em>new</em> shared instances against their former team's key (issue #53). {@code 0} (the default)
     * disables the grace window; only meaningful while {@link #partyLootMode} is on.
     */
    public int teamLeaveGraceMinutes = 0;
    public boolean enableMobLootScaling = true;
    public boolean enableFishingLootScaling = true;
    public boolean enableTrialChamberScaling = true;
    public boolean endAlwaysMaxTier = true;
    /** Loot index (S-025, SPEC §11): loot-table id &rarr; structure id overrides for unmapped tables. */
    public Map<String, String> lootTableStructures = new LinkedHashMap<>();

    // --- Client Config ---

    public ClientConfig client = new ClientConfig();

    /**
     * Cached matcher derived from {@link #lootTableBlacklist}, rebuilt by {@link #clamp()} on every
     * load/reload. Transient so it is never serialized; pre-initialized to the empty matcher so it is
     * non-null before {@code clamp()} first runs.
     */
    private transient LootBlacklist blacklist = LootBlacklist.of(null);

    /** The blacklist matcher for the current {@link #lootTableBlacklist} (SPEC §7). Never null. */
    public LootBlacklist blacklist() {
        return blacklist;
    }

    /** Default distance tiers, matching SPEC §3 (names) and §Configuration (boundaries). */
    public static List<DistanceTier> defaultDistanceTiers() {
        List<DistanceTier> tiers = new ArrayList<>();
        tiers.add(new DistanceTier("local", 0, 1.0, 0));
        tiers.add(new DistanceTier("frontier", 1000, 1.5, 1));
        tiers.add(new DistanceTier("wilderness", 3000, 2.0, 2));
        tiers.add(new DistanceTier("outlands", 6000, 2.75, 3));
        tiers.add(new DistanceTier("depths", 10000, 3.5, 4));
        return tiers;
    }

    /** Default structure overrides, matching SPEC §Configuration "Default Structure Overrides". */
    public static List<StructureOverride> defaultStructureOverrides() {
        List<StructureOverride> overrides = new ArrayList<>();
        overrides.add(new StructureOverride("minecraft:monument", "fixed", "wilderness"));
        overrides.add(new StructureOverride("minecraft:stronghold", "minimum", "outlands"));
        overrides.add(new StructureOverride("minecraft:village_plains", "maximum", "frontier"));
        overrides.add(new StructureOverride("minecraft:village_desert", "maximum", "frontier"));
        overrides.add(new StructureOverride("minecraft:village_savanna", "maximum", "frontier"));
        overrides.add(new StructureOverride("minecraft:village_snowy", "maximum", "frontier"));
        overrides.add(new StructureOverride("minecraft:village_taiga", "maximum", "frontier"));
        overrides.add(new StructureOverride("minecraft:ancient_city", "minimum", "outlands"));
        overrides.add(new StructureOverride("minecraft:trail_ruins", "minimum", "frontier"));
        overrides.add(new StructureOverride("minecraft:trial_chambers", "minimum", "wilderness"));
        return overrides;
    }

    /**
     * Resolves the distance tier for a container at {@code distance} blocks from origin.
     * Walks the configured tiers from highest to lowest {@link DistanceTier#minDistance()}
     * and returns the first whose bound is satisfied. An empty list, a null list, or a
     * distance below every tier yields {@link #LOCAL_SENTINEL} (never null).
     */
    public DistanceTier tierFor(double distance) {
        if (distanceTiers == null || distanceTiers.isEmpty()) {
            return LOCAL_SENTINEL;
        }
        DistanceTier best = null;
        for (DistanceTier tier : distanceTiers) {
            if (tier == null) {
                continue;
            }
            if (distance >= tier.minDistance() && (best == null || tier.minDistance() > best.minDistance())) {
                best = tier;
            }
        }
        return best != null ? best : LOCAL_SENTINEL;
    }

    /**
     * Sanitizes ranges and replaces nulls left by a partial/lenient deserialize. Idempotent;
     * safe to call on a fresh default instance.
     */
    public void clamp() {
        indicatorRenderDistance = clampInt("indicatorRenderDistance", indicatorRenderDistance, 0, 512);
        indicatorXrayDistance = clampInt("indicatorXrayDistance", indicatorXrayDistance, 0, 512);
        // Xray reveals containers through walls only inside the render radius, so it can never exceed
        // it — a hand-edited xray > render distance is capped to the render distance (and logged).
        if (indicatorXrayDistance > indicatorRenderDistance) {
            Prosperity.LOGGER.warn(
                    "indicatorXrayDistance must not exceed indicatorRenderDistance ({}), got {}; capped to {}",
                    indicatorRenderDistance, indicatorXrayDistance, indicatorRenderDistance);
            indicatorXrayDistance = indicatorRenderDistance;
        }
        lootRefreshDays = clampInt("lootRefreshDays", lootRefreshDays, 1, Integer.MAX_VALUE);
        absentPlayerEvictionDays = clampInt("absentPlayerEvictionDays", absentPlayerEvictionDays, 1, Integer.MAX_VALUE);
        protectionBreakMultiplier = clampFloat("protectionBreakMultiplier", protectionBreakMultiplier, 1.0f, 100.0f);
        teamLeaveGraceMinutes = clampInt("teamLeaveGraceMinutes", teamLeaveGraceMinutes, 0, Integer.MAX_VALUE);

        if (distanceTiers == null) {
            distanceTiers = defaultDistanceTiers();
        } else {
            distanceTiers = sanitizeDistanceTiers(distanceTiers);
        }
        if (structureOverrides == null) {
            structureOverrides = defaultStructureOverrides();
        } else {
            structureOverrides = sanitizeStructureOverrides(structureOverrides);
        }
        if (lootTableBlacklist == null) {
            lootTableBlacklist = new ArrayList<>();
        }
        if (lootTableStructures == null) {
            lootTableStructures = new LinkedHashMap<>();
        }
        blacklist = LootBlacklist.of(lootTableBlacklist);
        if (client == null) {
            client = new ClientConfig();
        }
        // Gson leaves enum fields null on unknown/missing values.
        if (client.hudAnchor == null) {
            client.hudAnchor = Anchor.TOP_LEFT;
        }
        client.hudOffsetX = Math.clamp(client.hudOffsetX, 0, 10_000);
        client.hudOffsetY = Math.clamp(client.hudOffsetY, 0, 10_000);
        client.peekHintJoins = Math.max(0, client.peekHintJoins);
    }

    /**
     * Clamps an int field into {@code [min, max]}, logging a warning only when it actually corrects
     * an out-of-range value so a player sees exactly what their hand-edited config did (the
     * {@code mc-config} warn-and-clamp standard). An in-range value passes through silently.
     */
    private static int clampInt(String name, int value, int min, int max) {
        int clamped = Math.clamp(value, min, max);
        if (clamped != value) {
            Prosperity.LOGGER.warn("{} must be in [{}, {}], got {}; clamped to {}", name, min, max, value, clamped);
        }
        return clamped;
    }

    /**
     * Float counterpart to {@link #clampInt(String, int, int, int)}: clamps into {@code [min, max]}
     * and warns only on an actual correction.
     */
    private static float clampFloat(String name, float value, float min, float max) {
        float clamped = Math.clamp(value, min, max);
        if (clamped != value) {
            Prosperity.LOGGER.warn("{} must be in [{}, {}], got {}; clamped to {}", name, min, max, value, clamped);
        }
        return clamped;
    }

    /**
     * Heals hand-edited {@link #distanceTiers}: drops null entries and entries with a null/blank
     * name, drops duplicate names (first wins) so {@link StructureOverride#tier()} resolves
     * unambiguously, and clamps a negative {@link DistanceTier#minDistance()} up to {@code 0} so the
     * highest-to-lowest walk in {@link #tierFor(double)} stays ordered. An all-invalid list collapses
     * to empty (a valid "no scaling" config), not to the defaults.
     */
    private static List<DistanceTier> sanitizeDistanceTiers(List<DistanceTier> tiers) {
        List<DistanceTier> cleaned = new ArrayList<>(tiers.size());
        Set<String> seenNames = new HashSet<>();
        for (DistanceTier tier : tiers) {
            if (tier == null || tier.name() == null || tier.name().isBlank()) {
                continue;
            }
            if (!seenNames.add(tier.name())) {
                continue;
            }
            int minDistance = Math.max(0, tier.minDistance());
            cleaned.add(minDistance == tier.minDistance()
                    ? tier
                    : new DistanceTier(tier.name(), minDistance, tier.stackMultiplier(), tier.qualityModifier()));
        }
        return cleaned;
    }

    /**
     * Heals hand-edited {@link #structureOverrides}: drops null entries and any whose structure id,
     * mode, or tier reference is null/blank, or whose mode is not one of {@code fixed}/{@code minimum}/
     * {@code maximum} — an unrecognized override does nothing downstream, so pruning it keeps the
     * in-memory list to only entries that can actually apply.
     */
    private static List<StructureOverride> sanitizeStructureOverrides(List<StructureOverride> overrides) {
        List<StructureOverride> cleaned = new ArrayList<>(overrides.size());
        for (StructureOverride override : overrides) {
            if (override == null
                    || override.structure() == null || override.structure().isBlank()
                    || override.tier() == null || override.tier().isBlank()
                    || !isRecognizedMode(override.mode())) {
                continue;
            }
            cleaned.add(override);
        }
        return cleaned;
    }

    private static boolean isRecognizedMode(String mode) {
        return "fixed".equals(mode) || "minimum".equals(mode) || "maximum".equals(mode);
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    /** Serialization for the config-sync wire form (S-010); {@link #fromJson(String)} reads it back. */
    public String toSyncJson() {
        return SYNC_GSON.toJson(this);
    }

    public static ProsperityConfig fromJson(String json) {
        ProsperityConfig config = GSON.fromJson(json, ProsperityConfig.class);
        if (config == null) {
            config = new ProsperityConfig();
        }
        config.clamp();
        return config;
    }

    public void save() {
        save(configPath());
    }

    void save(Path path) {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(tmp, GSON.toJson(this));
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Prosperity.LOGGER.error("Failed to save config", e);
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException cleanup) {
                Prosperity.LOGGER.warn("Failed to delete orphan config tmp file {}", tmp, cleanup);
            }
        }
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("prosperity.json");
    }

    public static ProsperityConfig load() {
        return load(configPath());
    }

    /** A fresh default config, clamped so every range and derived field (e.g. the blacklist matcher) is valid. */
    private static ProsperityConfig freshDefaults() {
        ProsperityConfig config = new ProsperityConfig();
        config.clamp();
        return config;
    }

    static ProsperityConfig load(Path path) {
        if (Files.exists(path)) {
            try {
                String json = Files.readString(path);
                JsonElement element = json.isBlank() ? null : JsonParser.parseString(json);
                if (element == null || element.isJsonNull() || !element.isJsonObject()) {
                    // Empty or non-object file: rewrite defaults (the content carries no recoverable
                    // state, unlike a syntactically-broken file which we preserve below).
                    Prosperity.LOGGER.warn("Config at {} is empty or not a JSON object; using defaults", path);
                    ProsperityConfig defaults = freshDefaults();
                    defaults.save(path);
                    return defaults;
                }

                JsonObject raw = element.getAsJsonObject();
                boolean migrated = ProsperityConfigMigrator.migrate(raw);

                ProsperityConfig config = GSON.fromJson(raw, ProsperityConfig.class);
                if (config == null) {
                    config = new ProsperityConfig();
                }
                config.clamp();
                if (migrated) {
                    config.save(path);
                }
                return config;
            } catch (Exception e) {
                Prosperity.LOGGER.error("Failed to load config, using defaults (corrupted file preserved at {})", path, e);
                return freshDefaults();
            }
        }
        ProsperityConfig defaults = freshDefaults();
        defaults.save(path);
        return defaults;
    }
}
