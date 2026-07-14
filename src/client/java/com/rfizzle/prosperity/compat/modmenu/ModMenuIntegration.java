package com.rfizzle.prosperity.compat.modmenu;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.client.network.ClientProsperityData;
import com.rfizzle.prosperity.config.DistanceTier;
import com.rfizzle.prosperity.config.ProsperityConfig;
import com.rfizzle.prosperity.config.StructureOverride;
import com.rfizzle.prosperity.network.ProsperityNetworking;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.api.Requirement;
import me.shedaniel.clothconfig2.impl.builders.FieldBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;

/**
 * Mod Menu entry point (S-028): a Cloth Config screen exposing every {@link ProsperityConfig} key,
 * grouped into categories with per-entry tooltips. Both Mod Menu and Cloth Config are
 * {@code modCompileOnly} — this class lives in the client source set and is referenced only from the
 * {@code modmenu} entrypoint, so the mod runs unchanged when either is absent (the screen is simply
 * never built).
 *
 * <p>Edits are applied to a deep working copy of the live config (so a cancelled screen never
 * half-applies); saving writes that copy to {@code prosperity.json}, reloads the live instance
 * (re-running {@link ProsperityConfig#clamp()} to sanitize ranges and dropped list rows), and
 * re-syncs to connected players when an integrated server is running.
 *
 * <p>On a remote server the server-authoritative settings (every top-level {@link ProsperityConfig}
 * field) are the operator's: their entries render read-only, seeded from the server's synced values
 * ({@link ClientProsperityData#config()}) so the screen reflects what is actually in effect, and the
 * save persists only the player-owned {@link ProsperityConfig#client} block. The
 * remote-vs-editable decision and the save merge are the pure {@link RemoteConfigGate}. Singleplayer,
 * an integrated LAN host, and the main menu leave every entry editable.
 *
 * <p>The two structured-list keys ({@code distanceTiers}, {@code structureOverrides}) are edited as
 * string-list rows: each row is a CSV form parsed back on save, with malformed rows dropped and
 * logged. Cloth 15.0.x ships no nested-list builder and the underlying records are immutable, so the
 * string-list keeps full add/remove/edit behind a battle-tested widget.
 */
public class ModMenuIntegration implements ModMenuApi {

    /** Enable requirement that never holds — a widget carrying it renders permanently read-only. */
    private static final Requirement READ_ONLY = () -> false;

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            // Deep copy via JSON round-trip so save consumers never touch the live config until save().
            ProsperityConfig working = ProsperityConfig.fromJson(Prosperity.getConfig().toJson());

            Minecraft mc = Minecraft.getInstance();
            boolean locked = RemoteConfigGate.serverSettingsLocked(
                    mc.level != null, mc.hasSingleplayerServer());
            // Server-authoritative widgets show the server's synced view when locked (what is really in
            // effect), the local working copy otherwise. The client block is always the working copy.
            ProsperityConfig serverView = locked ? ClientProsperityData.config() : working;

            ConfigBuilder builder = ConfigBuilder.create()
                    .setParentScreen(parent)
                    .setTitle(Component.translatable("config.prosperity.title"));
            ConfigEntryBuilder entry = builder.entryBuilder();

            // --- General ---
            ConfigCategory general = builder.getOrCreateCategory(
                    Component.translatable("config.prosperity.category.general"));
            lockNote(general, entry, locked);
            addServer(general, locked, entry.startBooleanToggle(
                            label("enable_instanced_loot"), serverView.enableInstancedLoot)
                    .setDefaultValue(true)
                    .setTooltip(tooltip("enable_instanced_loot"))
                    .setSaveConsumer(v -> working.enableInstancedLoot = v));

            // --- Indicators ---
            ConfigCategory indicators = builder.getOrCreateCategory(
                    Component.translatable("config.prosperity.category.indicators"));
            lockNote(indicators, entry, locked);
            addServer(indicators, locked, entry.startBooleanToggle(
                            label("enable_visual_indicators"), serverView.enableVisualIndicators)
                    .setDefaultValue(true)
                    .setTooltip(tooltip("enable_visual_indicators"))
                    .setSaveConsumer(v -> working.enableVisualIndicators = v));
            addServer(indicators, locked, entry.startIntSlider(
                            label("indicator_render_distance"), serverView.indicatorRenderDistance, 0, 512)
                    .setDefaultValue(48)
                    .setTooltip(tooltip("indicator_render_distance"))
                    .setSaveConsumer(v -> working.indicatorRenderDistance = v));
            addServer(indicators, locked, entry.startIntSlider(
                            label("indicator_xray_distance"), serverView.indicatorXrayDistance, 0, 512)
                    .setDefaultValue(8)
                    .setTooltip(tooltip("indicator_xray_distance"))
                    .setSaveConsumer(v -> working.indicatorXrayDistance = v));

