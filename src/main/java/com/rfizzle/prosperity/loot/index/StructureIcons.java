package com.rfizzle.prosperity.loot.index;

import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Representative icon item per structure for the loot index (S-025, SPEC §11). Owns the canonical
 * structure-id vocabulary the index buckets loot tables into ({@link LootTableStructures} maps loot
 * tables onto these ids). Any structure id not in the map — modded structures, or the synthetic
 * {@link LootTableStructures#OTHER} bucket — falls back to {@link Items#CHEST}.
 */
public final class StructureIcons {

    // Canonical structure ids. Vanilla structure registry keys where one exists; a descriptive
    // minecraft-namespaced id otherwise (dungeons are a worldgen feature, not a structure).
    public static final ResourceLocation DUNGEON = mc("dungeon");
    public static final ResourceLocation MINESHAFT = mc("mineshaft");
    public static final ResourceLocation STRONGHOLD = mc("stronghold");
    public static final ResourceLocation VILLAGE = mc("village");
    public static final ResourceLocation DESERT_PYRAMID = mc("desert_pyramid");
    public static final ResourceLocation JUNGLE_PYRAMID = mc("jungle_pyramid");
    public static final ResourceLocation MONUMENT = mc("monument");
    public static final ResourceLocation MANSION = mc("mansion");
    public static final ResourceLocation END_CITY = mc("end_city");
    public static final ResourceLocation BURIED_TREASURE = mc("buried_treasure");
    public static final ResourceLocation SHIPWRECK = mc("shipwreck");
    public static final ResourceLocation RUINED_PORTAL = mc("ruined_portal");
    public static final ResourceLocation BASTION_REMNANT = mc("bastion_remnant");
    public static final ResourceLocation FORTRESS = mc("fortress");
    public static final ResourceLocation ANCIENT_CITY = mc("ancient_city");
    public static final ResourceLocation TRAIL_RUINS = mc("trail_ruins");
    public static final ResourceLocation TRIAL_CHAMBERS = mc("trial_chambers");
    public static final ResourceLocation PILLAGER_OUTPOST = mc("pillager_outpost");
    public static final ResourceLocation IGLOO = mc("igloo");
    public static final ResourceLocation OCEAN_RUIN = mc("ocean_ruin");

    /** Structure id &rarr; representative icon item (SPEC §11 table, plus the 1.21 additions). */
    private static final Map<ResourceLocation, Item> ICONS = Map.ofEntries(
            Map.entry(DUNGEON, Items.MOSSY_COBBLESTONE),
            Map.entry(MINESHAFT, Items.RAIL),
            Map.entry(STRONGHOLD, Items.END_PORTAL_FRAME),
            Map.entry(VILLAGE, Items.EMERALD),
            Map.entry(DESERT_PYRAMID, Items.SANDSTONE),
            Map.entry(JUNGLE_PYRAMID, Items.MOSSY_COBBLESTONE),
            Map.entry(MONUMENT, Items.PRISMARINE),
            Map.entry(MANSION, Items.DARK_OAK_LOG),
            Map.entry(END_CITY, Items.PURPUR_BLOCK),
            Map.entry(BURIED_TREASURE, Items.HEART_OF_THE_SEA),
            Map.entry(SHIPWRECK, Items.OAK_BOAT),
            Map.entry(RUINED_PORTAL, Items.CRYING_OBSIDIAN),
            Map.entry(BASTION_REMNANT, Items.BLACKSTONE),
            Map.entry(FORTRESS, Items.NETHER_BRICKS),
            Map.entry(ANCIENT_CITY, Items.SCULK),
            Map.entry(TRAIL_RUINS, Items.DECORATED_POT),
            Map.entry(TRIAL_CHAMBERS, Items.VAULT),
            Map.entry(PILLAGER_OUTPOST, Items.CROSSBOW),
            Map.entry(IGLOO, Items.SNOW_BLOCK),
            Map.entry(OCEAN_RUIN, Items.PRISMARINE_BRICKS));

    private StructureIcons() {
    }

    /** The icon item for {@code structure}; {@link Items#CHEST} for modded/unmapped structures. */
    public static Item iconFor(ResourceLocation structure) {
        return ICONS.getOrDefault(structure, Items.CHEST);
    }

    /** The set of structure ids carrying a dedicated icon (every vanilla structure). */
    public static Set<ResourceLocation> mappedStructures() {
        return ICONS.keySet();
    }

    private static ResourceLocation mc(String path) {
        return ResourceLocation.withDefaultNamespace(path);
    }
}
