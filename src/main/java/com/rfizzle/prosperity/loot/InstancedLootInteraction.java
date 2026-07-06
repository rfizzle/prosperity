package com.rfizzle.prosperity.loot;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.advancement.ProsperityCriteria;
import com.rfizzle.prosperity.api.LootModifierContext;
import com.rfizzle.prosperity.attachment.InstancedLootData;
import com.rfizzle.prosperity.attachment.LootStatsData;
import com.rfizzle.prosperity.attachment.ProsperityAttachments;
import com.rfizzle.prosperity.config.DistanceTier;
import com.rfizzle.prosperity.loot.completion.StructureCompletion;
import com.rfizzle.prosperity.loot.eviction.AbsentPlayerEviction;
import com.rfizzle.prosperity.loot.injection.LootInjectionManager;
import com.rfizzle.prosperity.network.ProsperityNetworking;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
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
import net.minecraft.world.phys.Vec3;
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
        if (size != 5 && (size <= 0 || size > 54 || size % 9 != 0)) {
            // Require a chest-menu size (a positive multiple of nine up to 54) or the 5-slot block
            // hopper, which is served through a hopper menu; any other size stays vanilla.
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
        if (isBlacklisted(container.getLootTable())) {
            // Blacklisted loot tables open with full vanilla behavior (SPEC §7).
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
     * Whether {@code table} is excluded from all Prosperity behavior by the configured blacklist (SPEC §7).
     * Checked against the source's <em>live</em> loot table: a fresh blacklisted container keeps its table
     * (so it is never instanced, never nulled, and stays vanilla), while an already-generated container has
     * a null live table and is served normally — instancing cannot be undone after the fact.
     */
    public static boolean isBlacklisted(@Nullable ResourceKey<LootTable> table) {
        return table != null && Prosperity.getConfig().blacklist().matches(table.location());
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
                && isLootContainer(container, ProsperityAttachments.get(container))
                && !isBlacklisted(container.getLootTable())) {
            // A blacklisted container never showed an indicator, so it has none to drop.
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
        if (isBlacklisted(primary.getLootTable()) || isBlacklisted(secondary.getLootTable())) {
            // Either half blacklisted → the whole double opens vanilla (SPEC §7).
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
        // Keep the leave-grace memory fresh with the player's live group on this real open.
        PartyLootKeys.stampGrace(player);
        // The combined instance lives on the primary half, so resolve and lock against it (issue #53).
        // Resolve exactly once here and thread the key into generation, the lock, and the close-time
        // persist, so the three can never diverge (e.g. a non-deterministic API provider returning a
        // different key on a second call would otherwise store under one key and persist under another).
        UUID lootKey = PartyLootKeys.resolve(player, ProsperityAttachments.get(primary));
        boolean shared = !lootKey.equals(player.getUUID());
        String lockId = blockContainerId(level, primaryPos);
        if (shared && !SharedInstanceLocks.tryAcquire(lootKey, lockId)) {
            PartyLootKeys.refuseInUse(player, level, primaryPos.getCenter());
            return;
        }
        boolean lockHandedOff = false;
        try {
            NonNullList<ItemStack> stored =
                    generateAndStoreDouble(level, primaryPos, primary, secondaryPos, secondary, player, lootKey);

            SimpleContainer screenInventory = new SimpleContainer(DoubleChestLayout.TOTAL_SLOTS);
            for (int slot = 0; slot < DoubleChestLayout.TOTAL_SLOTS; slot++) {
                screenInventory.setItem(slot, stored.get(slot).copy());
            }

            Component title = Component.translatable("container.chestDouble");
            OptionalInt opened = player.openMenu(new SimpleMenuProvider(
                    (syncId, inventory, opener) -> InstancedLootMenu.createFor(syncId, inventory, screenInventory,
                            () -> {
                                onCloseDouble(level, primaryPos, secondaryPos, lootKey, screenInventory);
                                if (shared) {
                                    SharedInstanceLocks.release(lootKey, lockId);
                                }
                            }),
                    title));
            lockHandedOff = opened.isPresent();
            ContainerFeedback.playSound(level, primaryPos, SoundEvents.CHEST_OPEN);
            ContainerFeedback.animate(level, primaryPos, primary, true);
            ContainerFeedback.animate(level, secondaryPos, secondary, true);
        } finally {
            if (shared && !lockHandedOff) {
                SharedInstanceLocks.release(lootKey, lockId);
            }
        }
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
        return generateAndStoreDouble(level, primaryPos, primary, secondaryPos, secondary, player,
                PartyLootKeys.resolve(player, ProsperityAttachments.get(primary)));
    }

    /**
     * {@link #generateAndStoreDouble} keyed on an already-resolved {@code lootKey}, so the serve path can
     * resolve once and share the same key with the lock and the close-time persist (issue #53). The
     * combined instance lives on the primary half, so all state is read/written under {@code lootKey}
     * there.
     */
    public static NonNullList<ItemStack> generateAndStoreDouble(ServerLevel level, BlockPos primaryPos,
            ChestBlockEntity primary, BlockPos secondaryPos, ChestBlockEntity secondary,
            ServerPlayer player, UUID lootKey) {
        ContainerAdapter primaryAdapter = new BlockEntityContainerAdapter(level, primaryPos, primary);
        ContainerAdapter secondaryAdapter = new BlockEntityContainerAdapter(level, secondaryPos, secondary);
        boolean shared = !lootKey.equals(player.getUUID());
        recordMember(primaryAdapter, player, lootKey, shared);
        // Opportunistic absent-player eviction (issue #43) before any state is read: the combined
        // instance lives on the primary half, but the secondary can carry residual entries of its own
        // (e.g. from its earlier life as a single chest).
        AbsentPlayerEviction.prune(primaryAdapter);
        AbsentPlayerEviction.prune(secondaryAdapter);
        InstancedLootData primaryData = ProsperityAttachments.get(primary);
        if (primaryData != null && primaryData.hasGenerated(lootKey)) {
            if (!LootRefresh.isExpired(primaryData, lootKey, level.getGameTime())) {
                // Return visit: serve the stored combined inventory, or an empty 54-slot one when it
                // was evicted after being looted clean — never re-roll fresh loot (S-016).
                NonNullList<ItemStack> stored = primaryData.getInventory(lootKey);
                return stored != null ? stored
                        : NonNullList.withSize(DoubleChestLayout.TOTAL_SLOTS, ItemStack.EMPTY);
            }
            // Cooldown elapsed: clear the combined instance from the primary half, where the inventory
            // and tick both live, so it re-rolls below (S-016).
            ProsperityAttachments.update(primary, data -> data.clearForPlayer(lootKey));
        }

        LootRef primaryRef = resolveTable(primaryAdapter);
        LootRef secondaryRef = resolveTable(secondaryAdapter);
        // The combined instance and its refresh count both live on the primary half, so the salt for
        // both halves is read there (after any cooldown clear above has advanced it).
        long salt = refreshSalt(primaryAdapter.data(), lootKey);
        // The two halves are adjacent, so one tier (and structure) resolved at the primary applies to
        // both, and the loot-modifier event fires once for the whole double chest.
        Vec3 origin = primaryAdapter.origin();
        LootScaling.ScaledTier scaled = LootScaling.resolveForGeneration(level, origin);
        DistanceTier tier = scaled.tier();
        ResourceKey<LootTable> ctxTable = primaryRef.key() != null ? primaryRef.key() : secondaryRef.key();
        ModifierResult mods = fireModifiers(player, origin, ctxTable, tier);
        NonNullList<ItemStack> primaryLoot = InstancedLootGenerator.generate(
                level, primaryAdapter.origin(), primaryRef.key(), primaryRef.seed(), salt, player,
                DoubleChestLayout.PRIMARY_SLOTS, mods.luck(), mods.stackMultiplier());
        NonNullList<ItemStack> secondaryLoot = InstancedLootGenerator.generate(
                level, secondaryAdapter.origin(), secondaryRef.key(), secondaryRef.seed(), salt, player,
                DoubleChestLayout.PRIMARY_SLOTS, mods.luck(), mods.stackMultiplier());

        NonNullList<ItemStack> combined =
                NonNullList.withSize(DoubleChestLayout.TOTAL_SLOTS, ItemStack.EMPTY);
        for (int slot = 0; slot < DoubleChestLayout.PRIMARY_SLOTS; slot++) {
            combined.set(slot, primaryLoot.get(slot));
            combined.set(DoubleChestLayout.PRIMARY_SLOTS + slot, secondaryLoot.get(slot));
        }
        // One injected reward for the whole double chest, drawn against the primary half's table (S-014).
        boolean injected = LootInjectionManager.augment(combined, primaryRef.key(), tier, level,
                primaryRef.seed(), salt, lootKey);

        primaryAdapter.update(data -> {
            data.markGenerated(primaryRef.key(), primaryRef.seed());
            data.setInventory(lootKey, combined);
            data.setLastGeneratedTick(lootKey, level.getGameTime());
            data.setTierName(tier.name());
            data.setStructure(scaled.structure());
        });
        secondaryAdapter.update(data -> {
            data.markGenerated(secondaryRef.key(), secondaryRef.seed());
            data.setRedirect(primaryPos);
        });
        primaryAdapter.clearLootTable();
        secondaryAdapter.clearLootTable();
        // The double chest's single indicator is anchored at the primary half (the scan emits only
        // there), so drop that one on first generation — for the opener, and for every online teammate
        // sharing this instance in party loot mode (issue #53).
        notifyGenerated(primaryAdapter, player, lootKey, shared);
        // First-generation action-bar notification (S-021), reflecting the final modifier values.
        LootNotification.send(player, level, origin, scaled, mods.stackMultiplier(), mods.luck());
        // One stats increment for the whole double chest — the same "one generation" the salt,
        // injection, and notification all treat it as (issue #52).
        recordStats(player, level, origin, scaled, injected);
        // After the instance is stored: this double may have been the structure's last unlooted
        // container for the player, earning the completion bonus into the just-stored inventory.
        StructureCompletion.onLootGenerated(primaryAdapter, player, primaryRef.key(), tier,
                primaryRef.seed(), salt);
        return combined;
    }

    /**
     * Generate-or-retrieve the player's inventory, then open the matching vanilla screen. In party loot
     * mode (issue #53) the instance is keyed on the player's resolved team ({@link PartyLootKeys}); a
     * shared instance already open by a teammate is refused with feedback rather than served a desynced
     * copy, and the {@linkplain SharedInstanceLocks in-use lock} is released when the screen closes.
     */
    public static void serveInstance(ContainerAdapter adapter, ServerPlayer player) {
        // Keep the leave-grace memory fresh with the player's live group on this real open (read paths
        // never stamp it, so a tooltip or scan cannot extend the window).
        PartyLootKeys.stampGrace(player);
        UUID lootKey = PartyLootKeys.resolve(player, adapter.data());
        boolean shared = !lootKey.equals(player.getUUID());
        String lockId = adapter.containerId();
        if (shared && !SharedInstanceLocks.tryAcquire(lootKey, lockId)) {
            // v1 concurrency: a second teammate opening a shared instance already in use is refused, not
            // shown a copy that last-close-wins would clobber.
            PartyLootKeys.refuseInUse(player, adapter.level(), adapter.origin());
            return;
        }
        boolean lockHandedOff = false;
        try {
            int size = adapter.size();
            NonNullList<ItemStack> stored = generateAndStore(adapter, player, lootKey);

            SimpleContainer screenInventory = new SimpleContainer(size);
            for (int slot = 0; slot < size; slot++) {
                screenInventory.setItem(slot, stored.get(slot).copy());
            }

            OptionalInt opened = player.openMenu(new SimpleMenuProvider(
                    (syncId, inventory, opener) -> InstancedLootMenu.createFor(syncId, inventory, screenInventory,
                            () -> {
                                adapter.persist(lootKey, screenInventory);
                                adapter.closeFeedback();
                                if (shared) {
                                    SharedInstanceLocks.release(lootKey, lockId);
                                }
                            }),
                    adapter.displayName()));
            // The lock is released by the menu-close hook only if the menu actually opened; if openMenu
            // declined (no connection, player removed), the hook never fires, so the finally frees it.
            lockHandedOff = opened.isPresent();
            adapter.openFeedback();
        } finally {
            if (shared && !lockHandedOff) {
                SharedInstanceLocks.release(lootKey, lockId);
            }
        }
    }

    /**
     * Return the opener's stored inventory, generating and persisting it on first visit. Keyed on the
     * player's own UUID; the party loot mode overload resolves the shared team key first.
     */
    public static NonNullList<ItemStack> generateAndStore(ContainerAdapter adapter, ServerPlayer player) {
        return generateAndStore(adapter, player, PartyLootKeys.resolve(player, adapter.data()));
    }

    /**
     * Return the stored inventory for {@code lootKey}, generating and persisting it on first visit.
     * Records the generation tick, preserves the original loot table/seed in the attachment, and nulls
     * the vanilla loot table on the source so a hopper or comparator cannot trigger vanilla's unpack and
     * drain the global loot (S-006).
     *
     * <p>{@code lootKey} is the instance key: the player's own UUID normally, or the shared team key in
     * party loot mode (issue #53), so a team reads and writes one inventory, tick, and refresh count.
     * The generation <em>context</em> stays the opening {@code player} — their luck, entity, and the
     * container position seed the roll — matching "the first team member to open generates the loot". The
     * base-loot roll seed therefore keys on the opener's UUID while the refresh salt and injection key on
     * {@code lootKey}; this only affects <em>which</em> items a re-roll draws, never correctness, since a
     * generated instance is always persisted and served back verbatim, never re-derived.
     */
    public static NonNullList<ItemStack> generateAndStore(ContainerAdapter adapter, ServerPlayer player,
            UUID lootKey) {
        boolean shared = !lootKey.equals(player.getUUID());
        // Bind the opener to the shared instance (on first open and every return), so they keep
        // resolving to it for this container after leaving the team, and the looted broadcast reaches
        // them. Recorded before the read below so the snapshot already includes the opener.
        recordMember(adapter, player, lootKey, shared);
        // Opportunistic absent-player eviction (issue #43) before any state is read, so an evicted
        // returning player falls through to a from-scratch generation below.
        AbsentPlayerEviction.prune(adapter);
        InstancedLootData existing = adapter.data();
        if (existing != null && existing.hasGenerated(lootKey)) {
            if (!LootRefresh.isExpired(existing, lootKey, adapter.level().getGameTime())) {
                // A return visit before the cooldown: serve the stored inventory, or an empty one
                // when it was evicted after being looted clean — never re-roll fresh loot (S-016). For a
                // team key this is a teammate seeing the shared pot exactly as the last one left it.
                NonNullList<ItemStack> stored = existing.getInventory(lootKey);
                return stored != null ? stored : NonNullList.withSize(adapter.size(), ItemStack.EMPTY);
            }
            // Cooldown elapsed: drop the instance so a fresh one is rolled below (S-016). The preserved
            // original loot table stays, so generation re-rolls from it. Per-team when keyed on a team.
            adapter.update(data -> data.clearForPlayer(lootKey));
        }

        LootRef ref = resolveTable(adapter);
        // Read after any cooldown clear above has advanced the refresh count, so a refresh re-rolls.
        long salt = refreshSalt(adapter.data(), lootKey);
        Vec3 origin = adapter.origin();
        LootScaling.ScaledTier scaled = LootScaling.resolveForGeneration(adapter.level(), origin);
        DistanceTier tier = scaled.tier();
        ModifierResult mods = fireModifiers(player, origin, ref.key(), tier);
        NonNullList<ItemStack> generated = InstancedLootGenerator.generate(
                adapter.level(), adapter.origin(), ref.key(), ref.seed(), salt, player, adapter.size(),
                mods.luck(), mods.stackMultiplier());
        // Add one tier-and-dimension-eligible injected reward in an empty slot (S-014).
        boolean injected = LootInjectionManager.augment(generated, ref.key(), tier, adapter.level(),
                ref.seed(), salt, lootKey);

        adapter.update(data -> {
            data.markGenerated(ref.key(), ref.seed());
            data.setInventory(lootKey, generated);
            data.setLastGeneratedTick(lootKey, adapter.level().getGameTime());
            data.setTierName(tier.name());
            data.setStructure(scaled.structure());
        });
        adapter.clearLootTable();
        // First generation only (return visits returned above): drop the unlooted indicator for the
        // opener, and for every online teammate sharing this instance (issue #53).
        notifyGenerated(adapter, player, lootKey, shared);
        // First-generation action-bar notification (S-021), reflecting the final modifier values.
        LootNotification.send(player, adapter.level(), origin, scaled, mods.stackMultiplier(), mods.luck());
        // Count this generation in the opener's loot stats — first visits and refresh re-rolls both
        // land here; return visits took the early return above and are never counted (issue #52).
        recordStats(player, adapter.level(), origin, scaled, injected);
        // After the instance is stored: this may have been the structure's last unlooted container
        // for the player (or team), earning the completion bonus into the just-stored inventory.
        StructureCompletion.onLootGenerated(adapter, player, ref.key(), tier, ref.seed(), salt);
        return generated;
    }

    /**
     * Bind {@code player} to the shared instance keyed by {@code lootKey} on this container (party loot
     * mode, issue #53), writing only when the snapshot does not already record them so a return visit
     * does not needlessly re-dirty the block entity. No-op for an individual (non-shared) key.
     */
    private static void recordMember(ContainerAdapter adapter, ServerPlayer player, UUID lootKey,
            boolean shared) {
        if (!shared) {
            return;
        }
        UUID member = player.getUUID();
        InstancedLootData data = adapter.data();
        if (data == null || !data.isTeamMember(lootKey, member)) {
            adapter.update(d -> d.recordTeamMember(lootKey, member));
        }
    }

    /**
     * Drop the unlooted indicator on first generation: for an individual key, just the opener; for a
     * shared team key, every online member recorded on this container (issue #53), so a teammate looking
     * at the chest sees it go dark the moment the first opener generates.
     */
    private static void notifyGenerated(ContainerAdapter adapter, ServerPlayer player, UUID lootKey,
            boolean shared) {
        if (!shared) {
            adapter.notifyGenerated(player);
            return;
        }
        InstancedLootData data = adapter.data();
        Set<UUID> members = data != null ? data.teamMembers(lootKey) : Set.of();
        if (members.isEmpty()) {
            adapter.notifyGenerated(player);
        } else {
            adapter.notifyGeneratedForMembers(player.getServer(), members);
        }
    }

    /** A stable, dimension-qualified id for the block container at {@code pos} (in-use lock key). */
    static String blockContainerId(ServerLevel level, BlockPos pos) {
        return level.dimension().location() + "@" + pos.asLong();
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

    /**
     * The roll salt for this (re)generation: the player's refresh count when
     * {@code randomizeLootOnRefresh} is enabled, otherwise {@code 0} for a deterministic roll. Read
     * after any cooldown clear has advanced the count, so successive refreshes draw distinct loot while
     * staying reproducible across a reload. A {@code null} attachment (first-ever visit) yields {@code 0}.
     */
    private static long refreshSalt(@Nullable InstancedLootData data, UUID uuid) {
        if (data == null) {
            return 0L;
        }
        return LootRefresh.refreshSalt(Prosperity.getConfig().randomizeLootOnRefresh,
                data.getRefreshCount(uuid));
    }

    /**
     * Fire the loot-modifier event (S-013) once for a generation and return the final luck and stack
     * multiplier. Falls back to the tier's raw values without firing when there is no table to modify
     * (an empty roll), since the context contract requires a non-null loot table.
     */
    private static ModifierResult fireModifiers(ServerPlayer player, Vec3 origin,
            @Nullable ResourceKey<LootTable> table, DistanceTier tier) {
        if (table == null) {
            return new ModifierResult(tier.qualityModifier(), tier.stackMultiplier());
        }
        LootModifierContext ctx =
                LootModifiers.fire(player, BlockPos.containing(origin), table.location(), tier);
        return new ModifierResult(ctx.luck(), ctx.stackMultiplier());
    }

    private record ModifierResult(float luck, double stackMultiplier) {
    }

    /**
     * Count one generation in the player's persistent loot stats (issue #52): the effective tier's
     * bucket, the structure's bucket when the container sits in one, and whether an injected reward
     * was actually placed. Recorded on the player attachment, so it survives relog and restart. Then
     * fire the milestone advancement criteria (issue #50) off the just-updated running totals, so the
     * tab is driven from the same single generation choke point and inherits its gating. The player's
     * very first generation — the {@code 0 → 1} transition of the lifetime count — also frames the
     * instanced-loot promise once in chat (issue #86), from this same choke point.
     *
     * <p>Structure attribution must not depend on the scaling gates:
     * {@link LootScaling#resolveForGeneration} skips detection when distance scaling is off or no
     * overrides are configured, so a {@code null} structure here re-resolves through the same
     * single-source-of-truth walk. The only redundant case is a container genuinely in no structure,
     * which re-checks cheaply once per generation.
     */
    private static void recordStats(ServerPlayer player, ServerLevel level, Vec3 origin,
            LootScaling.ScaledTier scaled, boolean injectedPlaced) {
        ResourceLocation structure = scaled.structure() != null ? scaled.structure()
                : LootScaling.resolveStructure(level, BlockPos.containing(origin));
        LootStatsData stats = ProsperityAttachments.updateStats(player,
                data -> data.recordGeneration(scaled.tier().name(), structure, injectedPlaced));
        // On the player's very first generation ever, frame the instanced-loot promise once (issue #86).
        // The 0 → 1 transition of the lifetime container count is exactly that first moment, so this
        // rides the same choke point and never re-fires on refreshes, return visits, or after relog.
        if (stats.containersLooted() == 1) {
            LootNotification.sendFirstOpen(player);
        }
        // Fire milestone advancement criteria off the just-updated running totals (issue #50). Sharing
        // this choke point means the criteria inherit its return-visit / blacklist / passthrough gating.
        ProsperityCriteria.INSTANCED_LOOT.trigger(player, scaled.tier().name(),
                stats.containersLooted(), stats.distinctStructures());
    }

    /**
     * Write a screen inventory back to the player's attachment entry on a block-entity container.
     * Routes through {@link InstancedLootData#storeOrEvict} so a container the player has looted clean
     * drops its stored inventory rather than persisting an empty one per visitor forever.
     */
    public static void persist(RandomizableContainerBlockEntity be, UUID uuid, Container container) {
        int size = container.getContainerSize();
        NonNullList<ItemStack> out = NonNullList.withSize(size, ItemStack.EMPTY);
        for (int slot = 0; slot < size; slot++) {
            out.set(slot, container.getItem(slot));
        }
        ProsperityAttachments.update(be, data -> data.storeOrEvict(uuid, out));
    }

    /** Persist the combined inventory back to the primary half and close-animate both halves. */
    private static void onCloseDouble(ServerLevel level, BlockPos primaryPos, BlockPos secondaryPos,
            UUID lootKey, Container screenInventory) {
        if (level.getBlockEntity(primaryPos) instanceof RandomizableContainerBlockEntity primary) {
            persist(primary, lootKey, screenInventory);
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
