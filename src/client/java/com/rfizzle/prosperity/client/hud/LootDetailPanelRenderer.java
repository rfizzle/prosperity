package com.rfizzle.prosperity.client.hud;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.client.ProsperityClient;
import com.rfizzle.prosperity.client.hud.LootDetailPanelMath.NearbyEntry;
import com.rfizzle.prosperity.client.hud.LootDetailPanelMath.NearbyGroup;
import com.rfizzle.prosperity.client.indicator.UnlootedIndicatorCache;
import com.rfizzle.prosperity.client.indicator.UnlootedMinecartIndicatorCache;
import com.rfizzle.prosperity.client.item.ProspectorsCompassClient;
import com.rfizzle.prosperity.client.network.ClientProsperityData;
import com.rfizzle.prosperity.item.ProsperityItems;
import com.rfizzle.prosperity.config.DistanceTier;
import com.rfizzle.prosperity.config.ProsperityConfig;
import com.rfizzle.prosperity.loot.LootScaling;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Hold-to-peek loot detail panel (S-035). While {@link ProsperityClient#KEY_PEEK_LOOT_DETAIL} is
 * held — and the tier badge's normal visibility rules pass — this overlays a framed panel with three
 * pillars: the player's current tier and progress to the next, the full configured tier ladder, and
 * the unlooted instanced containers physically around them.
 *
 * <p>Every figure derives from data already on the client — no new server payload. Pillars 1 and 2
 * resolve against {@link ClientProsperityData#config()} through {@link LootScaling#resolveTier}, the
 * same source the badge and {@code /prosperity info} use, so the panel can never disagree with server
 * generation. Pillar 3 reads the existing {@link UnlootedIndicatorCache} /
 * {@link UnlootedMinecartIndicatorCache}, resolved on a tick interval rather than per frame, so it
 * adds no scan. It deliberately does <em>not</em> enumerate possible or injected loot — that stays in
 * the EMI/REI/JEI loot index.
 *
 * <p>No {@code Screen} is opened: the panel never captures the mouse, pauses the game, or blocks
 * movement — it behaves like vanilla's hold-Tab player list. Because a non-focused HUD layer can't
 * scroll without capturing input, the nearby list keeps a comfortable fixed size: everything that
 * fits shows at once, and any overflow is paged with a cross-fade and page dots while the header,
 * progress, and ladder stay static.
 */
public final class LootDetailPanelRenderer implements HudRenderCallback {

    private static final ResourceLocation PANEL = Prosperity.id("textures/gui/loot_detail_panel.png");
    private static final ResourceLocation ICON = Prosperity.id("textures/gui/hud_icon.png");

    private static final int TEX_SIZE = 64;    // panel texture is 64x64
    private static final int SLICE = 12;       // 9-slice corner inset (holds the gold accents)
    private static final int ICON_TEX = 32;    // header icon texture is 32x32
    private static final int ICON_SIZE = 16;   // drawn header-icon size

    private static final int INSET = 14;       // content inset from the panel edge
    private static final int LINE_H = 10;
    private static final int SECTION_GAP = 5;
    private static final int ROW_INDENT = 6;
    private static final int BAR_H = 6;
    private static final int MIN_CONTENT_W = 200;
    private static final float MAX_SCREEN_FRACTION = 0.92f;

    /** A page of the nearby list is up to this many rows (also clamped by the screen-height budget). */
    private static final int PAGE_COMFORT_ROWS = 8;
    /** Time one page stays up, including its fade in/out (ms) — matches the sibling peek panels. */
    private static final long PAGE_HOLD_MS = 2600L;
    /** Cross-fade duration at each page boundary (ms) — matches the sibling peek panels. */
    private static final long FADE_MS = 350L;

    /** How often (in game ticks) the nearby-container scan refreshes its cache. */
    private static final int SCAN_INTERVAL_TICKS = 10;

    private static final int DOT_SIZE = 3;
    private static final int DOT_GAP = 3;

    private static final int COLOR_BONE = 0xFFE8E0D4;
    private static final int COLOR_ASH = 0xFFA89F93;
    private static final int COLOR_BAR_TRACK = 0xFF2E2510;  // Prosperity dark gold
    private static final int COLOR_DOT_OFF = 0xFF55504A;

    private static final String BULLET = "› ";  // ›

    /**
     * Cached nearby groups and their type-label components, refreshed at most every
     * {@link #SCAN_INTERVAL_TICKS} ticks and only while the panel is held open. Render-thread only.
     */
    private final List<NearbyGroup> nearbyGroups = new ArrayList<>();
    private final Map<String, Component> nearbyLabels = new HashMap<>();
    private long lastScanTick = Long.MIN_VALUE;

    /**
     * Nearest unlooted target resolved by the last scan — the "Nearest: 142 blocks NW" line (#62).
     * Null tier name means no line: no candidates, or the player carries no Prospector's Compass
     * (the compass is what grants the datum; without one only the grouped rows show).
     */
    private String nearestTierName;
    private String nearestBearing;
    private double nearestDistance;

    @Override
    public void onHudRender(GuiGraphics graphics, DeltaTracker delta) {
        // Same visibility rules as the badge (F1, open screen, spectator, death, HUD enabled), plus
        // the hold-to-peek gate. The keymapping defaults to Left Alt (S-082); a player who cleared the
        // binding leaves isDown() false and never sees the panel.
        if (!ProsperityHudOverlay.isHudVisible()) return;
        if (ProsperityClient.KEY_PEEK_LOOT_DETAIL == null || !ProsperityClient.KEY_PEEK_LOOT_DETAIL.isDown()) return;

        // First peek retires the one-time join-time discovery hint for good, persisted client-side (S-082).
        dismissPeekHintOnce();

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) return;
        Font font = mc.font;

        ProsperityConfig cfg = ClientProsperityData.config();
        boolean isEnd = level.dimension() == Level.END;
        double distance = Math.sqrt(player.getX() * player.getX() + player.getZ() * player.getZ());
        DistanceTier current = LootScaling.resolveTier(cfg, isEnd, player.getX(), player.getZ());
        int tierColor = HudMath.tierColor(current.name());

        List<DistanceTier> ladder = LootDetailPanelMath.sortedLadder(cfg);
        int currentIndex = LootDetailPanelMath.currentIndex(ladder, current);
        boolean maxTier = LootDetailPanelMath.isMaxTier(ladder, currentIndex);
        boolean endMax = isEnd && cfg.endAlwaysMaxTier;
        float fraction = LootDetailPanelMath.progressToNext(ladder, currentIndex, distance);
        double remaining = LootDetailPanelMath.distanceToNext(ladder, currentIndex, distance);

        // ---- Header / pillar-1 strings ----
        Component title = Component.translatable("hud.prosperity.loot_detail.title");
        Component currentName = tierName(current.name());
        Component distanceText = Component.translatable("hud.prosperity.loot_detail.distance", fmtInt(distance));

        DistanceTier next = maxTier || currentIndex < 0 ? null : ladder.get(currentIndex + 1);
        Component nextText;
        if (endMax) {
            nextText = Component.translatable("hud.prosperity.loot_detail.end_max");
        } else if (next == null) {
            nextText = Component.translatable("hud.prosperity.loot_detail.max");
        } else {
            nextText = Component.translatable("hud.prosperity.loot_detail.next",
                    tierName(next.name()), fmtInt(next.minDistance()));
        }

        int percent = Math.round(fraction * 100f);
        Component remainingText = next == null
                ? Component.translatable("hud.prosperity.loot_detail.at_peak")
                : Component.translatable("hud.prosperity.loot_detail.remaining", fmtInt(remaining), tierName(next.name()));
        Component percentText = Component.translatable("hud.prosperity.loot_detail.percent", percent);
        Component effectText = Component.translatable("hud.prosperity.loot_detail.effect",
                fmtMult(current.stackMultiplier()), current.qualityModifier());

        // ---- Pillar 2: full tier ladder (static block) ----
        Component ladderHeading = Component.translatable("hud.prosperity.loot_detail.ladder_heading");

        // ---- Pillar 3: nearby unlooted containers (the only paged section) ----
        refreshNearbyIfStale(mc, cfg, isEnd);
        List<NearbyGroup> groups = new ArrayList<>(nearbyGroups);
        boolean hasNearby = !groups.isEmpty();
        Component nearbyHeading = Component.translatable("hud.prosperity.loot_detail.nearby_heading");
        Component noNearby = Component.translatable("hud.prosperity.loot_detail.no_nearby");

        // Nearest-unlooted line (#62): distance + 8-way bearing to the compass's nearest target,
        // shown only while the player carries a Prospector's Compass and something is in range.
        boolean showNearest = hasNearby && nearestTierName != null;
        Component nearestText = showNearest
                ? Component.translatable("hud.prosperity.loot_detail.nearest", fmtInt(nearestDistance),
                        Component.translatable("hud.prosperity.bearing." + nearestBearing))
                : null;
        Component nearestTierText = showNearest
                ? Component.translatable("hud.prosperity.loot_detail.nearby_tier", tierName(nearestTierName))
                : null;

        // Fixed-height "chrome": everything above the paged nearby list.
        int ladderH = LINE_H + ladder.size() * LINE_H + SECTION_GAP;   // heading + one row per tier
        int chromeH = 2 * INSET
                + ICON_SIZE + SECTION_GAP                                  // header
                + 1 + SECTION_GAP                                          // divider
                + LINE_H + 3 + BAR_H + 3 + LINE_H + SECTION_GAP            // distance + bar + figures
                + 1 + SECTION_GAP                                          // divider
                + ladderH                                                  // tier ladder
                + 1 + SECTION_GAP                                          // divider
                + LINE_H                                                   // nearby heading
                + (showNearest ? LINE_H : 0);                              // nearest line

        // Paginate the nearby list to a comfortable, bounded height.
        int rowBudget = (int) (graphics.guiHeight() * MAX_SCREEN_FRACTION) - chromeH;
        int rowsPerPage = Math.max(1, Math.min(PAGE_COMFORT_ROWS, rowBudget / LINE_H));
        List<List<NearbyGroup>> pages = LootDetailPanelMath.paginate(groups, rowsPerPage);
        int numPages = pages.size();

        // Fixed nearby-body height across pages so cycling never resizes the panel.
        int bodyRows = hasNearby ? Math.min(groups.size(), rowsPerPage) : 1;

        // ---- Content width ----
        int dotsW = numPages > 1 ? numPages * DOT_SIZE + (numPages - 1) * DOT_GAP : 0;
        int headingRowW = font.width(nearbyHeading) + (dotsW > 0 ? 12 + dotsW : 0);
        int headerW = ICON_SIZE + 4 + font.width(title) + 12 + font.width(currentName);
        int distanceRowW = font.width(distanceText) + 12 + font.width(nextText);
        int figuresW = font.width(remainingText) + (next == null ? 0 : 6 + font.width(percentText))
                + 12 + font.width(effectText);
        int ladderW = font.width(ladderHeading);
        for (DistanceTier tier : ladder) {
            ladderW = Math.max(ladderW, ROW_INDENT + font.width(ladderName(tier)) + 12 + font.width(ladderFigures(tier)));
        }
        int nearbyW = hasNearby ? nearbyRowWidth(font, groups) : font.width(noNearby);
        int nearestW = showNearest ? font.width(nearestText) + 4 + font.width(nearestTierText) : 0;
        int contentW = max(MIN_CONTENT_W, headerW, distanceRowW, figuresW, ladderW, nearbyW, headingRowW, nearestW);

        int contentH = chromeH - 2 * INSET + bodyRows * LINE_H;
        int panelW = contentW + 2 * INSET;
        int panelH = contentH + 2 * INSET;
        int panelX = (graphics.guiWidth() - panelW) / 2;
        int panelY = (graphics.guiHeight() - panelH) / 2;

        // ---- Safety net: scale the whole panel down if it still wouldn't fit ----
        graphics.setColor(1f, 1f, 1f, 1f);
        float fitScale = Math.min(1f, Math.min(
                graphics.guiWidth() * MAX_SCREEN_FRACTION / panelW,
                graphics.guiHeight() * MAX_SCREEN_FRACTION / panelH));
        boolean scaled = fitScale < 1f;
        if (scaled) {
            float scx = graphics.guiWidth() / 2f;
            float scy = graphics.guiHeight() / 2f;
            graphics.pose().pushPose();
            graphics.pose().translate(scx, scy, 0f);
            graphics.pose().scale(fitScale, fitScale, 1f);
            graphics.pose().translate(-scx, -scy, 0f);
        }

        // ---- Frame + content ----
        drawNineSlice(graphics, panelX, panelY, panelW, panelH);

        int contentX = panelX + INSET;
        int y = panelY + INSET;

        // Header: tier-tinted chest + title, current tier right-aligned in tier color.
        blitTinted(graphics, contentX, y, tierColor);
        graphics.drawString(font, title, contentX + ICON_SIZE + 4, y + 4, COLOR_BONE, true);
        graphics.drawString(font, currentName, contentX + contentW - font.width(currentName), y + 4, tierColor, true);
        y += ICON_SIZE + SECTION_GAP;

        y = divider(graphics, contentX, y, contentW, tierColor);

        // Distance + next-tier row.
        graphics.drawString(font, distanceText, contentX, y, COLOR_BONE, true);
        graphics.drawString(font, nextText, contentX + contentW - font.width(nextText), y, COLOR_ASH, true);
        y += LINE_H + 3;

        // Progress bar: dark-gold track, tier-colored fill.
        graphics.fill(contentX, y, contentX + contentW, y + BAR_H, COLOR_BAR_TRACK);
        int filledW = Math.round(contentW * Math.max(0f, Math.min(1f, fraction)));
        if (filledW > 0) {
            graphics.fill(contentX, y, contentX + filledW, y + BAR_H, tierColor);
        }
        y += BAR_H + 3;

        // Progress figures + current-effect row.
        graphics.drawString(font, remainingText, contentX, y, COLOR_ASH, true);
        if (next != null) {
            graphics.drawString(font, percentText, contentX + font.width(remainingText) + 6, y, COLOR_ASH, true);
        }
        graphics.drawString(font, effectText, contentX + contentW - font.width(effectText), y, COLOR_BONE, true);
        y += LINE_H + SECTION_GAP;

        y = divider(graphics, contentX, y, contentW, tierColor);

        // Tier ladder (static): each tier in its color, current row marked, figures right-aligned.
        graphics.drawString(font, ladderHeading, contentX, y, COLOR_ASH, true);
        y += LINE_H;
        for (int i = 0; i < ladder.size(); i++) {
            DistanceTier tier = ladder.get(i);
            boolean isCurrent = i == currentIndex;
            Component name = ladderName(tier);
            Component figures = ladderFigures(tier);
            int nameColor = HudMath.tierColor(tier.name());
            if (isCurrent) {
                graphics.drawString(font, BULLET, contentX, y, nameColor, true);
            }
            graphics.drawString(font, name, contentX + ROW_INDENT, y, nameColor, true);
            graphics.drawString(font, figures, contentX + contentW - font.width(figures), y,
                    isCurrent ? COLOR_BONE : COLOR_ASH, true);
            y += LINE_H;
        }
        y += SECTION_GAP;

        y = divider(graphics, contentX, y, contentW, tierColor);

        // ---- Nearby list: static caption + dots, page cross-fading ----
        graphics.drawString(font, nearbyHeading, contentX, y, COLOR_ASH, true);
        if (!hasNearby) {
            y += LINE_H;
            graphics.drawString(font, noNearby, contentX, y, COLOR_ASH, true);
        } else {
            int page = 0;
            float bodyAlpha = 1f;
            if (numPages > 1) {
                long now = System.currentTimeMillis();
                page = LootDetailPanelMath.pageIndex(now, numPages, PAGE_HOLD_MS);
                bodyAlpha = LootDetailPanelMath.pageAlpha(Math.floorMod(now, PAGE_HOLD_MS), PAGE_HOLD_MS, FADE_MS);
                drawDots(graphics, contentX + contentW - dotsW, y + 2, numPages, page, tierColor);
            }
            y += LINE_H;

            // The nearest line is static chrome — it neither pages nor fades with the list below.
            if (showNearest) {
                graphics.drawString(font, nearestText, contentX, y, COLOR_BONE, true);
                graphics.drawString(font, nearestTierText, contentX + font.width(nearestText) + 4, y,
                        HudMath.tierColor(nearestTierName), true);
                y += LINE_H;
            }

            int labelColor = fade(COLOR_BONE, bodyAlpha);
            for (NearbyGroup group : pages.get(page)) {
                Component labelPart = nearbyLabelPart(group);
                Component tierPart = nearbyTierPart(group);
                graphics.drawString(font, labelPart, contentX, y, labelColor, true);
                graphics.drawString(font, tierPart, contentX + font.width(labelPart) + 4, y,
                        fade(HudMath.tierColor(group.tierName()), bodyAlpha), true);
                y += LINE_H;
            }
        }

        if (scaled) {
            graphics.pose().popPose();
        }
        graphics.setColor(1f, 1f, 1f, 1f);
    }

    /**
     * Retire the one-time peek discovery hint the first time the panel opens (S-082): flip the
     * client-side flag and persist it so the join-time chat hint never returns across sessions.
     * Idempotent — the guard keeps the per-frame render path from re-saving on every held frame, so
     * the synchronous {@code save()} on the render thread is a deliberate one-shot (one write per
     * install), not a per-frame cost.
     */
    private static void dismissPeekHintOnce() {
        ProsperityConfig.ClientConfig client = Prosperity.getConfig().client;
        if (!client.peekHintDismissed) {
            client.peekHintDismissed = true;
            Prosperity.getConfig().save();
        }
    }

    /**
     * Refresh {@link #nearbyGroups} if the cache is older than {@link #SCAN_INTERVAL_TICKS} ticks (or
     * the world's game time jumped backward, a world change). Reads the existing indicator caches and
     * resolves each container's type and per-position tier — no world scan of its own. Positions or
     * entities that no longer resolve on the client (unloaded, broken) are skipped.
     */
    private void refreshNearbyIfStale(Minecraft mc, ProsperityConfig cfg, boolean isEnd) {
        ClientLevel level = mc.level;
        if (level == null) return;

        long now = level.getGameTime();
        boolean fresh = lastScanTick != Long.MIN_VALUE && now >= lastScanTick && now - lastScanTick < SCAN_INTERVAL_TICKS;
        if (fresh) return;
        lastScanTick = now;

        List<NearbyEntry> entries = new ArrayList<>();
        List<BlockPos> candidates = new ArrayList<>();
        nearbyLabels.clear();

        for (Set<BlockPos> positions : UnlootedIndicatorCache.view().values()) {
            for (BlockPos pos : positions) {
                BlockState state = level.getBlockState(pos);
                if (state.isAir()) {
                    continue;
                }
                ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                String key = id.toString();
                nearbyLabels.putIfAbsent(key, state.getBlock().getName());
                entries.add(new NearbyEntry(key, tierNameAt(cfg, isEnd, pos.getX(), pos.getZ())));
                candidates.add(pos);
            }
        }

        for (int entityId : UnlootedMinecartIndicatorCache.view()) {
            Entity entity = level.getEntity(entityId);
            if (entity == null) {
                continue;
            }
            EntityType<?> type = entity.getType();
            String key = EntityType.getKey(type).toString();
            nearbyLabels.putIfAbsent(key, type.getDescription());
            entries.add(new NearbyEntry(key, tierNameAt(cfg, isEnd, entity.getX(), entity.getZ())));
            candidates.add(entity.blockPosition());
        }

        nearbyGroups.clear();
        nearbyGroups.addAll(LootDetailPanelMath.groupNearby(entries));

        refreshNearest(mc, cfg, isEnd, candidates);
    }

    /**
     * Resolve the nearest-unlooted line from the scan's {@code candidates}: the same plain-nearest
     * pick as the Prospector's Compass needle ({@link ProspectorsCompassClient#selectTarget}, no
     * sticky state), extended over loot minecarts so the line never contradicts the rows above it.
     * The line is compass-gated: it clears unless the player carries a Prospector's Compass.
     */
    private void refreshNearest(Minecraft mc, ProsperityConfig cfg, boolean isEnd, List<BlockPos> candidates) {
        nearestTierName = null;
        LocalPlayer player = mc.player;
        if (player == null || candidates.isEmpty()) {
            return;
        }
        if (!player.getInventory().hasAnyMatching(s -> s.is(ProsperityItems.PROSPECTORS_COMPASS))) {
            return;
        }
        BlockPos target = ProspectorsCompassClient.selectTarget(null, candidates, player.position());
        if (target == null) {
            return;
        }
        nearestDistance = Math.sqrt(target.distToCenterSqr(player.getX(), player.getY(), player.getZ()));
        nearestBearing = LootDetailPanelMath.bearing8(
                target.getX() + 0.5 - player.getX(), target.getZ() + 0.5 - player.getZ());
        nearestTierName = tierNameAt(cfg, isEnd, target.getX(), target.getZ());
    }

    private static String tierNameAt(ProsperityConfig cfg, boolean isEnd, double x, double z) {
        return LootScaling.resolveTier(cfg, isEnd, x, z).name();
    }

    private static Component tierName(String name) {
        return Component.translatableWithFallback("prosperity.tier." + name, HudMath.displayName(name));
    }

    private static Component ladderName(DistanceTier tier) {
        return tierName(tier.name());
    }

    private static Component ladderFigures(DistanceTier tier) {
        return Component.translatable("hud.prosperity.loot_detail.ladder_row",
                fmtInt(tier.minDistance()), fmtMult(tier.stackMultiplier()), tier.qualityModifier());
    }

    private int nearbyRowWidth(Font font, List<NearbyGroup> groups) {
        int w = 0;
        for (NearbyGroup group : groups) {
            w = Math.max(w, font.width(nearbyLabelPart(group)) + 4 + font.width(nearbyTierPart(group)));
        }
        return w;
    }

    /** The bullet + container-type name + count part of a nearby row, e.g. {@code "› Chest ×3"}. */
    private Component nearbyLabelPart(NearbyGroup group) {
        Component label = nearbyLabels.getOrDefault(group.typeKey(), Component.literal(group.typeKey()));
        return Component.literal(BULLET)
                .append(Component.translatable("hud.prosperity.loot_detail.nearby_row", label, group.count()));
    }

    /** The tier-colored suffix of a nearby row, e.g. {@code "— Wilderness"}. */
    private static Component nearbyTierPart(NearbyGroup group) {
        return Component.translatable("hud.prosperity.loot_detail.nearby_tier", tierName(group.tierName()));
    }

    /** A block distance as a grouped integer, e.g. {@code 4521.7 -> "4,521"} — matches {@code /prosperity info}. */
    private static String fmtInt(double blocks) {
        return String.format(Locale.US, "%,d", Math.round(blocks));
    }

    private static String fmtInt(int blocks) {
        return String.format(Locale.US, "%,d", blocks);
    }

    /** A stack multiplier in its natural decimal form, e.g. {@code 2.0 -> "2.0"} — matches {@code /prosperity info}. */
    private static String fmtMult(double stackMultiplier) {
        return Double.toString(stackMultiplier);
    }

    private static void drawDots(GuiGraphics g, int x, int y, int count, int active, int activeColor) {
        int dx = x;
        for (int i = 0; i < count; i++) {
            g.fill(dx, y, dx + DOT_SIZE, y + DOT_SIZE, i == active ? activeColor : COLOR_DOT_OFF);
            dx += DOT_SIZE + DOT_GAP;
        }
    }

    private int divider(GuiGraphics graphics, int x, int y, int width, int color) {
        graphics.fill(x, y, x + width, y + 1, withAlpha(color, 0xB0));
        return y + 1 + SECTION_GAP;
    }

    /** Draw the 64x64 panel texture as a 9-slice scaled to {@code w}x{@code h}. */
    private static void drawNineSlice(GuiGraphics g, int x, int y, int w, int h) {
        int s = SLICE;
        int center = TEX_SIZE - 2 * s;
        int innerW = w - 2 * s;
        int innerH = h - 2 * s;
        blit(g, x, y, s, s, 0, 0, s, s);
        blit(g, x + w - s, y, s, s, TEX_SIZE - s, 0, s, s);
        blit(g, x, y + h - s, s, s, 0, TEX_SIZE - s, s, s);
        blit(g, x + w - s, y + h - s, s, s, TEX_SIZE - s, TEX_SIZE - s, s, s);
        blit(g, x + s, y, innerW, s, s, 0, center, s);
        blit(g, x + s, y + h - s, innerW, s, s, TEX_SIZE - s, center, s);
        blit(g, x, y + s, s, innerH, 0, s, s, center);
        blit(g, x + w - s, y + s, s, innerH, TEX_SIZE - s, s, s, center);
        blit(g, x + s, y + s, innerW, innerH, s, s, center, center);
    }

    private static void blit(GuiGraphics g, int x, int y, int dw, int dh, int u, int v, int sw, int sh) {
        g.blit(PANEL, x, y, dw, dh, u, v, sw, sh, TEX_SIZE, TEX_SIZE);
    }

    private static void blitTinted(GuiGraphics g, int x, int y, int color) {
        float r = ((color >> 16) & 0xFF) / 255f;
        float gr = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;
        g.setColor(r, gr, b, 1f);
        g.blit(ICON, x, y, ICON_SIZE, ICON_SIZE, 0, 0, ICON_TEX, ICON_TEX, ICON_TEX, ICON_TEX);
        g.setColor(1f, 1f, 1f, 1f);
    }

    private static int withAlpha(int argb, int alpha) {
        return (alpha << 24) | (argb & 0xFFFFFF);
    }

    /** Scale a colour's existing alpha by {@code a} (0..1) — used for the body fade. */
    private static int fade(int color, float a) {
        int base = (color >>> 24) & 0xFF;
        int scaled = Math.round(base * Math.max(0f, Math.min(1f, a)));
        return (scaled << 24) | (color & 0xFFFFFF);
    }

    private static int max(int... values) {
        int m = Integer.MIN_VALUE;
        for (int v : values) {
            m = Math.max(m, v);
        }
        return m;
    }
}