            // --- Scaling ---
            ConfigCategory scaling = builder.getOrCreateCategory(
                    Component.translatable("config.prosperity.category.scaling"));
            lockNote(scaling, entry, locked);
            addServer(scaling, locked, entry.startBooleanToggle(
                            label("enable_distance_scaling"), serverView.enableDistanceScaling)
                    .setDefaultValue(true)
                    .setTooltip(tooltip("enable_distance_scaling"))
                    .setSaveConsumer(v -> working.enableDistanceScaling = v));
            addServer(scaling, locked, entry.startBooleanToggle(
                            label("end_always_max_tier"), serverView.endAlwaysMaxTier)
                    .setDefaultValue(true)
                    .setTooltip(tooltip("end_always_max_tier"))
                    .setSaveConsumer(v -> working.endAlwaysMaxTier = v));
            addServer(scaling, locked, entry.startStrList(
                            label("distance_tiers"), encodeTiers(serverView.distanceTiers))
                    .setDefaultValue(encodeTiers(ProsperityConfig.defaultDistanceTiers()))
                    .setTooltip(tooltip("distance_tiers"))
                    .setSaveConsumer(rows -> working.distanceTiers = parseTiers(rows)));
            addServer(scaling, locked, entry.startStrList(
                            label("structure_overrides"), encodeOverrides(serverView.structureOverrides))
                    .setDefaultValue(encodeOverrides(ProsperityConfig.defaultStructureOverrides()))
                    .setTooltip(tooltip("structure_overrides"))
                    .setSaveConsumer(rows -> working.structureOverrides = parseOverrides(rows)));

            // --- Content ---
            ConfigCategory content = builder.getOrCreateCategory(
                    Component.translatable("config.prosperity.category.content"));
            lockNote(content, entry, locked);
            addServer(content, locked, entry.startBooleanToggle(
                            label("enable_loot_injection"), serverView.enableLootInjection)
                    .setDefaultValue(true)
                    .setTooltip(tooltip("enable_loot_injection"))
                    .setSaveConsumer(v -> working.enableLootInjection = v));
            addServer(content, locked, entry.startBooleanToggle(
                            label("enable_structure_completion_bonus"), serverView.enableStructureCompletionBonus)
                    .setDefaultValue(true)
                    .setTooltip(tooltip("enable_structure_completion_bonus"))
                    .setSaveConsumer(v -> working.enableStructureCompletionBonus = v));
            addServer(content, locked, entry.startStrList(
                            label("loot_table_blacklist"), new ArrayList<>(serverView.lootTableBlacklist))
                    .setDefaultValue(new ArrayList<>())
                    .setTooltip(tooltip("loot_table_blacklist"))
                    .setSaveConsumer(rows -> working.lootTableBlacklist = new ArrayList<>(rows)));

            // --- Feedback ---
            ConfigCategory feedback = builder.getOrCreateCategory(
                    Component.translatable("config.prosperity.category.feedback"));
            lockNote(feedback, entry, locked);
            addServer(feedback, locked, entry.startBooleanToggle(
                            label("enable_loot_notifications"), serverView.enableLootNotifications)
                    .setDefaultValue(true)
                    .setTooltip(tooltip("enable_loot_notifications"))
                    .setSaveConsumer(v -> working.enableLootNotifications = v));
            addServer(feedback, locked, entry.startBooleanToggle(
                            label("enable_loot_refresh"), serverView.enableLootRefresh)
                    .setDefaultValue(false)
                    .setTooltip(tooltip("enable_loot_refresh"))
                    .setSaveConsumer(v -> working.enableLootRefresh = v));
            addServer(feedback, locked, entry.startIntField(
                            label("loot_refresh_days"), serverView.lootRefreshDays)
                    .setDefaultValue(7)
                    .setMin(1)
                    .setTooltip(tooltip("loot_refresh_days"))
                    .setSaveConsumer(v -> working.lootRefreshDays = v));
            addServer(feedback, locked, entry.startBooleanToggle(
                            label("randomize_loot_on_refresh"), serverView.randomizeLootOnRefresh)
                    .setDefaultValue(false)
                    .setTooltip(tooltip("randomize_loot_on_refresh"))
                    .setSaveConsumer(v -> working.randomizeLootOnRefresh = v));
            addServer(feedback, locked, entry.startBooleanToggle(
                            label("evict_absent_player_data"), serverView.evictAbsentPlayerData)
                    .setDefaultValue(false)
                    .setTooltip(tooltip("evict_absent_player_data"))
                    .setSaveConsumer(v -> working.evictAbsentPlayerData = v));
            addServer(feedback, locked, entry.startIntField(
                            label("absent_player_eviction_days"), serverView.absentPlayerEvictionDays)
                    .setDefaultValue(60)
                    .setMin(1)
                    .setTooltip(tooltip("absent_player_eviction_days"))
                    .setSaveConsumer(v -> working.absentPlayerEvictionDays = v));

