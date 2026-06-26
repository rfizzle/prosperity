package com.rfizzle.prosperity.loot.index;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.config.ProsperityConfig;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/**
 * Maps a loot table onto the structure it belongs to for the loot index (S-025, SPEC §11).
 *
 * <p>Most vanilla structure&rarr;loot links are hardcoded in Java (legacy structures, plus the
 * dungeon worldgen feature), not data-discoverable, so the vanilla mapping is a hardcoded table
 * keyed identically to {@link StructureIcons}. Resolution order is: a {@code lootTableStructures}
 * config override, then the hardcoded vanilla map, then namespaced prefix rules (village /
 * trial-chamber sub-tables), and finally the {@link #OTHER} bucket. No structure name is ever
 * fabricated from a path — unmapped tables stay in {@code OTHER} until a config entry maps them.
 */
public final class LootTableStructures {

    /** Bucket for loot tables with no known structure (modded / unmapped). Rendered with a chest icon. */
    public static final ResourceLocation OTHER = Prosperity.id("other");

    /**
     * Hardcoded vanilla loot-table &rarr; structure map (1.21.1). Village and trial-chamber
     * sub-tables are covered by prefix rules in {@link #structureFor} rather than enumerated here.
     */
    private static final Map<ResourceLocation, ResourceLocation> VANILLA = buildVanillaMap();

    private LootTableStructures() {
    }

    /**
     * The structure id for {@code table}: a config override if present, else the hardcoded vanilla
     * map, else a village/trial-chamber prefix rule, else {@link #OTHER}. Never null.
     */
    public static ResourceLocation structureFor(ResourceLocation table, ProsperityConfig cfg) {
        Map<String, String> overrides = cfg.lootTableStructures;
        if (overrides != null) {
            String override = overrides.get(table.toString());
            if (override != null) {
                ResourceLocation parsed = ResourceLocation.tryParse(override);
                if (parsed != null) {
                    return parsed;
                }
            }
        }
        ResourceLocation mapped = VANILLA.get(table);
        if (mapped != null) {
            return mapped;
        }
        if (table.getNamespace().equals("minecraft")) {
            String path = table.getPath();
            if (path.startsWith("chests/village/")) {
                return StructureIcons.VILLAGE;
            }
            if (path.startsWith("chests/trial_chambers/")) {
                return StructureIcons.TRIAL_CHAMBERS;
            }
        }
        return OTHER;
    }

    private static Map<ResourceLocation, ResourceLocation> buildVanillaMap() {
        Map<ResourceLocation, ResourceLocation> map = new HashMap<>();
        put(map, "chests/simple_dungeon", StructureIcons.DUNGEON);
        put(map, "chests/abandoned_mineshaft", StructureIcons.MINESHAFT);
        put(map, "chests/stronghold_corridor", StructureIcons.STRONGHOLD);
        put(map, "chests/stronghold_crossing", StructureIcons.STRONGHOLD);
        put(map, "chests/stronghold_library", StructureIcons.STRONGHOLD);
        put(map, "chests/desert_pyramid", StructureIcons.DESERT_PYRAMID);
        put(map, "chests/jungle_temple", StructureIcons.JUNGLE_PYRAMID);
        put(map, "chests/jungle_temple_dispenser", StructureIcons.JUNGLE_PYRAMID);
        put(map, "chests/woodland_mansion", StructureIcons.MANSION);
        put(map, "chests/end_city_treasure", StructureIcons.END_CITY);
        put(map, "chests/buried_treasure", StructureIcons.BURIED_TREASURE);
        put(map, "chests/shipwreck_map", StructureIcons.SHIPWRECK);
        put(map, "chests/shipwreck_supply", StructureIcons.SHIPWRECK);
        put(map, "chests/shipwreck_treasure", StructureIcons.SHIPWRECK);
        put(map, "chests/ruined_portal", StructureIcons.RUINED_PORTAL);
        put(map, "chests/bastion_treasure", StructureIcons.BASTION_REMNANT);
        put(map, "chests/bastion_other", StructureIcons.BASTION_REMNANT);
        put(map, "chests/bastion_bridge", StructureIcons.BASTION_REMNANT);
        put(map, "chests/bastion_hoglin_stable", StructureIcons.BASTION_REMNANT);
        put(map, "chests/nether_bridge", StructureIcons.FORTRESS);
        put(map, "chests/ancient_city", StructureIcons.ANCIENT_CITY);
        put(map, "chests/ancient_city_ice_box", StructureIcons.ANCIENT_CITY);
        put(map, "chests/pillager_outpost", StructureIcons.PILLAGER_OUTPOST);
        put(map, "chests/igloo_chest", StructureIcons.IGLOO);
        put(map, "chests/underwater_ruin_small", StructureIcons.OCEAN_RUIN);
        put(map, "chests/underwater_ruin_big", StructureIcons.OCEAN_RUIN);
        return Map.copyOf(map);
    }

    private static void put(Map<ResourceLocation, ResourceLocation> map, String table, ResourceLocation structure) {
        map.put(ResourceLocation.withDefaultNamespace(table), structure);
    }
}
