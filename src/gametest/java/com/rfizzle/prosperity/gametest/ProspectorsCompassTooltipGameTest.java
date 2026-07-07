package com.rfizzle.prosperity.gametest;

import com.rfizzle.prosperity.item.ProsperityItems;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * Runtime coverage for the Prospector's Compass behavior tooltip (S-083): the item's own
 * {@link net.minecraft.world.item.Item#appendHoverText} contributes two translation-keyed lines, so
 * a player who finds the compass learns what it does. Rendering {@code getTooltipLines} on a real
 * stack exercises the full vanilla tooltip assembly, which is where the ordering (above any
 * recipe-viewer footer) and the translation-key wiring actually live.
 */
public class ProspectorsCompassTooltipGameTest implements FabricGameTest {

    private static final String POINTS_KEY = "tooltip.prosperity.prospectors_compass.points";
    private static final String SPINS_KEY = "tooltip.prosperity.prospectors_compass.spins";

    /** The index of the first tooltip line carrying the given key, or {@code -1} if absent. */
    private static int indexOfKey(List<Component> lines, String key) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).getContents() instanceof TranslatableContents t && key.equals(t.getKey())) {
                return i;
            }
        }
        return -1;
    }

    /** The rendered tooltip carries both behavior lines, keyed and below the name (index 0). */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    @SuppressWarnings("removal")
    public void tooltipShowsBothBehaviorLines(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        ItemStack stack = new ItemStack(ProsperityItems.PROSPECTORS_COMPASS);

        List<Component> lines =
                stack.getTooltipLines(Item.TooltipContext.EMPTY, player, TooltipFlag.Default.NORMAL);

        int points = indexOfKey(lines, POINTS_KEY);
        int spins = indexOfKey(lines, SPINS_KEY);
        helper.assertTrue(points > 0, "the 'points to nearest' line renders below the item name");
        helper.assertTrue(spins > points, "the 'spins when empty' line renders after the 'points' line");
        player.discard();
        helper.succeed();
    }
}
