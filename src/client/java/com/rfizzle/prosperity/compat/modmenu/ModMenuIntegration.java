package com.rfizzle.prosperity.compat.modmenu;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.config.DistanceTier;
import com.rfizzle.prosperity.config.ProsperityConfig;
import com.rfizzle.prosperity.config.StructureOverride;
import com.rfizzle.prosperity.network.ProsperityNetworking;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
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
 * re-syncs to connected players when an integrated server is running. On a remote server the screen
 * edits the local file harmlessly without changing server behavior.
 *
 * <p>The two structured-list keys ({@code distanceTiers}, {@code structureOverrides}) are edited as
 * string-list rows: each row is a CSV form parsed back on save, with malformed rows dropped and
 * logged. Cloth 15.0.x ships no nested-list builder and the underlying records are immutable, so the
 * string-list keeps full add/remove/edit behind a battle-tested widget.
 */
public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            // Deep copy via JSON round-trip so save consumers never touch the live config until save().
            ProsperityConfig working = ProsperityConfig.fromJson(Prosperity.getConfig().toJson());

            ConfigBuilder builder = ConfigBuilder.create()
                    .setParentScreen(parent)
                    .setTitle(Component.translatable("config.prosperity.title"));
            ConfigEntryBuilder entry = builder.entryBuilder();

            // --- General ---
            ConfigCategory general = builder.getOrCreateCategory(
                    Component.translatable("config.prosperity.category.general"));
            general.addEntry(entry.startBooleanToggle(
                            label("enable_instanced_loot"), working.enableInstancedLoot)
                    .setDefaultValue(true)
                    .setTooltip(tooltip("enable_instanced_loot"))
                    .setSaveConsumer(v -> working.enableInstancedLoot = v)
                    .build());

            // --- Indicators ---
            ConfigCategory indicators = builder.getOrCreateCategory(
                    Component.translatable("config.prosperity.category.indicators"));
            indicators.addEntry(entry.startBooleanToggle(
                            label("enable_visual_indicators"), working.enableVisualIndicators)
                    .setDefaultValue(true)
                    .setTooltip(tooltip("enable_visual_indicators"))
                    .setSaveConsumer(v -> working.enableVisualIndicators = v)
                    .build());
            indicators.addEntry(entry.startIntSlider(
                            label("indicator_render_distance"), working.indicatorRenderDistance, 0, 512)
                    .setDefaultValue(48)
                    .setTooltip(tooltip("indicator_render_distance"))
                    .setSaveConsumer(v -> working.indicatorRenderDistance = v)
                    .build());
            indicators.addEntry(entry.startIntSlider(
                            label("indicator_xray_distance"), working.indicatorXrayDistance, 0, 512)
                    .setDefaultValue(8)
                    .setTooltip(tooltip("indicator_xray_distance"))
                    .setSaveConsumer(v -> working.indicatorXrayDistance = v)
                    .build());

            // --- Scaling ---
            ConfigCategory scaling = builder.getOrCreateCategory(
                    Component.translatable("config.prosperity.category.scaling"));
            scaling.addEntry(entry.startBooleanToggle(
                            label("enable_distance_scaling"), working.enableDistanceScaling)
                    .setDefaultValue(true)
                    .setTooltip(tooltip("enable_distance_scaling"))
                    .setSaveConsumer(v -> working.enableDistanceScaling = v)
                    .build());
            scaling.addEntry(entry.startBooleanToggle(
                            label("end_always_max_tier"), working.endAlwaysMaxTier)
                    .setDefaultValue(true)
                    .setTooltip(tooltip("end_always_max_tier"))
                    .setSaveConsumer(v -> working.endAlwaysMaxTier = v)
                    .build());
            scaling.addEntry(entry.startStrList(
                            label("distance_tiers"), encodeTiers(working.distanceTiers))
                    .setDefaultValue(encodeTiers(ProsperityConfig.defaultDistanceTiers()))
                    .setTooltip(tooltip("distance_tiers"))
                    .setSaveConsumer(rows -> working.distanceTiers = parseTiers(rows))
                    .build());
            scaling.addEntry(entry.startStrList(
                            label("structure_overrides"), encodeOverrides(working.structureOverrides))
                    .setDefaultValue(encodeOverrides(ProsperityConfig.defaultStructureOverrides()))
                    .setTooltip(tooltip("structure_overrides"))
                    .setSaveConsumer(rows -> working.structureOverrides = parseOverrides(rows))
                    .build());

            // --- Content ---
            ConfigCategory content = builder.getOrCreateCategory(
                    Component.translatable("config.prosperity.category.content"));
            content.addEntry(entry.startBooleanToggle(
                            label("enable_loot_injection"), working.enableLootInjection)
                    .setDefaultValue(true)
                    .setTooltip(tooltip("enable_loot_injection"))
                    .setSaveConsumer(v -> working.enableLootInjection = v)
                    .build());
            content.addEntry(entry.startBooleanToggle(
                            label("enable_structure_completion_bonus"), working.enableStructureCompletionBonus)
                    .setDefaultValue(true)
                    .setTooltip(tooltip("enable_structure_completion_bonus"))
                    .setSaveConsumer(v -> working.enableStructureCompletionBonus = v)
                    .build());
            content.addEntry(entry.startStrList(
                            label("loot_table_blacklist"), new ArrayList<>(working.lootTableBlacklist))
                    .setDefaultValue(new ArrayList<>())
                    .setTooltip(tooltip("loot_table_blacklist"))
                    .setSaveConsumer(rows -> working.lootTableBlacklist = new ArrayList<>(rows))
                    .build());

            // --- Feedback ---
            ConfigCategory feedback = builder.getOrCreateCategory(
                    Component.translatable("config.prosperity.category.feedback"));
            feedback.addEntry(entry.startBooleanToggle(
                            label("enable_loot_notifications"), working.enableLootNotifications)
                    .setDefaultValue(true)
                    .setTooltip(tooltip("enable_loot_notifications"))
                    .setSaveConsumer(v -> working.enableLootNotifications = v)
                    .build());
            feedback.addEntry(entry.startBooleanToggle(
                            label("enable_loot_refresh"), working.enableLootRefresh)
                    .setDefaultValue(false)
                    .setTooltip(tooltip("enable_loot_refresh"))
                    .setSaveConsumer(v -> working.enableLootRefresh = v)
                    .build());
            feedback.addEntry(entry.startIntField(
                            label("loot_refresh_days"), working.lootRefreshDays)
                    .setDefaultValue(7)
                    .setMin(1)
                    .setTooltip(tooltip("loot_refresh_days"))
                    .setSaveConsumer(v -> working.lootRefreshDays = v)
                    .build());
            feedback.addEntry(entry.startBooleanToggle(
                            label("randomize_loot_on_refresh"), working.randomizeLootOnRefresh)
                    .setDefaultValue(false)
                    .setTooltip(tooltip("randomize_loot_on_refresh"))
                    .setSaveConsumer(v -> working.randomizeLootOnRefresh = v)
                    .build());
            feedback.addEntry(entry.startBooleanToggle(
                            label("evict_absent_player_data"), working.evictAbsentPlayerData)
                    .setDefaultValue(false)
                    .setTooltip(tooltip("evict_absent_player_data"))
                    .setSaveConsumer(v -> working.evictAbsentPlayerData = v)
                    .build());
            feedback.addEntry(entry.startIntField(
                            label("absent_player_eviction_days"), working.absentPlayerEvictionDays)
                    .setDefaultValue(60)
                    .setMin(1)
                    .setTooltip(tooltip("absent_player_eviction_days"))
                    .setSaveConsumer(v -> working.absentPlayerEvictionDays = v)
                    .build());

            // --- Extended ---
            ConfigCategory extended = builder.getOrCreateCategory(
                    Component.translatable("config.prosperity.category.extended"));
            extended.addEntry(entry.startBooleanToggle(
                            label("enable_container_protection"), working.enableContainerProtection)
                    .setDefaultValue(false)
                    .setTooltip(tooltip("enable_container_protection"))
                    .setSaveConsumer(v -> working.enableContainerProtection = v)
                    .build());
            extended.addEntry(entry.startFloatField(
                            label("protection_break_multiplier"), working.protectionBreakMultiplier)
                    .setDefaultValue(4.0f)
                    .setMin(1.0f).setMax(100.0f)
                    .setTooltip(tooltip("protection_break_multiplier"))
                    .setSaveConsumer(v -> working.protectionBreakMultiplier = v)
                    .build());
            extended.addEntry(entry.startBooleanToggle(
                            label("protection_unbreakable"), working.protectionUnbreakable)
                    .setDefaultValue(false)
                    .setTooltip(tooltip("protection_unbreakable"))
                    .setSaveConsumer(v -> working.protectionUnbreakable = v)
                    .build());
            extended.addEntry(entry.startBooleanToggle(
                            label("enable_mob_loot_scaling"), working.enableMobLootScaling)
                    .setDefaultValue(true)
                    .setTooltip(tooltip("enable_mob_loot_scaling"))
                    .setSaveConsumer(v -> working.enableMobLootScaling = v)
                    .build());
            extended.addEntry(entry.startBooleanToggle(
                            label("enable_fishing_loot_scaling"), working.enableFishingLootScaling)
                    .setDefaultValue(true)
                    .setTooltip(tooltip("enable_fishing_loot_scaling"))
                    .setSaveConsumer(v -> working.enableFishingLootScaling = v)
                    .build());
            extended.addEntry(entry.startBooleanToggle(
                            label("enable_trial_chamber_scaling"), working.enableTrialChamberScaling)
                    .setDefaultValue(true)
                    .setTooltip(tooltip("enable_trial_chamber_scaling"))
                    .setSaveConsumer(v -> working.enableTrialChamberScaling = v)
                    .build());
            extended.addEntry(entry.startBooleanToggle(
                            label("party_loot_mode"), working.partyLootMode)
                    .setDefaultValue(false)
                    .setTooltip(tooltip("party_loot_mode"))
                    .setSaveConsumer(v -> working.partyLootMode = v)
                    .build());
            extended.addEntry(entry.startIntField(
                            label("team_leave_grace_minutes"), working.teamLeaveGraceMinutes)
                    .setDefaultValue(0)
                    .setMin(0)
                    .setTooltip(tooltip("team_leave_grace_minutes"))
                    .setSaveConsumer(v -> working.teamLeaveGraceMinutes = v)
                    .build());

            // --- HUD (client-only) ---
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
                working.save();
                Prosperity.reloadConfig();
                // Re-sync to clients when this client hosts the world; a remote server is unaffected.
                MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
                if (server != null) {
                    server.execute(() -> ProsperityNetworking.syncConfigToAll(server));
                }
            });

            return builder.build();
        };
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
