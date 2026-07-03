package com.rfizzle.prosperity.compat.meridian;

import com.rfizzle.meridian.api.MeridianAPI;
import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.api.DistanceTierInfo;
import com.rfizzle.prosperity.api.ProsperityAPI;
import com.rfizzle.prosperity.config.DistanceTier;
import com.rfizzle.prosperity.loot.injection.LootInjectionManager;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * Meridian soft-dependency integration: distance-scaled dynamic enchantment rolls on found loot.
 * Installs the {@link LootInjectionManager.InjectedStackFinalizer} so that an injected
 * {@code minecraft:enchanted_book} carrying {@code meridian:*} stored enchantments has its authored
 * enchantments replaced at generation time by a live {@code MeridianAPI.rollLootEnchantments} draw —
 * the same rules as Meridian's enchanting table — at a power derived from the container's distance
 * tier through {@link MeridianEnchantPower}. Treasure-tagged enchantments only roll at the ladder's
 * deepest tier, and Meridian's per-enchantment {@code maxLootLevel} caps the rolled levels, so far
 * travel scales rewards without handing out maxed gear.
 *
 * <p>The roll consumes the injection draw's own deterministic {@link RandomSource} (seeded from the
 * container seed, refresh salt, and player UUID), so instanced loot stays reproducible per player
 * and re-rolls in lockstep with the main loot under {@code randomizeLootOnRefresh}. Books injected
 * from the built-in vanilla files (no {@code meridian:*} enchant) pass through untouched.
 *
 * <p>This class references {@code com.rfizzle.meridian.api} and must only be class-loaded behind an
 * {@code isModLoaded("meridian")} guard (Concord API Standard v1). A Meridian call that throws —
 * including the {@code LinkageError} an older Meridian jar without {@code rollLootEnchantments}
 * surfaces as — is swallowed (logged once) and the authored static enchantments stand, so the
 * integration can never break loot generation.
 */
public final class MeridianCompat {

    private static final String MERIDIAN_NAMESPACE = "meridian";
    private static final AtomicBoolean FAILURE_LOGGED = new AtomicBoolean(false);

    private MeridianCompat() {
    }

    /** Install the injected-book finalizer. Call once at initialization, behind the mod-loaded guard. */
    public static void register() {
        LootInjectionManager.setInjectedStackFinalizer(MeridianCompat::rollInjectedBook);
        Prosperity.LOGGER.info("Meridian detected: injected Meridian enchanted books now roll"
                + " distance-scaled enchantments");
    }

    /**
     * The finalizer: replace a Meridian book's authored stored enchantments with a dynamic roll at
     * the tier's power. Any non-Meridian stack, an off-ladder tier, an empty roll, or a failing
     * Meridian call returns {@code stack} unchanged — the authored enchantments are the fallback.
     */
    static ItemStack rollInjectedBook(ServerLevel level, ItemStack stack, DistanceTier tier,
            RandomSource random) {
        try {
            if (!stack.is(Items.ENCHANTED_BOOK) || !hasMeridianEnchant(stack)) {
                return stack;
            }
            List<DistanceTierInfo> ladder = ProsperityAPI.getDistanceTiers();
            int maxIndex = ladder.size() - 1;
            int power = MeridianEnchantPower.powerForTier(tierIndex(ladder, tier), maxIndex);
            if (power <= 0) {
                return stack;
            }
            boolean treasure = MeridianEnchantPower.treasureAllowed(tierIndex(ladder, tier), maxIndex);
            // Roll against a plain book so every enchantment is a candidate, matching how vanilla
            // loot enchants books before converting them.
            List<EnchantmentInstance> rolled = MeridianAPI.rollLootEnchantments(
                    level, random, new ItemStack(Items.BOOK), power, treasure);
            if (rolled.isEmpty()) {
                return stack;
            }
            ItemEnchantments.Mutable enchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
            for (EnchantmentInstance instance : rolled) {
                enchantments.upgrade(instance.enchantment, instance.level);
            }
            ItemStack out = stack.copy();
            out.set(DataComponents.STORED_ENCHANTMENTS, enchantments.toImmutable());
            return out;
        } catch (Throwable e) {
            // Throwable, not Exception: an older Meridian jar missing rollLootEnchantments surfaces
            // as a LinkageError, which must not escape into generation.
            if (FAILURE_LOGGED.compareAndSet(false, true)) {
                Prosperity.LOGGER.warn("Meridian enchantment roll failed; keeping the authored"
                        + " enchantments for this and any further failing generations", e);
            }
            return stack;
        }
    }

    /** Whether any stored enchantment on {@code stack} lives in the {@code meridian} namespace. */
    private static boolean hasMeridianEnchant(ItemStack stack) {
        ItemEnchantments stored =
                stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
        for (Holder<Enchantment> enchantment : stored.keySet()) {
            if (enchantment.unwrapKey()
                    .map(key -> key.location().getNamespace().equals(MERIDIAN_NAMESPACE))
                    .orElse(false)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The index of {@code tier} on the sorted ladder, matched by name; {@code 0} (no roll) when the
     * tier is off the ladder — the {@code local} sentinel of an empty config.
     */
    private static int tierIndex(List<DistanceTierInfo> ladder, DistanceTier tier) {
        for (DistanceTierInfo info : ladder) {
            if (info.name().equals(tier.name())) {
                return info.index();
            }
        }
        return 0;
    }
}
