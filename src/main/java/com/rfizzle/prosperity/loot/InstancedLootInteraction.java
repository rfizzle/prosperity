package com.rfizzle.prosperity.loot;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.attachment.InstancedLootData;
import com.rfizzle.prosperity.attachment.ProsperityAttachments;
import java.util.UUID;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * Core instanced-loot loop (S-005). Intercepts right-clicks on naturally-generated containers,
 * serves each player a private inventory generated on first visit and retrieved thereafter, and
 * keeps the vanilla lid animation and open/close sounds in sync without touching the block entity's
 * own state.
 *
 * <p>On first generation the block entity's vanilla loot table is nulled and the
 * {@link com.rfizzle.prosperity.mixin.RandomizableContainerUnpackMixin unpack-safety mixin} blocks
 * any leftover unpack call, so hoppers and comparators cannot drain the global loot.
 *
 * <p>A double chest is served as one 54-slot instance (S-007): both halves' loot is generated and
 * stored on the primary half (the lexicographically smaller position, see {@link DoubleChestLayout}),
 * the secondary half holds only a redirect marker, and both halves' loot tables are nulled. The
 * unlooted-indicator packets (S-009) and the first-generation notification (S-021) land in their own
 * stories. The generation and persist steps are split out as plain static methods so gametests can
 * drive them without a live screen.
 */
public final class InstancedLootInteraction {

    private InstancedLootInteraction() {
    }

    public static void register() {
        UseBlockCallback.EVENT.register(InstancedLootInteraction::onUseBlock);
    }

