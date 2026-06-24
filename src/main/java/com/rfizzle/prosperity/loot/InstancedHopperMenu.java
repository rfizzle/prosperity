package com.rfizzle.prosperity.loot;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.HopperMenu;

/**
 * A vanilla {@link HopperMenu} over a player's instanced 5-slot inventory, used for hopper minecarts.
 * The only addition is the same close hook as {@link InstancedLootMenu}: {@code onClose} runs once
 * server-side when the menu is removed, letting {@link InstancedLootInteraction} persist the
 * inventory and play the close cue. No custom screen &mdash; the client renders the vanilla hopper GUI.
 */
public final class InstancedHopperMenu extends HopperMenu {

    private final Runnable onClose;
    private boolean closed;

    private InstancedHopperMenu(int syncId, Inventory playerInventory, Container container,
            Runnable onClose) {
        super(syncId, playerInventory, container);
        this.onClose = onClose;
    }

    /** Build a hopper menu over a 5-slot {@code container}. */
    public static InstancedHopperMenu create(int syncId, Inventory playerInventory, Container container,
            Runnable onClose) {
        return new InstancedHopperMenu(syncId, playerInventory, container, onClose);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (!closed && !player.level().isClientSide) {
            closed = true;
            onClose.run();
        }
    }
}
