package com.rfizzle.prosperity.loot.injection;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.config.DistanceTier;
import com.rfizzle.prosperity.config.ProsperityConfig;
import com.rfizzle.prosperity.loot.LootScaling;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import org.jetbrains.annotations.Nullable;

/**
 * The runtime eligibility-and-draw logic for loot injection (S-014, SPEC §5): given the tier-gated
 * groups {@link InjectionRegistry} holds for a target table, narrows them to the entries eligible at
 * a container's tier and dimension, optionally applies the per-group {@code chance} gate, and picks
 * one weighted-random item. Stateless — every method is a pure function of its arguments and the
 * live config, so the draw for a given {@link RandomSource} seed is fully reproducible.
 *
 * <p>Eligibility gates on two conditions that both must pass: the container's resolved
 * {@link DistanceTier} (the geographic/structure tier, not the post-modifier luck) being at or above
 * the entry's {@code min_tier}, compared by {@link DistanceTier#minDistance()}; and the container's
 * dimension being in the entry's {@code dimensions} list (an empty list matches any dimension). A
 * group additionally carries a {@code chance} (default {@code 1.0}) rolled from the draw's
 * {@link RandomSource} <em>before</em> the weighted draw; a failed roll drops the group from the pool.
 */
public final class InjectionSelector {

    private InjectionSelector() {
    }

    /**
     * One weighted-random eligible injected item for {@code list} (the groups registered for a target
     * table) at {@code tier} in {@code dimension}, or {@code null} when the list is empty or none are
     * eligible. Generative entries resolve their enchantment tag against {@code registries} at draw
     * time. With {@code applyChance} each eligible group first rolls its {@code chance} from
     * {@code random} (a failed roll drops the group, possibly leaving nothing to draw); without it the
     * chance gate is bypassed and the draw runs over every eligible entry — the completion-bonus path.
     */
    @Nullable
    public static ItemStack pick(List<Tiered> list, DistanceTier tier, ResourceLocation dimension,
            RandomSource random, HolderLookup.Provider registries, boolean applyChance) {
        if (list.isEmpty()) {
            return null;
        }
        ProsperityConfig cfg = Prosperity.getConfig();
        List<Entry> pool = applyChance
                ? survivingEntries(list, tier, dimension, cfg, random)
                : eligibleEntries(list, tier, dimension, cfg);
        return draw(pool, random, registries);
    }

    /**
     * The injection groups eligible at {@code containerTier} in {@code dimension}: their {@code min_tier}
     * resolves and is at or below the tier, and their {@code dimensions} are empty (any) or contain the
     * dimension. The two gates compose — both must pass. The per-group {@code chance} is <em>not</em>
     * consulted here; see {@link #survivingEntries} for the generation-time roll.
     */
    public static List<Entry> eligibleEntries(List<Tiered> list, DistanceTier containerTier,
            ResourceLocation dimension, ProsperityConfig cfg) {
        List<Entry> out = new ArrayList<>();
        for (Tiered tiered : list) {
            if (applies(tiered, containerTier, dimension, cfg)) {
                out.addAll(tiered.entries());
            }
        }
        return out;
    }

    /**
     * {@link #eligibleEntries} narrowed by the per-group {@code chance} roll: each eligible group with
     * {@code chance < 1.0} consumes one {@code random.nextFloat()}, in registry (file-id) order, and
     * survives only when the roll lands under its chance. A group at the default {@code 1.0} consumes
     * <em>no</em> randomness, so a registry with no gated groups sharing the target draws
     * bit-identically to a build without the gate — a pre-gate datapack's worlds reproduce their
     * loot exactly. (A gated group ahead of an ungated one does shift the ungated group's draw, as
     * any pool change would.)
     */
    public static List<Entry> survivingEntries(List<Tiered> list, DistanceTier containerTier,
            ResourceLocation dimension, ProsperityConfig cfg, RandomSource random) {
        List<Entry> out = new ArrayList<>();
        for (Tiered tiered : list) {
            if (!applies(tiered, containerTier, dimension, cfg)) {
                continue;
            }
            if (tiered.chance() < 1.0f && random.nextFloat() >= tiered.chance()) {
                continue;
            }
            out.addAll(tiered.entries());
        }
        return out;
    }