    private static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand,
            BlockHitResult hit) {
        if (level.isClientSide || hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        if (!Prosperity.getConfig().enableInstancedLoot) {
            return InteractionResult.PASS;
        }
        // Sneaking with an item in hand means "place/use the item against the block" — let vanilla run.
        if (player.isSecondaryUseActive() && !player.getItemInHand(hand).isEmpty()) {
            return InteractionResult.PASS;
        }

        BlockPos pos = hit.getBlockPos();
        if (!(level.getBlockEntity(pos) instanceof RandomizableContainerBlockEntity container)) {
            return InteractionResult.PASS;
        }
        BlockState state = level.getBlockState(pos);
        // A double chest is served as one combined 54-slot instance from its primary half (S-007).
        if (state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE)
                && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            return serveDouble((ServerLevel) level, pos, container, state, serverPlayer);
        }
        int size = container.getContainerSize();
        if (size <= 0 || size > 54 || size % 9 != 0) {
            // Sizes that do not map to a chest menu (e.g. a 5-slot hopper) stay vanilla.
            return InteractionResult.PASS;
        }
        InstancedLootData data = ProsperityAttachments.get(container);
        if (!isLootContainer(container, data)) {
            // Player-placed storage (no loot table, never generated) stays shared and vanilla.
            // Gate on the loot table, not the attachment's presence: a never-opened loot chest has
            // no attachment yet, so gating on presence would silently leak first-open instancing.
            return InteractionResult.PASS;
        }

        serveInstance((ServerLevel) level, pos, container, serverPlayer);
        return InteractionResult.SUCCESS;
    }

    /** Whether the container carries (or has consumed) a loot table and so should be instanced. */
    public static boolean isLootContainer(RandomizableContainerBlockEntity container,
            @Nullable InstancedLootData data) {
        return container.getLootTable() != null || (data != null && data.isGenerated());
    }

    /**
     * Resolve the double chest's two halves and serve the combined instance. Falls through to vanilla
     * if the connected half is not a loaded chest block entity, or if neither half is a loot
     * container (a player-placed double chest stays shared).
     */
    private static InteractionResult serveDouble(ServerLevel level, BlockPos pos,
            RandomizableContainerBlockEntity clicked, BlockState state, ServerPlayer player) {
        Direction connected = ChestBlock.getConnectedDirection(state);
        BlockPos otherPos = pos.relative(connected);
        if (!(clicked instanceof ChestBlockEntity)
                || !(level.getBlockEntity(otherPos) instanceof ChestBlockEntity)) {
            return InteractionResult.PASS;
        }

        BlockPos primaryPos = DoubleChestLayout.primary(pos, otherPos);
        BlockPos secondaryPos = DoubleChestLayout.secondary(pos, otherPos);
        if (!(level.getBlockEntity(primaryPos) instanceof ChestBlockEntity primary)
                || !(level.getBlockEntity(secondaryPos) instanceof ChestBlockEntity secondary)) {
            return InteractionResult.PASS;
        }
        if (!isDoubleLootContainer(primary, secondary)) {
            return InteractionResult.PASS;
        }

        serveDoubleInstance(level, primaryPos, primary, secondaryPos, secondary, player);
        return InteractionResult.SUCCESS;
    }

    /** Whether either half of a double chest carries (or has consumed) a loot table. */
    private static boolean isDoubleLootContainer(ChestBlockEntity primary, ChestBlockEntity secondary) {
        return isLootContainer(primary, ProsperityAttachments.get(primary))
                || isLootContainer(secondary, ProsperityAttachments.get(secondary));
    }

    /**
     * Generate-or-retrieve the player's combined 54-slot inventory and open it as one chest screen.
     * The inventory lives on the primary half; both halves animate and a single open sound plays.
     */
    private static void serveDoubleInstance(ServerLevel level, BlockPos primaryPos,
            ChestBlockEntity primary, BlockPos secondaryPos, ChestBlockEntity secondary,
            ServerPlayer player) {
        UUID uuid = player.getUUID();
        NonNullList<ItemStack> stored =
                generateAndStoreDouble(level, primaryPos, primary, secondaryPos, secondary, player);

        SimpleContainer screenInventory = new SimpleContainer(DoubleChestLayout.TOTAL_SLOTS);
        for (int slot = 0; slot < DoubleChestLayout.TOTAL_SLOTS; slot++) {
            screenInventory.setItem(slot, stored.get(slot).copy());
        }

        Component title = Component.translatable("container.chestDouble");
        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, opener) -> InstancedLootMenu.create(syncId, inventory, screenInventory,
                        () -> onCloseDouble(level, primaryPos, secondaryPos, uuid, screenInventory)),
                title));
        playSound(level, primaryPos, SoundEvents.CHEST_OPEN);
        animate(level, primaryPos, primary, true);
        animate(level, secondaryPos, secondary, true);
    }

    /**
     * Roll each half's own loot table into its 27 slots (primary {@code 0..26}, secondary
     * {@code 27..53}), store the combined inventory on the primary, mark the secondary with a
     * redirect to the primary, and null both halves' loot tables (S-006). Returns the player's stored
     * inventory unchanged on a return visit.
     */
    public static NonNullList<ItemStack> generateAndStoreDouble(ServerLevel level, BlockPos primaryPos,
            ChestBlockEntity primary, BlockPos secondaryPos, ChestBlockEntity secondary,
            ServerPlayer player) {
        UUID uuid = player.getUUID();
        InstancedLootData primaryData = ProsperityAttachments.get(primary);
        if (primaryData != null) {
            NonNullList<ItemStack> stored = primaryData.getInventory(uuid);
            if (stored != null) {
                return stored;
            }
        }

        LootRef primaryRef = resolveTable(primary, primaryData);
        LootRef secondaryRef = resolveTable(secondary, ProsperityAttachments.get(secondary));
        NonNullList<ItemStack> primaryLoot = InstancedLootGenerator.generate(
                level, primaryPos, primaryRef.key(), primaryRef.seed(), player, DoubleChestLayout.PRIMARY_SLOTS);
        NonNullList<ItemStack> secondaryLoot = InstancedLootGenerator.generate(
                level, secondaryPos, secondaryRef.key(), secondaryRef.seed(), player, DoubleChestLayout.PRIMARY_SLOTS);

        NonNullList<ItemStack> combined =
                NonNullList.withSize(DoubleChestLayout.TOTAL_SLOTS, ItemStack.EMPTY);
        for (int slot = 0; slot < DoubleChestLayout.PRIMARY_SLOTS; slot++) {
            combined.set(slot, primaryLoot.get(slot));
            combined.set(DoubleChestLayout.PRIMARY_SLOTS + slot, secondaryLoot.get(slot));
        }

        ProsperityAttachments.update(primary, data -> {
            data.markGenerated(primaryRef.key(), primaryRef.seed());
            data.setInventory(uuid, combined);
            data.setLastGeneratedTick(uuid, level.getGameTime());
        });
        ProsperityAttachments.update(secondary, data -> {
            data.markGenerated(secondaryRef.key(), secondaryRef.seed());
            data.setRedirect(primaryPos);
        });
        nullify(primary);
        nullify(secondary);
        return combined;
    }

    /** Generate-or-retrieve the player's inventory, then open the matching vanilla chest screen. */
    public static void serveInstance(ServerLevel level, BlockPos pos,
            RandomizableContainerBlockEntity be, ServerPlayer player) {
        UUID uuid = player.getUUID();
        int size = be.getContainerSize();
        NonNullList<ItemStack> stored = generateAndStore(level, pos, be, player);

        SimpleContainer screenInventory = new SimpleContainer(size);
        for (int slot = 0; slot < size; slot++) {
            screenInventory.setItem(slot, stored.get(slot).copy());
        }

        Component title = be.getDisplayName();
        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, opener) -> InstancedLootMenu.create(syncId, inventory, screenInventory,
                        () -> onClose(level, pos, uuid, screenInventory)),
                title));
        playOpen(level, pos, be);
    }

    /**
     * Return the player's stored inventory, generating and persisting it on first visit. Records the
     * generation tick, preserves the original loot table/seed in the component, and nulls the vanilla
     * {@code lootTable}/{@code lootTableSeed} fields on the block entity so a hopper or comparator
     * cannot trigger vanilla's {@code unpackLootTable} and drain the global loot (S-006).
     */
    public static NonNullList<ItemStack> generateAndStore(ServerLevel level, BlockPos pos,
            RandomizableContainerBlockEntity be, ServerPlayer player) {
        UUID uuid = player.getUUID();
        InstancedLootData existing = ProsperityAttachments.get(be);
        if (existing != null) {
            NonNullList<ItemStack> stored = existing.getInventory(uuid);
            if (stored != null) {
                return stored;
            }
        }

        LootRef ref = resolveTable(be, existing);
        NonNullList<ItemStack> generated =
                InstancedLootGenerator.generate(level, pos, ref.key(), ref.seed(), player, be.getContainerSize());

        ProsperityAttachments.update(be, data -> {
            data.markGenerated(ref.key(), ref.seed());
            data.setInventory(uuid, generated);
            data.setLastGeneratedTick(uuid, level.getGameTime());
        });
        nullify(be);
        return generated;
    }

    /**
     * The loot table and seed to roll from for {@code be}: the original preserved in the attachment
     * once generation has happened (the live block-entity fields are nulled by then), otherwise the
     * block entity's own live fields on a first visit.
     */
    private static LootRef resolveTable(RandomizableContainerBlockEntity be,
            @Nullable InstancedLootData data) {
        if (data != null && data.isGenerated()) {
            return new LootRef(data.getOriginalLootTable(), data.getOriginalSeed());
        }
        return new LootRef(be.getLootTable(), be.getLootTableSeed());
    }

    private record LootRef(@Nullable ResourceKey<LootTable> key, long seed) {
    }

    /**
     * Sever a block entity's link to the global loot table once its original has been preserved in
     * the attachment. The unpack-safety mixin backstops any direct {@code unpackLootTable} call that
     * slips past this.
     */
    private static void nullify(RandomizableContainerBlockEntity be) {
        be.setLootTable(null);
        be.setLootTableSeed(0L);
        be.setChanged();
    }

    /** Write a screen inventory back to the player's attachment entry. */
    public static void persist(RandomizableContainerBlockEntity be, UUID uuid, Container container) {
        int size = container.getContainerSize();
        NonNullList<ItemStack> out = NonNullList.withSize(size, ItemStack.EMPTY);
        for (int slot = 0; slot < size; slot++) {
            out.set(slot, container.getItem(slot));
        }
        ProsperityAttachments.update(be, data -> data.setInventory(uuid, out));
    }

    private static void onClose(ServerLevel level, BlockPos pos, UUID uuid, Container screenInventory) {
        if (level.getBlockEntity(pos) instanceof RandomizableContainerBlockEntity be) {
            persist(be, uuid, screenInventory);
            playClose(level, pos, be);
        }
    }

    /** Persist the combined inventory back to the primary half and close-animate both halves. */
    private static void onCloseDouble(ServerLevel level, BlockPos primaryPos, BlockPos secondaryPos,
            UUID uuid, Container screenInventory) {
        if (level.getBlockEntity(primaryPos) instanceof RandomizableContainerBlockEntity primary) {
            persist(primary, uuid, screenInventory);
        }
        playSound(level, primaryPos, SoundEvents.CHEST_CLOSE);
        if (level.getBlockEntity(primaryPos) instanceof ChestBlockEntity primary) {
            animate(level, primaryPos, primary, false);
        }
        if (level.getBlockEntity(secondaryPos) instanceof ChestBlockEntity secondary) {
            animate(level, secondaryPos, secondary, false);
        }
    }

    // ---- lid animation + sound ----

    private static void playOpen(ServerLevel level, BlockPos pos, BlockEntity be) {
        playSound(level, pos, openSound(be));
        animate(level, pos, be, true);
    }

    private static void playClose(ServerLevel level, BlockPos pos, BlockEntity be) {
        playSound(level, pos, closeSound(be));
        animate(level, pos, be, false);
    }

    private static void playSound(ServerLevel level, BlockPos pos, SoundEvent sound) {
        float pitch = level.getRandom().nextFloat() * 0.1f + 0.9f;
        level.playSound(null, pos, sound, SoundSource.BLOCKS, 0.5f, pitch);
    }

    /**
     * Trigger the lid animation that vanilla would normally drive through its opener counter.
     * Chests and shulker boxes animate from a block event; barrels animate from the {@code OPEN}
     * blockstate. We never touch the block entity's opener counter, so nothing reverts this.
     */
    private static void animate(ServerLevel level, BlockPos pos, BlockEntity be, boolean open) {
        BlockState state = level.getBlockState(pos);
        if (be instanceof BarrelBlockEntity) {
            if (state.hasProperty(BarrelBlock.OPEN)) {
                level.setBlock(pos, state.setValue(BarrelBlock.OPEN, open), Block.UPDATE_ALL);
            }
        } else if (be instanceof ChestBlockEntity || be instanceof ShulkerBoxBlockEntity) {
            level.blockEvent(pos, state.getBlock(), 1, open ? 1 : 0);
        }
    }

    private static SoundEvent openSound(BlockEntity be) {
        if (be instanceof BarrelBlockEntity) {
            return SoundEvents.BARREL_OPEN;
        }
        if (be instanceof ShulkerBoxBlockEntity) {
            return SoundEvents.SHULKER_BOX_OPEN;
        }
        return SoundEvents.CHEST_OPEN;
    }

    private static SoundEvent closeSound(BlockEntity be) {
        if (be instanceof BarrelBlockEntity) {
            return SoundEvents.BARREL_CLOSE;
        }
        if (be instanceof ShulkerBoxBlockEntity) {
            return SoundEvents.SHULKER_BOX_CLOSE;
        }
        return SoundEvents.CHEST_CLOSE;
    }
}