            // --- Extended ---
            ConfigCategory extended = builder.getOrCreateCategory(
                    Component.translatable("config.prosperity.category.extended"));
            lockNote(extended, entry, locked);
            addServer(extended, locked, entry.startBooleanToggle(
                            label("enable_container_protection"), serverView.enableContainerProtection)
                    .setDefaultValue(false)
                    .setTooltip(tooltip("enable_container_protection"))
                    .setSaveConsumer(v -> working.enableContainerProtection = v));
            addServer(extended, locked, entry.startFloatField(
                            label("protection_break_multiplier"), serverView.protectionBreakMultiplier)
                    .setDefaultValue(4.0f)
                    .setMin(1.0f).setMax(100.0f)
                    .setTooltip(tooltip("protection_break_multiplier"))
                    .setSaveConsumer(v -> working.protectionBreakMultiplier = v));
            addServer(extended, locked, entry.startBooleanToggle(
                            label("protection_unbreakable"), serverView.protectionUnbreakable)
                    .setDefaultValue(false)
                    .setTooltip(tooltip("protection_unbreakable"))
                    .setSaveConsumer(v -> working.protectionUnbreakable = v));
            addServer(extended, locked, entry.startBooleanToggle(
                            label("enable_mob_loot_scaling"), serverView.enableMobLootScaling)
                    .setDefaultValue(true)
                    .setTooltip(tooltip("enable_mob_loot_scaling"))
                    .setSaveConsumer(v -> working.enableMobLootScaling = v));
            addServer(extended, locked, entry.startBooleanToggle(
                            label("enable_fishing_loot_scaling"), serverView.enableFishingLootScaling)
                    .setDefaultValue(true)
                    .setTooltip(tooltip("enable_fishing_loot_scaling"))
                    .setSaveConsumer(v -> working.enableFishingLootScaling = v));
            addServer(extended, locked, entry.startBooleanToggle(
                            label("enable_trial_chamber_scaling"), serverView.enableTrialChamberScaling)
                    .setDefaultValue(true)
                    .setTooltip(tooltip("enable_trial_chamber_scaling"))
                    .setSaveConsumer(v -> working.enableTrialChamberScaling = v));
            addServer(extended, locked, entry.startBooleanToggle(
                            label("party_loot_mode"), serverView.partyLootMode)
                    .setDefaultValue(false)
                    .setTooltip(tooltip("party_loot_mode"))
                    .setSaveConsumer(v -> working.partyLootMode = v));
            addServer(extended, locked, entry.startIntField(
                            label("team_leave_grace_minutes"), serverView.teamLeaveGraceMinutes)
                    .setDefaultValue(0)
                    .setMin(0)
                    .setTooltip(tooltip("team_leave_grace_minutes"))
                    .setSaveConsumer(v -> working.teamLeaveGraceMinutes = v));

            // --- HUD (client-only; always editable, even on a remote server) ---
            ConfigCategory hud = builder.getOrCreateCategory(
                    Component.translatable("config.prosperity.category.hud"));
            hud.addEntry(entry.startBooleanToggle(
                            label("show_indicators"), working.client.showIndicators)
                    .setDefaultValue(true)
                    .setTooltip(tooltip("show_indicators"))
                    .setSaveConsumer(v -> working.client.showIndicators = v)
                    .build());
            hud.addEntry(entry.startBooleanToggle(
                            label("enable_tier_hud"), working.client.enableTierHud)
                    .setDefaultValue(true)
                    .setTooltip(tooltip("enable_tier_hud"))
                    .setSaveConsumer(v -> working.client.enableTierHud = v)
                    .build());
            hud.addEntry(entry.startEnumSelector(
                            label("hud_anchor"), ProsperityConfig.Anchor.class, working.client.hudAnchor)
                    .setDefaultValue(ProsperityConfig.Anchor.TOP_LEFT)
                    .setEnumNameProvider(a -> Component.translatable(
                            "config.prosperity.hud_anchor." + a.name().toLowerCase()))
                    .setTooltip(tooltip("hud_anchor"))
                    .setSaveConsumer(v -> working.client.hudAnchor = v)
                    .build());
            hud.addEntry(entry.startIntField(
                            label("hud_offset_x"), working.client.hudOffsetX)
                    .setDefaultValue(4)
                    .setMin(0).setMax(10_000)
                    .setTooltip(tooltip("hud_offset_x"))
                    .setSaveConsumer(v -> working.client.hudOffsetX = v)
                    .build());
            hud.addEntry(entry.startIntField(
                            label("hud_offset_y"), working.client.hudOffsetY)
                    .setDefaultValue(4)
                    .setMin(0).setMax(10_000)
                    .setTooltip(tooltip("hud_offset_y"))
                    .setSaveConsumer(v -> working.client.hudOffsetY = v)
                    .build());

