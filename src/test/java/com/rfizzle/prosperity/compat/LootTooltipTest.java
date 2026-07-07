package com.rfizzle.prosperity.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rfizzle.prosperity.compat.LootTooltip.Status;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

/**
 * Coverage of the read side of the container tooltip (S-026): the pure {@link LootTooltip#statusOf}
 * truth table and {@link LootTooltip#buildLines} over hand-built server-data tags. No game bootstrap —
 * {@link Component} and {@link CompoundTag} construct without registries. The write side (which needs a
 * live world) is covered by {@code ContainerTooltipGameTest}.
 */
class LootTooltipTest {

    @Test
    void statusTruthTable() {
        assertEquals(Status.UNLOOTED, LootTooltip.statusOf(false, false, false));
        assertEquals(Status.LOOTED, LootTooltip.statusOf(false, true, false));
        assertEquals(Status.REFRESHED, LootTooltip.statusOf(false, true, true));
        // Blacklist wins over any instanced state.
        assertEquals(Status.VANILLA, LootTooltip.statusOf(true, false, false));
        assertEquals(Status.VANILLA, LootTooltip.statusOf(true, true, true));
        // Expiry implies refreshed even ahead of the looted check.
        assertEquals(Status.REFRESHED, LootTooltip.statusOf(false, false, true));
    }

    @Test
    void absentDataYieldsNoLines() {
        assertTrue(LootTooltip.buildLines(new CompoundTag()).isEmpty());
    }

    @Test
    void vanillaShowsOnlyTheStatusLine() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(LootTooltip.KEY_PRESENT, true);
        tag.putInt(LootTooltip.KEY_STATUS, Status.VANILLA.ordinal());
        // Tier data present in the tag is ignored once status is vanilla.
        tag.putBoolean(LootTooltip.KEY_TIER_SHOWN, true);
        tag.putString(LootTooltip.KEY_TIER_NAME, "wilderness");
        tag.putDouble(LootTooltip.KEY_TIER_MULT, 2.0);

        List<Component> lines = LootTooltip.buildLines(tag);
        assertEquals(1, lines.size());
        assertEquals("jade.prosperity.status.vanilla", key(lines.get(0)));
        assertEquals(ChatFormatting.WHITE.getColor().intValue(), color(lines.get(0)));
    }

    @Test
    void unlootedWithTierLine() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(LootTooltip.KEY_PRESENT, true);
        tag.putInt(LootTooltip.KEY_STATUS, Status.UNLOOTED.ordinal());
        tag.putBoolean(LootTooltip.KEY_TIER_SHOWN, true);
        tag.putString(LootTooltip.KEY_TIER_NAME, "wilderness");
        tag.putDouble(LootTooltip.KEY_TIER_MULT, 2.0);

        List<Component> lines = LootTooltip.buildLines(tag);
        assertEquals(2, lines.size());
        assertEquals("jade.prosperity.status.unlooted", key(lines.get(0)));
        assertEquals(ChatFormatting.GOLD.getColor().intValue(), color(lines.get(0)));

        assertEquals("jade.prosperity.tier", key(lines.get(1)));
        Object[] tierArgs = args(lines.get(1));
        assertEquals("tier.prosperity.wilderness", key((Component) tierArgs[0]));
        assertEquals("2.0", tierArgs[1]);
    }

    @Test
    void lootedShowsTierOverrideAndRefresh() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(LootTooltip.KEY_PRESENT, true);
        tag.putInt(LootTooltip.KEY_STATUS, Status.LOOTED.ordinal());
        tag.putBoolean(LootTooltip.KEY_TIER_SHOWN, true);
        tag.putString(LootTooltip.KEY_TIER_NAME, "outlands");
        tag.putDouble(LootTooltip.KEY_TIER_MULT, 2.75);
        tag.putBoolean(LootTooltip.KEY_OVERRIDE_SHOWN, true);
        tag.putString(LootTooltip.KEY_OVERRIDE_STRUCTURE, "minecraft:ancient_city");
        tag.putString(LootTooltip.KEY_OVERRIDE_MODE, "minimum");
        tag.putString(LootTooltip.KEY_OVERRIDE_TIER, "outlands");
        tag.putBoolean(LootTooltip.KEY_REFRESH_SHOWN, true);
        tag.putLong(LootTooltip.KEY_REFRESH_TICKS, 2 * 24_000L + 14 * 1_000L);

        List<Component> lines = LootTooltip.buildLines(tag);
        assertEquals(4, lines.size());
        assertEquals("jade.prosperity.status.looted", key(lines.get(0)));
        assertEquals(ChatFormatting.GRAY.getColor().intValue(), color(lines.get(0)));

        assertEquals("jade.prosperity.tier", key(lines.get(1)));
        assertEquals("jade.prosperity.override.minimum", key(lines.get(2)));
        Object[] overrideArgs = args(lines.get(2));
        assertEquals("structure.prosperity.ancient_city", key((Component) overrideArgs[0]));
        assertEquals("tier.prosperity.outlands", key((Component) overrideArgs[1]));

        assertEquals("jade.prosperity.refresh", key(lines.get(3)));
        assertEquals("2d 14h", args(lines.get(3))[0]);
    }

    @Test
    void refreshedStatusIsGreen() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(LootTooltip.KEY_PRESENT, true);
        tag.putInt(LootTooltip.KEY_STATUS, Status.REFRESHED.ordinal());

        List<Component> lines = LootTooltip.buildLines(tag);
        assertEquals(1, lines.size());
        assertEquals("jade.prosperity.status.refreshed", key(lines.get(0)));
        assertEquals(ChatFormatting.GREEN.getColor().intValue(), color(lines.get(0)));
    }

    @Test
    void overrideModeSelectsTheMatchingKey() {
        assertEquals("jade.prosperity.override.fixed", key(overrideLine("fixed")));
        assertEquals("jade.prosperity.override.maximum", key(overrideLine("maximum")));
        assertEquals("jade.prosperity.override.minimum", key(overrideLine("minimum")));
    }

    private static Component overrideLine(String mode) {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(LootTooltip.KEY_PRESENT, true);
        tag.putInt(LootTooltip.KEY_STATUS, Status.UNLOOTED.ordinal());
        tag.putBoolean(LootTooltip.KEY_OVERRIDE_SHOWN, true);
        tag.putString(LootTooltip.KEY_OVERRIDE_STRUCTURE, "minecraft:monument");
        tag.putString(LootTooltip.KEY_OVERRIDE_MODE, mode);
        tag.putString(LootTooltip.KEY_OVERRIDE_TIER, "wilderness");
        // status line is index 0; the override line is the next one.
        return LootTooltip.buildLines(tag).get(1);
    }

    private static String key(Component component) {
        return component.getContents() instanceof TranslatableContents t ? t.getKey() : null;
    }

    private static Object[] args(Component component) {
        return ((TranslatableContents) component.getContents()).getArgs();
    }

    private static int color(Component component) {
        return component.getStyle().getColor().getValue();
    }
}
