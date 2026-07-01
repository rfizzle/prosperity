package com.rfizzle.prosperity.compat;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.attachment.InstancedLootData;
import com.rfizzle.prosperity.attachment.ProsperityAttachments;
import com.rfizzle.prosperity.config.DistanceTier;
import com.rfizzle.prosperity.config.ProsperityConfig;
import com.rfizzle.prosperity.config.StructureOverride;
import com.rfizzle.prosperity.loot.InstancedLootInteraction;
import com.rfizzle.prosperity.loot.LootNotification;
import com.rfizzle.prosperity.loot.LootRefresh;
import com.rfizzle.prosperity.loot.LootScaling;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.Vec3;

/**
 * The viewer-agnostic data layer behind the container probe tooltip (SPEC §10), shared by every
 * probe integration (Jade, WTHIT) so they show identical lines. Has <em>no</em> probe-mod imports: a
 * server-side {@link #writeServerData} that packs the per-look state into an NBT tag, and a
 * client-side {@link #buildLines} that turns that tag into ready-to-render tooltip components. Keeping
 * the probe API out of this class is what makes the integrations degrade gracefully when their mod is
 * absent &mdash; only the thin {@code compat.jade} / {@code compat.wthit} adapters touch those types.
 *
 * <p>Status is resolved for the <em>looking</em> player. The distance tier is resolved live at the
 * container position (matching {@code /prosperity info}), with any structure override applied.
 */
public final class LootTooltip {

    /** Loot status for the looking player, in tooltip color order (SPEC §10). */
    public enum Status {
        /** Never opened by this player. Gold. */
        UNLOOTED,
        /** Opened and generated; loot is current. Gray. */
        LOOTED,
        /** This player's loot has passed its refresh cooldown; new loot awaits. Green. */
        REFRESHED,
        /** Container's loot table is blacklisted; vanilla behavior applies. White. */
        VANILLA;

        private static final Status[] VALUES = values();

        static Status byOrdinal(int ordinal) {
            return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : UNLOOTED;
        }
    }

    // Namespaced NBT keys for the per-look server data.
    static final String KEY_PRESENT = "prosperity:present";
    static final String KEY_STATUS = "prosperity:status";
    static final String KEY_TIER_SHOWN = "prosperity:tierShown";
    static final String KEY_TIER_NAME = "prosperity:tierName";
    static final String KEY_TIER_MULT = "prosperity:tierMult";
    static final String KEY_OVERRIDE_SHOWN = "prosperity:overrideShown";
    static final String KEY_OVERRIDE_STRUCTURE = "prosperity:overrideStructure";
    static final String KEY_OVERRIDE_MODE = "prosperity:overrideMode";
    static final String KEY_OVERRIDE_TIER = "prosperity:overrideTier";
    static final String KEY_REFRESH_SHOWN = "prosperity:refreshShown";
    static final String KEY_REFRESH_TICKS = "prosperity:refreshTicks";

    private LootTooltip() {
    }

    /**
     * The loot status from the three independent facts the server resolves. Pure (no game state) so the
     * full truth table is unit-tested. Blacklist wins over everything; an expired instance reads as
     * refreshed; a current instance as looted; otherwise unlooted.
     */
    public static Status statusOf(boolean blacklisted, boolean generated, boolean expired) {
        if (blacklisted) {
            return Status.VANILLA;
        }
        if (expired) {
            return Status.REFRESHED;
        }
        if (generated) {
            return Status.LOOTED;
        }
        return Status.UNLOOTED;
    }

    /**
     * Pack the per-look tooltip state for {@code player} looking at the container at {@code pos} into
     * {@code tag}. Writes nothing (leaving {@link #KEY_PRESENT} absent, so the tooltip is empty) when
     * instanced loot is disabled, the block entity is not a randomizable container, or it is plain
     * storage that never had a loot table. Takes the resolved game objects rather than a probe accessor
     * so it is callable headlessly from a gametest.
     */
    public static void writeServerData(CompoundTag tag, ServerLevel level, Player player, BlockPos pos,
            BlockEntity be) {
        ProsperityConfig cfg = Prosperity.getConfig();
        if (!cfg.enableInstancedLoot) {
            return;
        }
        if (!(be instanceof RandomizableContainerBlockEntity container)) {
            return;
        }
        InstancedLootData data = ProsperityAttachments.get(container);
        if (!InstancedLootInteraction.isLootContainer(container, data)) {
            return;
        }

        UUID uuid = player.getUUID();
        ResourceKey<LootTable> liveTable = container.getLootTable();
        boolean blacklisted = InstancedLootInteraction.isBlacklisted(liveTable);
        boolean generated = data != null && data.hasGenerated(uuid);
        boolean expired = generated && LootRefresh.isExpired(data, uuid, level.getGameTime());
        Status status = statusOf(blacklisted, generated, expired);

        tag.putBoolean(KEY_PRESENT, true);
        tag.putInt(KEY_STATUS, status.ordinal());

        // A blacklisted container shows only the status line — no scaling applies to vanilla loot.
        if (status == Status.VANILLA) {
            return;
        }

        if (cfg.enableDistanceScaling) {
            LootScaling.ScaledTier scaled = LootScaling.resolveForGeneration(level, Vec3.atCenterOf(pos));
            DistanceTier tier = scaled.tier();
            tag.putBoolean(KEY_TIER_SHOWN, true);
            tag.putString(KEY_TIER_NAME, tier.name());
            tag.putDouble(KEY_TIER_MULT, tier.stackMultiplier());

            ResourceLocation structure = scaled.structure();
            if (structure != null) {
                writeOverride(tag, cfg, structure);
            }
        }

        // Only a running countdown — an expired instance already reads "Refreshed", with no timer.
        if (cfg.enableLootRefresh && status == Status.LOOTED) {
            long remaining = LootRefresh.remainingTicks(data.getLastGeneratedTick(uuid),
                    level.getGameTime(), cfg.lootRefreshDays);
            tag.putBoolean(KEY_REFRESH_SHOWN, true);
            tag.putLong(KEY_REFRESH_TICKS, remaining);
        }
    }

