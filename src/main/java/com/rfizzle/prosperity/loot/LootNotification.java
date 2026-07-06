package com.rfizzle.prosperity.loot;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.config.DistanceTier;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Action-bar loot notifications (S-021, SPEC §8). On the first generation of an instanced container
 * (not return visits), the opener is shown a brief overlay message naming the resolved tier and the
 * active modifiers &mdash; e.g. {@code "✦ Wilderness — 2.0x stacks, +2 quality"}, with a structure
 * suffix when a structure override changed the tier and the modifier clause omitted at the baseline
 * (Local) tier.
 *
 * <p>The numbers reflect the <em>final</em> post-modifier values read back from the loot-modifier
 * event (S-013), so distance scaling, structure overrides, the player's {@code generic.luck}, and any
 * API listener all show through. Every line is built with {@link Component#translatableWithFallback}
 * so it is localizable yet still renders deterministically on a headless server (the fallback text).
 *
 * <p>The numeric and text-shaping helpers are pure (no Minecraft state) so they are unit tested; the
 * assembled {@link Component} and the gated send are covered by a gametest.
 */
public final class LootNotification {

    private LootNotification() {
    }

    /**
     * Build the notification and send it to {@code player} as an action-bar overlay, honoring
     * {@code enableLootNotifications}. The structure suffix is included only when a structure override
     * actually changed the tier from the pure distance band (SPEC §8 / §6). Returns the message that
     * was sent, or {@code null} when notifications are disabled (so a test can assert the gate without
     * observing the packet).
     */
    @Nullable
    public static Component send(ServerPlayer player, ServerLevel level, Vec3 origin,
            LootScaling.ScaledTier scaled, double finalStackMultiplier, float finalLuck) {
        if (!Prosperity.getConfig().enableLootNotifications) {
            return null;
        }
        int quality = qualityValue(finalLuck);
        DistanceTier base = LootScaling.resolveTier(level, origin.x, origin.z);
        ResourceLocation structure = overrideChangedTier(scaled, base) ? scaled.structure() : null;
        Component message = build(scaled.tier(), finalStackMultiplier, quality, structure);
        player.displayClientMessage(message, true);
        return message;
    }

    /**
     * Send the structure completion fanfare (e.g. {@code "✦ Stronghold cleared!"}) as an action-bar
     * overlay, honoring {@code enableLootNotifications} like every other loot notification. Returns
     * the message that was sent, or {@code null} when notifications are disabled.
     */
    @Nullable
    public static Component sendStructureCleared(ServerPlayer player, ResourceLocation structure) {
        if (!Prosperity.getConfig().enableLootNotifications) {
            return null;
        }
        Component message = buildStructureCleared(structure);
        player.displayClientMessage(message, true);
        return message;
    }

    /**
     * Send the one-time first-open framing (issue #86) as a chat line the first time a player has
     * instanced loot generated, honoring {@code enableLootNotifications} like every other loot
     * notification. Sent to chat, not the action bar, so it coexists with the tier toast that fires
     * the same tick. The caller gates the once-per-player semantics off the {@code 0 → 1} transition
     * of the player's lifetime container count, so this method only shapes and gates the send.
     * Returns the message that was sent, or {@code null} when notifications are disabled.
     */
    @Nullable
    public static Component sendFirstOpen(ServerPlayer player) {
        if (!Prosperity.getConfig().enableLootNotifications) {
            return null;
        }
        Component message = buildFirstOpen();
        player.displayClientMessage(message, false);
        return message;
    }

    /**
     * Assemble the first-open framing: a plain chat sentence explaining that instanced loot is rolled
     * per player. Localizable with a fallback so it renders deterministically on a headless server.
     */
    public static Component buildFirstOpen() {
        return Component.translatableWithFallback("prosperity.notification.first_open",
                "This loot was rolled just for you — other players get their own.");
    }

    /**
     * Assemble the completion fanfare: the structure's display name (localizable with a humanized
     * fallback, so modded structures still read sensibly) in a {@code "✦ %s cleared!"} frame.
     */
    public static Component buildStructureCleared(ResourceLocation structure) {
        Component structureName = Component.translatableWithFallback(
                "prosperity.structure." + structure.getPath(), humanize(structure.getPath()));
        return Component.translatableWithFallback(
                "prosperity.notification.structure_cleared", "✦ %s cleared!", structureName);
    }

    /**
     * Assemble the notification {@link Component}: {@code "✦ <Tier>"}, plus a {@code " — Nx stacks,
     * +N quality"} clause when the values are not at their baseline defaults, plus a {@code " (Name)"}
     * suffix when {@code structure} is non-null. The tier and structure names are localizable with a
     * humanized fallback, so unknown (e.g. modded) structures still read sensibly.
     */
    public static Component build(DistanceTier tier, double finalStackMultiplier, int finalQuality,
            @Nullable ResourceLocation structure) {
        Component tierName = Component.translatableWithFallback(
                "prosperity.tier." + tier.name(), capitalize(tier.name()));
        MutableComponent message = Component.translatableWithFallback(
                "prosperity.notification.loot_generated", "✦ %s", tierName);
        if (hasModifiers(finalStackMultiplier, finalQuality)) {
            message.append(Component.translatableWithFallback(
                    "prosperity.notification.modifiers", " — %sx stacks, +%s quality",
                    multiplierText(finalStackMultiplier), finalQuality));
        }
        if (structure != null) {
            Component structureName = Component.translatableWithFallback(
                    "prosperity.structure." + structure.getPath(), humanize(structure.getPath()));
            message.append(Component.translatableWithFallback(
                    "prosperity.notification.structure", " (%s)", structureName));
        }
        return message;
    }

    /**
     * Whether a structure override changed the resolved tier from the pure distance band, the only
     * case in which the structure name is shown (SPEC §6/§8). {@code scaled.structure()} is populated
     * for <em>any</em> detected structure, including one with no override, so presence alone is not
     * enough &mdash; the tier must actually differ from {@code base}.
     */
    public static boolean overrideChangedTier(LootScaling.ScaledTier scaled, DistanceTier base) {
        return scaled.structure() != null && !scaled.tier().equals(base);
    }

    /** Whether either modifier is off its baseline ({@code 1.0x} stacks, {@code +0} quality). */
    public static boolean hasModifiers(double stackMultiplier, int quality) {
        return stackMultiplier != 1.0 || quality != 0;
    }

    /**
     * A stack multiplier in its natural decimal form, e.g. {@code 2.0 -> "2.0"}, {@code 2.75 -> "2.75"}
     * (matching the SPEC §8 examples and {@code /prosperity info}'s rendering).
     */
    public static String multiplierText(double stackMultiplier) {
        return Double.toString(stackMultiplier);
    }

    /** The integer quality shown for a final luck value (the loot-modifier event's {@code luck}). */
    public static int qualityValue(float luck) {
        return Math.round(luck);
    }

    /** Humanize a resource path, e.g. {@code "ancient_city" -> "Ancient City"}, as a name fallback. */
    public static String humanize(String path) {
        StringBuilder out = new StringBuilder(path.length());
        boolean startOfWord = true;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '_' || c == '/') {
                out.append(' ');
                startOfWord = true;
            } else if (startOfWord) {
                out.append(Character.toUpperCase(c));
                startOfWord = false;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String capitalize(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
