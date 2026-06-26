package com.rfizzle.prosperity.loot.index;

import com.rfizzle.prosperity.loot.index.LootIndexEntry.Origin;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Representative marker items for the loot index's tier and source filters (S-042, SPEC §11). The
 * recipe-viewer plugins attach these as invisible catalyst ingredients and register them as
 * workstations, so a viewer's "uses" tree filters rows by tier and by origin — the same mechanism
 * {@link StructureIcons} provides for the structure filter.
 *
 * <p>Tier markers are keyed by tier <em>ordinal</em> (not name) so a custom or renamed tier list still
 * maps cleanly; an ordinal past the ladder reuses the deepest marker. Every marker item is disjoint
 * from {@link StructureIcons} so the structure, tier, and source filter axes never alias one another
 * (guarded by {@code StructureMappingTest}).
 */
public final class FilterMarkers {

    // Exploration-themed ladder, deepening with distance. Disjoint from StructureIcons by construction.
    private static final List<Item> TIER_LADDER = List.of(
            Items.COMPASS,          // local
            Items.MAP,              // frontier
            Items.SPYGLASS,         // wilderness
            Items.RECOVERY_COMPASS, // outlands
            Items.ECHO_SHARD);      // depths

    private static final Item VANILLA_MARKER = Items.BARREL;
    private static final Item INJECTED_MARKER = Items.NETHER_STAR;

    private FilterMarkers() {
    }

    /** The marker item for the tier at {@code ordinal}; an ordinal past the ladder reuses the deepest. */
    public static Item tierMarker(int ordinal) {
        int clamped = Math.max(0, Math.min(ordinal, TIER_LADDER.size() - 1));
        return TIER_LADDER.get(clamped);
    }

    /** The marker item for a row's origin: the injected source marker, else the vanilla one. */
    public static Item sourceMarker(Origin origin) {
        return origin == Origin.INJECTED ? INJECTED_MARKER : VANILLA_MARKER;
    }

    public static Item vanillaMarker() {
        return VANILLA_MARKER;
    }

    public static Item injectedMarker() {
        return INJECTED_MARKER;
    }

    /** Every distinct marker item — the disjointness guard set used by the marker/structure-icon test. */
    public static Set<Item> allMarkers() {
        Set<Item> set = new HashSet<>(TIER_LADDER);
        set.add(VANILLA_MARKER);
        set.add(INJECTED_MARKER);
        return set;
    }
}
