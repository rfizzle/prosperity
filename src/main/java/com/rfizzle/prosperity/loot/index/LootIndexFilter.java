package com.rfizzle.prosperity.loot.index;

import com.rfizzle.prosperity.config.DistanceTier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pure filter logic for the loot index (S-042, SPEC §11). Decides, for a row's minimum tier, which
 * distance tiers can reach it under "available at" semantics: a row gated at min tier <em>M</em> is
 * reachable from <em>M</em> and every deeper tier, and a vanilla row (no min tier) is reachable from
 * every tier. The recipe-viewer plugins tag each row with these tiers' marker items so a viewer's
 * "uses" tree gives a per-tier view; this class is the single place that logic lives.
 *
 * <p>No Minecraft types — it operates on the row's {@code Optional<String>} min tier and the configured
 * {@link DistanceTier} list — so it is unit-testable as plain JUnit over synthetic tiers.
 */
public final class LootIndexFilter {

    private LootIndexFilter() {
    }

    /**
     * The names of every tier from which a row with {@code minTier} is reachable, in {@code tiers}
     * order. An empty {@code minTier} (a vanilla "Any tier" row) is reachable from all tiers; a min
     * tier not present in {@code tiers} (a custom or renamed tier) is treated permissively as
     * reachable from all, so a misconfigured row is never silently hidden from every filter.
     */
    public static List<String> reachableTierNames(Optional<String> minTier, List<DistanceTier> tiers) {
        if (minTier.isPresent()) {
            int min = indexOf(minTier.get(), tiers);
            if (min >= 0) {
                List<String> out = new ArrayList<>(tiers.size() - min);
                for (int i = min; i < tiers.size(); i++) {
                    out.add(tiers.get(i).name());
                }
                return out;
            }
        }
        List<String> all = new ArrayList<>(tiers.size());
        for (DistanceTier tier : tiers) {
            all.add(tier.name());
        }
        return all;
    }

    /** The index of the tier named {@code name} in {@code tiers}, or {@code -1} if absent. */
    public static int indexOf(String name, List<DistanceTier> tiers) {
        for (int i = 0; i < tiers.size(); i++) {
            if (tiers.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }
}
