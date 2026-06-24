package com.rfizzle.prosperity.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.rfizzle.prosperity.Prosperity;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

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
    }

    // --- Server Config (SPEC §Configuration "Server Config") ---

    public boolean enableInstancedLoot = true;
    public boolean enableVisualIndicators = true;
    public int indicatorRenderDistance = 48;
    public int indicatorXrayDistance = 8;
    public boolean enableDistanceScaling = true;
    public List<DistanceTier> distanceTiers = defaultDistanceTiers();
    public List<StructureOverride> structureOverrides = defaultStructureOverrides();
    public List<String> lootTableBlacklist = new ArrayList<>();
    public boolean enableLootInjection = true;
    public boolean enableLootNotifications = true;
    public boolean enableLootRefresh = false;
    public int lootRefreshDays = 7;
    public boolean enableContainerProtection = false;
    public float protectionBreakMultiplier = 4.0f;
    public boolean enableMobLootScaling = true;
    public boolean endAlwaysMaxTier = true;

    // --- Client Config ---

    public ClientConfig client = new ClientConfig();

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
        indicatorRenderDistance = Math.clamp(indicatorRenderDistance, 0, 512);
        indicatorXrayDistance = Math.clamp(indicatorXrayDistance, 0, 512);
        lootRefreshDays = Math.clamp(lootRefreshDays, 1, Integer.MAX_VALUE);
        protectionBreakMultiplier = Math.clamp(protectionBreakMultiplier, 1.0f, 100.0f);

        if (distanceTiers == null) {
            distanceTiers = defaultDistanceTiers();
        }
        if (structureOverrides == null) {
            structureOverrides = defaultStructureOverrides();
        }
        if (lootTableBlacklist == null) {
            lootTableBlacklist = new ArrayList<>();
        }
        if (client == null) {
            client = new ClientConfig();
        }
        // Gson leaves enum fields null on unknown/missing values.
        if (client.hudAnchor == null) {
            client.hudAnchor = Anchor.TOP_LEFT;
        }
        client.hudOffsetX = Math.clamp(client.hudOffsetX, 0, 10_000);
        client.hudOffsetY = Math.clamp(client.hudOffsetY, 0, 10_000);
    }

    public String toJson() {
        return GSON.toJson(this);
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

    static ProsperityConfig load(Path path) {
        if (Files.exists(path)) {
            try {
                String json = Files.readString(path);
                ProsperityConfig config = GSON.fromJson(json, ProsperityConfig.class);
                if (config == null) {
                    config = new ProsperityConfig();
                }
                config.clamp();
                return config;
            } catch (Exception e) {
                Prosperity.LOGGER.error("Failed to load config, using defaults (corrupted file preserved at {})", path, e);
                return new ProsperityConfig();
            }
        }
        ProsperityConfig defaults = new ProsperityConfig();
        defaults.save(path);
        return defaults;
    }
}
