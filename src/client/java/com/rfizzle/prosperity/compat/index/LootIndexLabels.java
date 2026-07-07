package com.rfizzle.prosperity.compat.index;

import com.rfizzle.prosperity.loot.index.LootIndexEntry;
import com.rfizzle.prosperity.loot.index.LootIndexFormat;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * Viewer-agnostic label builders for the loot-index recipe-viewer plugins (S-029 EMI, S-030 REI,
 * S-031 JEI). Holds the {@link Component} text shared across every viewer so each plugin renders an
 * identical row, and so no plugin class-loads another's viewer-typed classes to reuse the labels
 * (calling these from REI must not pull in EMI types — that would crash when EMI is absent).
 *
 * <p>Custom-config tier names and modded/unmapped structures degrade to a title-cased fallback via
 * {@code translatableWithFallback}, mirroring {@link LootIndexFormat}.
 */
public final class LootIndexLabels {

    private LootIndexLabels() {
    }

    /** The localized structure name, degrading modded/unmapped structures to a title-cased path. */
    public static Component structureName(LootIndexEntry entry) {
        String path = entry.structure().getPath();
        return Component.translatableWithFallback(LootIndexFormat.structureKey(path), LootIndexFormat.titleCase(path));
    }

    /** The on-screen tier badge: {@code "Frontier+"} for an injected entry, "Any tier" for vanilla. */
    public static Component tierBadge(LootIndexEntry entry) {
        return entry.minTier()
                .map(name -> Component.translatable("loot_index.prosperity.tier", tierName(name)))
                .orElseGet(() -> Component.translatable("loot_index.prosperity.any_tier"));
    }

    /** The output slot's appended tooltip line: the injection note for injected rows, else the source. */
    public static Component sourceTooltip(LootIndexEntry entry) {
        if (entry.origin() == LootIndexEntry.Origin.INJECTED) {
            return Component.translatable("loot_index.prosperity.injected", tierBadge(entry)).withStyle(ChatFormatting.GOLD);
        }
        return Component.translatable("loot_index.prosperity.source.vanilla").withStyle(ChatFormatting.GRAY);
    }

    private static Component tierName(String name) {
        return Component.translatableWithFallback("tier.prosperity." + name, LootIndexFormat.titleCase(name));
    }
}
