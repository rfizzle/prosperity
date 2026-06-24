package com.rfizzle.prosperity.loot;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.attachment.InstancedLootData;
import com.rfizzle.prosperity.attachment.ProsperityAttachments;
import com.rfizzle.prosperity.network.ProsperityNetworking;
import java.util.UUID;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
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
 * <p>The single-source loop runs against a {@link ContainerAdapter}, so the same generate-or-retrieve,
 * nullify, serve, and persist code drives both block-entity containers (here) and container minecarts
 * ({@link MinecartLootInteraction}). On first generation the source's vanilla loot table is nulled and
 * the unpack-safety mixins block any leftover unpack call, so hoppers and comparators cannot drain the
 * global loot (S-006).
 *
 * <p>A double chest is served as one 54-slot instance (S-007): both halves' loot is generated and
 * stored on the primary half (the lexicographically smaller position, see {@link DoubleChestLayout}),
 * the secondary half holds only a redirect marker, and both halves' loot tables are nulled. The
 * generation and persist steps are split out as plain static methods so gametests can drive them
 * without a live screen.
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
        // Automation mods open containers through a fake player; pass them through to vanilla before
        // any loot logic so they never generate, nullify, or write an instance (S-034).
        if (FakePlayers.isFakePlayer(serverPlayer)) {
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
            // Sizes that do not map to a chest menu (e.g. a 5-slot block hopper) stay vanilla.
            return InteractionResult.PASS;
        }
        BlockEntityContainerAdapter adapter =
                new BlockEntityContainerAdapter((ServerLevel) level, pos, container);
        if (!adapter.isLootContainer()) {
            // Player-placed storage (no loot table, never generated) stays shared and vanilla.
            // Gate on the loot table, not the attachment's presence: a never-opened loot chest has
            // no attachment yet, so gating on presence would silently leak first-open instancing.
            return InteractionResult.PASS;
        }

        serveInstance(adapter, serverPlayer);
        return InteractionResult.SUCCESS;
    }

    /** Whether the container carries (or has consumed) a loot table and so should be instanced. */
    public static boolean isLootContainer(RandomizableContainerBlockEntity container,
            @Nullable InstancedLootData data) {
        return container.getLootTable() != null || (data != null && data.isGenerated());
    }

    /**
     * Called from the block-entity removal mixin (S-008) for every destroyed block entity. When the
     * removed entity was an instanced loot container, tell tracking clients to drop its unlooted
     * indicator. Fires for any destruction cause (break, explosion, {@code /setblock}, piston) but
     * never on chunk unload — the mixin targets {@code LevelChunk#removeBlockEntity}, which the unload
     * path bypasses. The per-player attachment lives in the block entity's own NBT and dies with it,
     * so no separate server-side state needs cleaning here.
     *
     * <p>Breaking one half of a double chest removes only that half's indicator; the surviving half
     * stays consistent through the single-container open path (its stored inventory is served or
     * regenerated as a single chest, and a now-dangling redirect is never read on that path).
     *
     * <p>Chest/hopper minecarts are entities (no block-entity removal) and brushable blocks are not
     * {@code RandomizableContainerBlockEntity}, so both fall outside this gate; their indicator
     * cleanup lands with the visual-indicator system (E-003).
     */
    public static void onContainerRemoved(ServerLevel level, BlockPos pos, BlockEntity be) {
        if (be instanceof RandomizableContainerBlockEntity container
                && isLootContainer(container, ProsperityAttachments.get(container))) {
            ProsperityNetworking.sendContainerRemoved(level, pos);
        }
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
                (syncId, inventory, opener) -> InstancedLootMenu.createFor(syncId, inventory, screenInventory,
                        () -> onCloseDouble(level, primaryPos, secondaryPos, uuid, screenInventory)),
                title));
        ContainerFeedback.playSound(level, primaryPos, SoundEvents.CHEST_OPEN);
        ContainerFeedback.animate(level, primaryPos, primary, true);
        ContainerFeedback.animate(level, secondaryPos, secondary, true);
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

        ContainerAdapter primaryAdapter = new BlockEntityContainerAdapter(level, primaryPos, primary);
        ContainerAdapter secondaryAdapter = new BlockEntityContainerAdapter(level, secondaryPos, secondary);
        LootRef primaryRef = resolveTable(primaryAdapter);
        LootRef secondaryRef = resolveTable(secondaryAdapter);
        NonNullList<ItemStack> primaryLoot = InstancedLootGenerator.generate(
                level, primaryAdapter.origin(), primaryRef.key(), primaryRef.seed(), player,
                DoubleChestLayout.PRIMARY_SLOTS);
        NonNullList<ItemStack> secondaryLoot = InstancedLootGenerator.generate(
                level, secondaryAdapter.origin(), secondaryRef.key(), secondaryRef.seed(), player,
                DoubleChestLayout.PRIMARY_SLOTS);

        NonNullList<ItemStack> combined =
                NonNullList.withSize(DoubleChestLayout.TOTAL_SLOTS, ItemStack.EMPTY);
        for (int slot = 0; slot < DoubleChestLayout.PRIMARY_SLOTS; slot++) {
            combined.set(slot, primaryLoot.get(slot));
            combined.set(DoubleChestLayout.PRIMARY_SLOTS + slot, secondaryLoot.get(slot));
        }

        primaryAdapter.update(data -> {
            data.markGenerated(primaryRef.key(), primaryRef.seed());
            data.setInventory(uuid, combined);
            data.setLastGeneratedTick(uuid, level.getGameTime());
        });
        secondaryAdapter.update(data -> {
            data.markGenerated(secondaryRef.key(), secondaryRef.seed());
            data.setRedirect(primaryPos);
        });
        primaryAdapter.clearLootTable();
        secondaryAdapter.clearLootTable();
        // The double chest's single indicator is anchored at the primary half (the scan emits only
        // there), so drop that one for this player on first generation.
        primaryAdapter.notifyGenerated(player);
        return combined;
    }

    /** Generate-or-retrieve the player's inventory, then open the matching vanilla screen. */
    public static void serveInstance(ContainerAdapter adapter, ServerPlayer player) {
        UUID uuid = player.getUUID();
        int size = adapter.size();
        NonNullList<ItemStack> stored = generateAndStore(adapter, player);

        SimpleContainer screenInventory = new SimpleContainer(size);
        for (int slot = 0; slot < size; slot++) {
            screenInventory.setItem(slot, stored.get(slot).copy());
        }

        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, opener) -> InstancedLootMenu.createFor(syncId, inventory, screenInventory,
                        () -> {
                            adapter.persist(uuid, screenInventory);
                            adapter.closeFeedback();
                        }),
                adapter.displayName()));
        adapter.openFeedback();
    }

    /**
     * Return the player's stored inventory, generating and persisting it on first visit. Records the
     * generation tick, preserves the original loot table/seed in the attachment, and nulls the vanilla
     * loot table on the source so a hopper or comparator cannot trigger vanilla's unpack and drain the
     * global loot (S-006).
     */
    public static NonNullList<ItemStack> generateAndStore(ContainerAdapter adapter, ServerPlayer player) {
        UUID uuid = player.getUUID();
        InstancedLootData existing = adapter.data();
        if (existing != null) {
            NonNullList<ItemStack> stored = existing.getInventory(uuid);
            if (stored != null) {
                return stored;
            }
        }

        LootRef ref = resolveTable(adapter);
        NonNullList<ItemStack> generated = InstancedLootGenerator.generate(
                adapter.level(), adapter.origin(), ref.key(), ref.seed(), player, adapter.size());

        adapter.update(data -> {
            data.markGenerated(ref.key(), ref.seed());
            data.setInventory(uuid, generated);
            data.setLastGeneratedTick(uuid, adapter.level().getGameTime());
        });
        adapter.clearLootTable();
        // First generation only (return visits returned above): drop this player's unlooted indicator.
        adapter.notifyGenerated(player);
        return generated;
    }

    /**
     * The loot table and seed to roll from for {@code adapter}: the original preserved in the
     * attachment once generation has happened (the live source fields are nulled by then), otherwise
     * the source's own live fields on a first visit.
     */
    private static LootRef resolveTable(ContainerAdapter adapter) {
        InstancedLootData data = adapter.data();
        if (data != null && data.isGenerated()) {
            return new LootRef(data.getOriginalLootTable(), data.getOriginalSeed());
        }
        return new LootRef(adapter.lootTable(), adapter.lootTableSeed());
    }

    private record LootRef(@Nullable ResourceKey<LootTable> key, long seed) {
    }

    /** Write a screen inventory back to the player's attachment entry on a block-entity container. */
    public static void persist(RandomizableContainerBlockEntity be, UUID uuid, Container container) {
        int size = container.getContainerSize();
        NonNullList<ItemStack> out = NonNullList.withSize(size, ItemStack.EMPTY);
        for (int slot = 0; slot < size; slot++) {
            out.set(slot, container.getItem(slot));
        }
        ProsperityAttachments.update(be, data -> data.setInventory(uuid, out));
    }

    /** Persist the combined inventory back to the primary half and close-animate both halves. */
    private static void onCloseDouble(ServerLevel level, BlockPos primaryPos, BlockPos secondaryPos,
            UUID uuid, Container screenInventory) {
        if (level.getBlockEntity(primaryPos) instanceof RandomizableContainerBlockEntity primary) {
            persist(primary, uuid, screenInventory);
        }
        ContainerFeedback.playSound(level, primaryPos, SoundEvents.CHEST_CLOSE);
        if (level.getBlockEntity(primaryPos) instanceof ChestBlockEntity primary) {
            ContainerFeedback.animate(level, primaryPos, primary, false);
        }
        if (level.getBlockEntity(secondaryPos) instanceof ChestBlockEntity secondary) {
            ContainerFeedback.animate(level, secondaryPos, secondary, false);
        }
    }
}
