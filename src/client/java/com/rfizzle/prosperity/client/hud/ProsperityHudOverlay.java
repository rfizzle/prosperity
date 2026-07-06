package com.rfizzle.prosperity.client.hud;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.client.network.ClientProsperityData;
import com.rfizzle.prosperity.config.DistanceTier;
import com.rfizzle.prosperity.config.ProsperityConfig;
import com.rfizzle.prosperity.loot.LootScaling;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Slot-3 tier HUD badge in the shared Concord layout (SPEC §14, HUD-STANDARD §2): a 16×16 treasure
 * chest glyph with the current distance-tier name beside it on a semi-transparent box, the name drawn
 * in the tier's color. The tier is recomputed each frame from the player's XZ via
 * {@link LootScaling#resolveTier} (so the badge never disagrees with {@code /prosperity info} or
 * server-side generation), and crossing a tier boundary briefly lerps the label from gold to the new
 * tier color.
 *
 * <p>Prosperity sits below Tribulation (slot 1) and Mercantile (slot 2), so it offsets past the sum
 * of their {@link SiblingOffset#current() reported heights} at the shared top-left anchor. Its own
 * {@link #isHudVisible()}/{@link #getHudHeight()} accessors (reflected by
 * {@link com.rfizzle.prosperity.api.ProsperityAPI}) let a future slot-4 mod stack below it.</p>
 *
 * <p>All state here is render-thread only ({@code HudRenderCallback} fires on the render thread).</p>
 */
public final class ProsperityHudOverlay {

    private static final ResourceLocation ICON = Prosperity.id("textures/gui/hud_icon.png");

    private static final int ICON_SIZE = 16;
    /** Source texture is authored at 32×32 (HUD-STANDARD glyph density) and blitted down to 16×16. */
    private static final int ICON_TEX = 32;
    private static final int PAD_H = 4;
    private static final int PAD_V = 2;
    private static final int ICON_TEXT_GAP = 3;
    /** Semi-transparent dark badge background (SPEC §14). */
    private static final int BG_COLOR = 0x80000000;

    /**
     * This element's height contribution for sibling HUD coordination (HUD-STANDARD §3/§6): the
     * standard 20px slot element ({@link #ICON_SIZE} + 2·{@link #PAD_V}) plus the 2px stacking gap.
     */
    private static final int HUD_HEIGHT_CONTRIBUTION = 22;

    private static final SiblingOffset TRIBULATION =
            new SiblingOffset("tribulation", "com.rfizzle.tribulation.api.TribulationAPI");
    private static final SiblingOffset MERCANTILE =
            new SiblingOffset("mercantile", "com.rfizzle.mercantile.api.MercantileAPI");

    // Tier-crossing transition tracking (render-thread only). lastTierName is null until the first
    // render so the badge never flashes on login; lastChangeMs stays 0 (no flash) until a real change.
    private static String lastTierName;
    private static long lastChangeMs;

    private ProsperityHudOverlay() {
    }

    public static void register() {
        HudRenderCallback.EVENT.register(ProsperityHudOverlay::render);
    }

    private static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!shouldRender(mc)) {
            return;
        }
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;

        ProsperityConfig cfg = ClientProsperityData.config();
        boolean isEnd = level.dimension() == Level.END;
        DistanceTier tier = LootScaling.resolveTier(cfg, isEnd, player.getX(), player.getZ());
        String tierName = tier.name();

        long now = System.currentTimeMillis();
        if (lastTierName != null && !lastTierName.equals(tierName)) {
            lastChangeMs = now;
        }
        lastTierName = tierName;

        int color = HudMath.animatedColor(HudMath.tierColor(tierName), lastChangeMs, now);

        Font font = mc.font;
        Component label = Component.translatableWithFallback(
                "prosperity.tier." + tierName, HudMath.displayName(tierName));
        int textW = font.width(label);

        int badgeW = PAD_H + ICON_SIZE + ICON_TEXT_GAP + textW + PAD_H;
        int badgeH = ICON_SIZE + PAD_V * 2;

        ProsperityConfig.ClientConfig client = Prosperity.getConfig().client;
        ProsperityConfig.Anchor anchor = client.hudAnchor != null ? client.hudAnchor : ProsperityConfig.Anchor.TOP_LEFT;
        int stackOffset = HudMath.stackOffsetFor(anchor, TRIBULATION.current() + MERCANTILE.current());
        int x = HudMath.computeOriginX(anchor, graphics.guiWidth(), client.hudOffsetX, badgeW);
        int y = HudMath.computeOriginY(anchor, graphics.guiHeight(), client.hudOffsetY, badgeH, stackOffset);

        // Background box.
        graphics.fill(x, y, x + badgeW, y + badgeH, BG_COLOR);

        // Full-fidelity chest glyph, drawn untinted: the badge conveys tier through the label
        // color (below), not by recoloring the detailed art — multiply-tinting a full-color sprite
        // muddies it (cf. Mercantile's full-color glyph). The 32px master is blitted down to ICON_SIZE.
        int iconX = x + PAD_H;
        int iconY = y + PAD_V;
        graphics.blit(ICON, iconX, iconY, ICON_SIZE, ICON_SIZE, 0, 0, ICON_TEX, ICON_TEX, ICON_TEX, ICON_TEX);

        // Tier name in the tier color, with the gold->tier lerp on a crossing; default font + shadow.
        int textX = iconX + ICON_SIZE + ICON_TEXT_GAP;
        int textY = y + (badgeH - font.lineHeight) / 2 + 1;
        graphics.drawString(font, label, textX, textY, color, true);
    }

    private static boolean shouldRender(Minecraft mc) {
        if (mc == null) {
            return false;
        }
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            return false;
        }
        // The four HUD-STANDARD §5 visibility rules: F1, open screen, spectator mode, death screen.
        if (mc.options.hideGui) {
            return false;
        }
        if (mc.screen != null) {
            return false;
        }
        if (player.isSpectator()) {
            return false;
        }
        if (player.isDeadOrDying()) {
            return false;
        }
        return Prosperity.getConfig().client.enableTierHud;
    }

    // --- HUD coordination accessors (HUD-STANDARD §6) ---
    // Reflection targets of ProsperityAPI.isHudVisible()/getHudHeight(); keep the names and
    // (static, no-arg) signatures in sync with the api facade.

    /**
     * Whether the tier HUD badge is currently being drawn — config enabled and none of the §5
     * visibility rules hiding it. Client render state; call on the client only.
     */
    public static boolean isHudVisible() {
        return shouldRender(Minecraft.getInstance());
    }

    /**
     * This element's current height contribution in px (element + gap) for a lower-priority sibling
     * to offset past; 0 when not visible.
     */
    public static int getHudHeight() {
        return isHudVisible() ? HUD_HEIGHT_CONTRIBUTION : 0;
    }

    /**
     * A higher-priority sibling's HUD height, read through its HUD coordination accessors
     * (HUD-STANDARD §6) — hardcoded sibling heights go stale the moment the user disables or moves
     * the sibling's HUD. Falls back to the legacy fixed 22px reservation when the sibling is present
     * but predates the accessors, and to 0 when the sibling is absent entirely.
     */
    static final class SiblingOffset {
        /** Pre-accessor behavior: reserve a fixed slot strip whenever the sibling is loaded. */
        private static final int LEGACY_FIXED_OFFSET = 22;

        private final String modId;
        private final String apiClass;

        // Render-thread only, resolved once on the first render pass.
        private boolean resolveAttempted;
        private MethodHandle isHudVisibleHandle;
        private MethodHandle getHudHeightHandle;

        SiblingOffset(String modId, String apiClass) {
            this.modId = modId;
            this.apiClass = apiClass;
        }

        /** This sibling's current height contribution; queried per render pass (cheap client reads). */
        int current() {
            if (!FabricLoader.getInstance().isModLoaded(modId)) {
                return 0;
            }
            resolveOnce();
            if (isHudVisibleHandle == null || getHudHeightHandle == null) {
                return LEGACY_FIXED_OFFSET;
            }
            try {
                if (!(boolean) isHudVisibleHandle.invokeExact()) {
                    return 0;
                }
                return Math.max(0, (int) getHudHeightHandle.invokeExact());
            } catch (Throwable t) {
                // Accessor misbehaving — degrade to the legacy reservation rather than overlapping.
                return LEGACY_FIXED_OFFSET;
            }
        }

        private void resolveOnce() {
            if (resolveAttempted) {
                return;
            }
            resolveAttempted = true;
            try {
                Class<?> api = Class.forName(apiClass);
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                isHudVisibleHandle = lookup.findStatic(api, "isHudVisible", MethodType.methodType(boolean.class));
                getHudHeightHandle = lookup.findStatic(api, "getHudHeight", MethodType.methodType(int.class));
            } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
                // Older sibling without the coordination accessors.
                isHudVisibleHandle = null;
                getHudHeightHandle = null;
                Prosperity.LOGGER.info(
                        "{} present without HUD accessors; using the legacy fixed {}px HUD offset",
                        modId, LEGACY_FIXED_OFFSET);
            }
        }
    }
}
