package com.rfizzle.prosperity.compat.index;

import com.rfizzle.prosperity.config.DistanceTier;
import com.rfizzle.prosperity.loot.index.FilterMarkers;
import com.rfizzle.prosperity.loot.index.LootIndexEntry;
import com.rfizzle.prosperity.loot.index.LootIndexFilter;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.ItemStack;

/**
 * Viewer-agnostic builder of a loot-index row's filter markers (S-042, SPEC §11), the tier/source
 * parallel of {@link LootIndexLabels}. Each row carries the marker items for every tier it is reachable
 * from (so "available at" tier filtering works) plus its origin marker; the EMI/REI/JEI plugins attach
 * these as invisible catalyst ingredients and register {@link #tierChips}/{@link #sourceChips} as
 * workstations so a viewer's "uses" tree filters by tier and source.
 *
 * <p>Callers pass the tier list from the server-synced config ({@code ClientProsperityData.config()})
 * so the ladder matches the server's actual tiers, not the client's local {@code config.json}.
 */
public final class LootIndexFilterMarkers {

    private LootIndexFilterMarkers() {
    }

    /** The marker items for {@code entry}: one per reachable tier, plus the row's source marker. */
    public static List<ItemStack> markersFor(LootIndexEntry entry, List<DistanceTier> tiers) {
        List<String> reachable = LootIndexFilter.reachableTierNames(entry.minTier(), tiers);
        List<ItemStack> out = new ArrayList<>(reachable.size() + 1);
        for (String tierName : reachable) {
            out.add(new ItemStack(FilterMarkers.tierMarker(LootIndexFilter.indexOf(tierName, tiers))));
        }
        out.add(new ItemStack(FilterMarkers.sourceMarker(entry.origin())));
        return out;
    }

    /** One marker per configured tier — the tier filter chips registered as workstations/catalysts. */
    public static List<ItemStack> tierChips(List<DistanceTier> tiers) {
        List<ItemStack> out = new ArrayList<>(tiers.size());
        for (int i = 0; i < tiers.size(); i++) {
            out.add(new ItemStack(FilterMarkers.tierMarker(i)));
        }
        return out;
    }

    /** The two source filter chips (Vanilla, Injected) registered as workstations/catalysts. */
    public static List<ItemStack> sourceChips() {
        return List.of(new ItemStack(FilterMarkers.vanillaMarker()), new ItemStack(FilterMarkers.injectedMarker()));
    }
}
