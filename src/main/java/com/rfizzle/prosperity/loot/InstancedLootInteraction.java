package com.rfizzle.prosperity.loot;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.attachment.InstancedLootData;
import com.rfizzle.prosperity.attachment.ProsperityAttachments;
import java.util.UUID;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
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
 * <p>Scope is single containers up to one chest's worth of slots. Double chests (S-007), the
 * unlooted-indicator packets (S-009), and the first-generation notification (S-021) land in their
 * own stories. The generation and persist steps are split out as plain static methods so gametests
 * can drive them without a live screen.
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
        // Defer double chests to S-007; instance only single containers for now.
        if (state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE)
                && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            return InteractionResult.PASS;
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

        boolean alreadyGenerated = existing != null && existing.isGenerated();
        ResourceKey<LootTable> tableKey =
                alreadyGenerated ? existing.getOriginalLootTable() : be.getLootTable();
        long seed = alreadyGenerated ? existing.getOriginalSeed() : be.getLootTableSeed();
        ResourceKey<LootTable> beTable = be.getLootTable();
        long beSeed = be.getLootTableSeed();

        NonNullList<ItemStack> generated =
                InstancedLootGenerator.generate(level, pos, tableKey, seed, player, be.getContainerSize());

        ProsperityAttachments.update(be, data -> {
            data.markGenerated(beTable, beSeed);
            data.setInventory(uuid, generated);
            data.setLastGeneratedTick(uuid, level.getGameTime());
        });
        // Sever the block entity's link to the global loot table now that the original is preserved
        // in the attachment. The unpack-safety mixin backstops any direct call that slips past this.
        be.setLootTable(null);
        be.setLootTableSeed(0L);
        be.setChanged();
        return generated;
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