    private static void writeOverride(CompoundTag tag, ProsperityConfig cfg, ResourceLocation structure) {
        StructureOverride override = findOverride(cfg, structure.toString());
        if (override == null || !isRecognizedMode(override.mode())) {
            return;
        }
        DistanceTier overrideTier = LootScaling.tierByName(cfg, override.tier());
        if (overrideTier == null) {
            return;
        }
        tag.putBoolean(KEY_OVERRIDE_SHOWN, true);
        tag.putString(KEY_OVERRIDE_STRUCTURE, structure.toString());
        tag.putString(KEY_OVERRIDE_MODE, override.mode());
        tag.putString(KEY_OVERRIDE_TIER, overrideTier.name());
    }

    private static StructureOverride findOverride(ProsperityConfig cfg, String structureId) {
        if (cfg.structureOverrides == null) {
            return null;
        }
        for (StructureOverride candidate : cfg.structureOverrides) {
            if (candidate != null && structureId.equals(candidate.structure())) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isRecognizedMode(String mode) {
        return "fixed".equals(mode) || "minimum".equals(mode) || "maximum".equals(mode);
    }

    /**
     * The status the tag was written with, or {@code null} when it carries no Prosperity data (plain
     * storage, or a block the probe queried that this provider never wrote to). A public read-back over
     * the same server-data tag {@link #buildLines} consumes.
     */
    public static Status readStatus(CompoundTag tag) {
        if (!tag.getBoolean(KEY_PRESENT)) {
            return null;
        }
        return Status.byOrdinal(tag.getInt(KEY_STATUS));
    }

    /**
     * Turn a tag written by {@link #writeServerData} into the tooltip lines, in SPEC §10 order with
     * their colors. An empty list when no Prosperity data is present (plain storage, or a non-container
     * block the probe also queried), so the component provider simply adds nothing.
     */
    public static List<Component> buildLines(CompoundTag tag) {
        List<Component> lines = new ArrayList<>();
        if (!tag.getBoolean(KEY_PRESENT)) {
            return lines;
        }
        Status status = Status.byOrdinal(tag.getInt(KEY_STATUS));
        lines.add(statusLine(status));
        if (status == Status.VANILLA) {
            return lines;
        }
        if (tag.getBoolean(KEY_TIER_SHOWN)) {
            lines.add(Component.translatable("prosperity.jade.tier",
                    tierName(tag.getString(KEY_TIER_NAME)),
                    formatMultiplier(tag.getDouble(KEY_TIER_MULT))));
        }
        if (tag.getBoolean(KEY_OVERRIDE_SHOWN)) {
            lines.add(Component.translatable(overrideKey(tag.getString(KEY_OVERRIDE_MODE)),
                    structureName(tag.getString(KEY_OVERRIDE_STRUCTURE)),
                    tierName(tag.getString(KEY_OVERRIDE_TIER))));
        }
        if (tag.getBoolean(KEY_REFRESH_SHOWN)) {
            lines.add(Component.translatable("prosperity.jade.refresh",
                    RefreshTimerFormat.format(tag.getLong(KEY_REFRESH_TICKS))));
        }
        return lines;
    }

    private static Component statusLine(Status status) {
        return switch (status) {
            case UNLOOTED -> Component.translatable("prosperity.jade.status.unlooted")
                    .withStyle(ChatFormatting.GOLD);
            case LOOTED -> Component.translatable("prosperity.jade.status.looted")
                    .withStyle(ChatFormatting.GRAY);
            case REFRESHED -> Component.translatable("prosperity.jade.status.refreshed")
                    .withStyle(ChatFormatting.GREEN);
            case VANILLA -> Component.translatable("prosperity.jade.status.vanilla")
                    .withStyle(ChatFormatting.WHITE);
        };
    }

    private static String overrideKey(String mode) {
        return switch (mode) {
            case "fixed" -> "prosperity.jade.override.fixed";
            case "maximum" -> "prosperity.jade.override.maximum";
            default -> "prosperity.jade.override.minimum";
        };
    }

    /** A stack multiplier in its natural decimal form, e.g. {@code 2.0 -> "2.0"} (mirrors §15 feedback). */
    private static String formatMultiplier(double stackMultiplier) {
        return Double.toString(stackMultiplier);
    }

    /**
     * The localized tier name, reusing the {@code prosperity.tier.<name>} keys (and capitalized
     * fallback) shared with {@code /prosperity info} and the loot notification, so a custom tier still
     * renders a readable name.
     */
    private static Component tierName(String name) {
        return Component.translatableWithFallback("prosperity.tier." + name, capitalize(name));
    }

    /**
     * The localized structure name, reusing the {@code prosperity.structure.<path>} keys (and humanized
     * fallback) shared with the loot notification.
     */
    private static Component structureName(String structureId) {
        String path = structureId;
        int colon = structureId.indexOf(':');
        if (colon >= 0) {
            path = structureId.substring(colon + 1);
        }
        return Component.translatableWithFallback("prosperity.structure." + path,
                LootNotification.humanize(path));
    }

    /** First letter upper-cased, e.g. {@code "wilderness" -> "Wilderness"}. */
    private static String capitalize(String name) {
        if (name == null || name.isEmpty()) {
            return name == null ? "" : name;
        }
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