    /** Whether {@code tiered}'s tier and dimension gates both pass — the chance roll is separate. */
    private static boolean applies(Tiered tiered, DistanceTier containerTier, ResourceLocation dimension,
            ProsperityConfig cfg) {
        DistanceTier min = LootScaling.tierByName(cfg, tiered.minTier());
        boolean tierOk = min != null && containerTier.minDistance() >= min.minDistance();
        boolean dimensionOk = tiered.dimensions().isEmpty() || tiered.dimensions().contains(dimension);
        return tierOk && dimensionOk;
    }

    /**
     * One entry chosen by weight, realized to a stack; {@code null} when nothing can be drawn. A
     * generative entry whose tag resolves empty or absent against {@code registries} is dropped from
     * the pool <em>before</em> weighting, so the draw slot falls to the remaining entries rather than
     * being wasted. Both the resolution order (tag order) and the weighted pick are deterministic for
     * a given {@code random} seed.
     */
    @Nullable
    public static ItemStack draw(List<Entry> eligible, RandomSource random, HolderLookup.Provider registries) {
        List<Entry> pool = new ArrayList<>(eligible.size());
        List<HolderSet.Named<Enchantment>> resolved = new ArrayList<>(eligible.size());
        for (Entry entry : eligible) {
            HolderSet.Named<Enchantment> holders = null;
            if (entry.enchantRandomly().isPresent()) {
                holders = resolveTag(registries, entry.enchantRandomly().get());
                if (holders == null || holders.size() == 0) {
                    continue;
                }
            }
            pool.add(entry);
            resolved.add(holders);
        }
        if (pool.isEmpty()) {
            return null;
        }
        int total = 0;
        for (Entry entry : pool) {
            total += entry.weight();
        }
        int roll = random.nextInt(total);
        int chosen = pool.size() - 1;
        for (int i = 0; i < pool.size(); i++) {
            roll -= pool.get(i).weight();
            if (roll < 0) {
                chosen = i;
                break;
            }
        }
        Entry entry = pool.get(chosen);
        HolderSet.Named<Enchantment> holders = resolved.get(chosen);
        return holders == null ? entry.stack().copy()
                : generate(entry.stack(), holders, entry.level(), random);
    }

    /** The tag's holder set in {@code registries}, or {@code null} when the tag is absent. */
    @Nullable
    private static HolderSet.Named<Enchantment> resolveTag(HolderLookup.Provider registries,
            TagKey<Enchantment> tag) {
        return registries.lookup(Registries.ENCHANTMENT)
                .flatMap(lookup -> lookup.get(tag))
                .orElse(null);
    }

    /**
     * Realize a generative entry: one enchantment drawn uniformly from {@code holders} (known
     * non-empty), stored on a copy of {@code prototype} at the level {@code policy} yields. The stack's
     * item routes the write &mdash; {@link EnchantmentHelper#updateEnchantments} targets
     * {@code stored_enchantments} on an enchanted book.
     */
    private static ItemStack generate(ItemStack prototype, HolderSet.Named<Enchantment> holders,
            LevelPolicy policy, RandomSource random) {
        Holder<Enchantment> holder = holders.get(random.nextInt(holders.size()));
        int level = policyLevel(policy, holder.value().getMinLevel(), holder.value().getMaxLevel(), random);
        ItemStack stack = prototype.copy();
        EnchantmentHelper.updateEnchantments(stack, mutable -> mutable.set(holder, level));
        return stack;
    }

    /**
     * The enchantment level a policy yields within {@code [min, max]}: {@code mid} is the rounded-up
     * midpoint {@code ceil(max/2)} (floored at {@code min}), {@code max} the top level, and
     * {@code uniform} a uniform draw over the whole range &mdash; vanilla {@code enchant_randomly}
     * semantics, the only policy that consumes {@code random}.
     */
    public static int policyLevel(LevelPolicy policy, int min, int max, RandomSource random) {
        return switch (policy) {
            case MID -> Math.max(min, (max + 1) / 2);
            case MAX -> max;
            case UNIFORM -> Mth.nextInt(random, min, max);
        };
    }
}
