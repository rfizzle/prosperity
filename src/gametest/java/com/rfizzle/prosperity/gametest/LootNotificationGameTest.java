package com.rfizzle.prosperity.gametest;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.config.DistanceTier;
import com.rfizzle.prosperity.loot.LootNotification;
import com.rfizzle.prosperity.loot.LootScaling;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Runtime checks for the action-bar loot notification (S-021, SPEC §8). The assembled message is
 * asserted by rendering {@link LootNotification#build} to its string (the server resolves each
 * {@code translatableWithFallback} to its baked-in fallback, so the text is deterministic headless),
 * and the {@code enableLootNotifications} gate is asserted through {@link LootNotification#send}. The
 * value- and text-shaping logic is unit tested in {@code LootNotificationTest}.
 *
 * <p>Runs in its own batch because the gate test toggles the global {@code enableLootNotifications}
 * flag; a unique batch keeps that mutation from racing tests in the default batch.
 */
public class LootNotificationGameTest implements FabricGameTest {

    private static final String BATCH = "loot_notification";

    private static final DistanceTier LOCAL = new DistanceTier("local", 0, 1.0, 0);
    private static final DistanceTier WILDERNESS = new DistanceTier("wilderness", 3000, 2.0, 2);
    private static final DistanceTier OUTLANDS = new DistanceTier("outlands", 6000, 2.75, 3);

    /** Local omits the clause; an off-baseline tier shows it; an override appends the structure. */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    public void messageRendersExpectedText(GameTestHelper helper) {
        helper.assertTrue(
                LootNotification.build(LOCAL, 1.0, 0, null).getString().equals("✦ Local"),
                "the baseline tier must render bare, with no modifier clause");
        helper.assertTrue(
                LootNotification.build(WILDERNESS, 2.0, 2, null).getString()
                        .equals("✦ Wilderness — 2.0x stacks, +2 quality"),
                "an off-baseline tier must render its tier name and modifier clause");
        helper.assertTrue(
                LootNotification.build(OUTLANDS, 2.75, 3, ResourceLocation.parse("minecraft:ancient_city"))
                        .getString().equals("✦ Outlands — 2.75x stacks, +3 quality (Ancient City)"),
                "a structure override must append the structure name");
        helper.succeed();
    }

    /** {@code send} returns null (and shows nothing) when notifications are off, the message when on. */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    @SuppressWarnings("removal")
    public void sendHonorsConfigToggle(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Vec3 origin = Vec3.atCenterOf(helper.absolutePos(new BlockPos(1, 1, 1)));
        // No structure, so the suffix never applies regardless of the base tier resolved at the origin.
        LootScaling.ScaledTier scaled = new LootScaling.ScaledTier(WILDERNESS, null);

        boolean saved = Prosperity.getConfig().enableLootNotifications;
        try {
            Prosperity.getConfig().enableLootNotifications = false;
            helper.assertTrue(LootNotification.send(player, level, origin, scaled, 2.0, 2.0f) == null,
                    "a disabled toggle must suppress the notification");

            Prosperity.getConfig().enableLootNotifications = true;
            Component sent = LootNotification.send(player, level, origin, scaled, 2.0, 2.0f);
            helper.assertTrue(sent != null
                            && sent.getString().equals("✦ Wilderness — 2.0x stacks, +2 quality"),
                    "an enabled toggle must send the formatted notification");
        } finally {
            Prosperity.getConfig().enableLootNotifications = saved;
        }
        player.discard();
        helper.succeed();
    }
}
