package com.rfizzle.prosperity.loot;

import com.rfizzle.prosperity.attachment.InstancedLootData;
import com.rfizzle.prosperity.attachment.ProsperityAttachments;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.Container;
import net.minecraft.world.entity.vehicle.AbstractMinecartContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * {@link ContainerAdapter} over an {@code AbstractMinecartContainer} (chest and hopper minecarts) &mdash;
 * mineshaft loot. The generate/nullify/serve/persist path is identical to the block adapter; only the
 * attachment point (an entity attachment) and the feedback differ: there is no lid, so the cart only
 * plays the chest open/close sound at its live position. A minecart can be destroyed while its screen
 * is open, so the deferred operations guard on {@link AbstractMinecartContainer#isRemoved()}.
 */
public final class MinecartContainerAdapter implements ContainerAdapter {

    private final ServerLevel level;
    private final AbstractMinecartContainer cart;

    public MinecartContainerAdapter(ServerLevel level, AbstractMinecartContainer cart) {
        this.level = level;
        this.cart = cart;
    }

    @Nullable
    @Override
    public ResourceKey<LootTable> lootTable() {
        return cart.getLootTable();
    }

    @Override
    public long lootTableSeed() {
        return cart.getLootTableSeed();
    }

    @Override
    public void clearLootTable() {
        cart.setLootTable((ResourceKey<LootTable>) null);
        cart.setLootTableSeed(0L);
    }

    @Override
    public int size() {
        return cart.getContainerSize();
    }

    @Override
    public Component displayName() {
        return cart.getDisplayName();
    }

    @Override
    public Vec3 origin() {
        return cart.position();
    }

    @Override
    public ServerLevel level() {
        return level;
    }

    @Nullable
    @Override
    public InstancedLootData data() {
        return ProsperityAttachments.get(cart);
    }

    @Override
    public InstancedLootData update(Consumer<InstancedLootData> mutation) {
        return ProsperityAttachments.update(cart, mutation);
    }

    @Override
    public void persist(UUID player, Container screenInventory) {
        if (cart.isRemoved()) {
            return;
        }
        int size = screenInventory.getContainerSize();
        NonNullList<ItemStack> out = NonNullList.withSize(size, ItemStack.EMPTY);
        for (int slot = 0; slot < size; slot++) {
            out.set(slot, screenInventory.getItem(slot));
        }
        ProsperityAttachments.update(cart, data -> data.setInventory(player, out));
    }

    @Override
    public void openFeedback() {
        ContainerFeedback.playSound(level, cart.position(), SoundEvents.CHEST_OPEN);
    }

    @Override
    public void closeFeedback() {
        if (!cart.isRemoved()) {
            ContainerFeedback.playSound(level, cart.position(), SoundEvents.CHEST_CLOSE);
        }
    }
}
