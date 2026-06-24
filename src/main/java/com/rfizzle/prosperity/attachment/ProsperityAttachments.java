package com.rfizzle.prosperity.attachment;

import com.rfizzle.prosperity.Prosperity;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.world.entity.vehicle.AbstractMinecartContainer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

/**
 * Registry for Prosperity's persistent data attachments and the single write choke point for them.
 *
 * <p>Per-player loot state lives in an {@link InstancedLootData} attachment on the vanilla loot
 * source. Persistence rides the block entity's own {@code createNbt}/{@code read} seam, so it
 * serializes with the chunk and is stripped from the client update tag for vanilla block entities —
 * client sync is via our own packets, not attachment sync. The attachment is latent: a
 * naturally-placed storage container has none until loot is generated, so it stays byte-identical to
 * vanilla on disk.
 *
 * <p>The attachment API is {@code @ApiStatus.Experimental}; the Fabric API version is pinned in
 * {@code gradle.properties} and must be re-checked on each MC/Fabric bump.
 */
public final class ProsperityAttachments {

    /**
     * Per-player instanced loot. Attached on demand to a loot container's block entity when a player
     * first opens it — never auto-attached, so interception must gate on the loot table, not on the
     * attachment's presence.
     */
    public static final AttachmentType<InstancedLootData> INSTANCED_LOOT =
            AttachmentRegistry.create(Prosperity.id("instanced_loot"), builder -> builder
                    .persistent(InstancedLootData.CODEC)
                    .initializer(InstancedLootData::new));

    /**
     * Per-player instanced loot for container minecarts (chest and hopper carts). The same
     * {@link InstancedLootData} state as {@link #INSTANCED_LOOT}, registered as a distinct
     * entity-targeted attachment so one state class and codec cover every loot-source shape. Attached
     * on demand when a player first opens the cart — interception gates on the loot table, not on the
     * attachment's presence.
     */
    public static final AttachmentType<InstancedLootData> INSTANCED_MINECART_LOOT =
            AttachmentRegistry.create(Prosperity.id("instanced_minecart_loot"), builder -> builder
                    .persistent(InstancedLootData.CODEC)
                    .initializer(InstancedLootData::new));

    private ProsperityAttachments() {
    }

    /** Force static initialization so the attachment type registers at mod init. */
    public static void init() {
    }

    /** The instanced-loot data on {@code be}, or {@code null} if none has been attached yet. */
    @Nullable
    public static InstancedLootData get(BlockEntity be) {
        return be.getAttached(INSTANCED_LOOT);
    }

    /**
     * Apply a mutation to the instanced-loot data on {@code be}, creating it if absent, and persist.
     * The single write choke point: in-place attachment mutation does not auto-mark the block entity
     * dirty, so this calls {@link BlockEntity#setChanged()} after the mutation. Returns the data.
     */
    public static InstancedLootData update(BlockEntity be, Consumer<InstancedLootData> mutation) {
        InstancedLootData data = be.getAttachedOrCreate(INSTANCED_LOOT);
        mutation.accept(data);
        be.setChanged();
        return data;
    }

    /** The instanced-loot data on minecart {@code cart}, or {@code null} if none has been attached yet. */
    @Nullable
    public static InstancedLootData get(AbstractMinecartContainer cart) {
        return cart.getAttached(INSTANCED_MINECART_LOOT);
    }

    /**
     * Apply a mutation to the instanced-loot data on minecart {@code cart}, creating it if absent.
     * Unlike block entities, an entity serializes with its chunk on every save (vanilla mutates a
     * minecart's contents in place the same way), so no explicit dirty call is needed.
     */
    public static InstancedLootData update(AbstractMinecartContainer cart,
            Consumer<InstancedLootData> mutation) {
        InstancedLootData data = cart.getAttachedOrCreate(INSTANCED_MINECART_LOOT);
        mutation.accept(data);
        return data;
    }
}