            builder.setSavingRunnable(() -> {
                // When locked, persist only the client block; server fields stay the operator's.
                RemoteConfigGate.persistedConfig(locked, Prosperity.getConfig(), working).save();
                Prosperity.reloadConfig();
                // Re-sync to clients when this client hosts the world; a remote server has none.
                MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
                if (server != null) {
                    server.execute(() -> ProsperityNetworking.syncConfigToAll(server));
                }
            });

            return builder.build();
        };
    }

    /**
     * Adds a server-authoritative entry to {@code category}, marking it read-only when {@code locked}.
     * The save consumer still runs against the working copy on save, but {@link RemoteConfigGate}
     * discards the working copy's server fields when locked, so a read-only widget can never write its
     * (server-sourced) value over the local file.
     */
    private static void addServer(ConfigCategory category, boolean locked, FieldBuilder<?, ?, ?> builder) {
        if (locked) {
            builder.setRequirement(READ_ONLY);
        }
        category.addEntry(builder.build());
    }

    /** Prepends the "settings controlled by the server" note to a server category when {@code locked}. */
    private static void lockNote(ConfigCategory category, ConfigEntryBuilder entry, boolean locked) {
        if (locked) {
            category.addEntry(entry.startTextDescription(
                    Component.translatable("config.prosperity.server_locked_note")).build());
        }
    }

    private static Component label(String key) {
        return Component.translatable("config.prosperity." + key);
    }

    private static Component[] tooltip(String key) {
        return new Component[]{Component.translatable("config.prosperity." + key + ".tooltip")};
    }

    // --- Structured-list row codecs (CSV per row; malformed rows dropped + logged on save) ---

    private static List<String> encodeTiers(List<DistanceTier> tiers) {
        List<String> rows = new ArrayList<>();
        for (DistanceTier t : tiers) {
            rows.add(t.name() + ", " + t.minDistance() + ", " + t.stackMultiplier() + ", " + t.qualityModifier());
        }
        return rows;
    }

    private static List<DistanceTier> parseTiers(List<String> rows) {
        List<DistanceTier> tiers = new ArrayList<>();
        for (String row : rows) {
            if (row.isBlank()) {
                continue;
            }
            String[] parts = row.split(",");
            if (parts.length != 4) {
                Prosperity.LOGGER.warn("Dropping malformed distance tier row (expected 4 fields): {}", row);
                continue;
            }
            try {
                tiers.add(new DistanceTier(
                        parts[0].trim(),
                        Integer.parseInt(parts[1].trim()),
                        Double.parseDouble(parts[2].trim()),
                        Integer.parseInt(parts[3].trim())));
            } catch (NumberFormatException e) {
                Prosperity.LOGGER.warn("Dropping distance tier row with unparseable number: {}", row);
            }
        }
        return tiers;
    }

    private static List<String> encodeOverrides(List<StructureOverride> overrides) {
        List<String> rows = new ArrayList<>();
        for (StructureOverride o : overrides) {
            rows.add(o.structure() + ", " + o.mode() + ", " + o.tier());
        }
        return rows;
    }

    private static List<StructureOverride> parseOverrides(List<String> rows) {
        List<StructureOverride> overrides = new ArrayList<>();
        for (String row : rows) {
            if (row.isBlank()) {
                continue;
            }
            String[] parts = row.split(",");
            if (parts.length != 3) {
                Prosperity.LOGGER.warn("Dropping malformed structure override row (expected 3 fields): {}", row);
                continue;
            }
            overrides.add(new StructureOverride(parts[0].trim(), parts[1].trim(), parts[2].trim()));
        }
        return overrides;
    }
}
