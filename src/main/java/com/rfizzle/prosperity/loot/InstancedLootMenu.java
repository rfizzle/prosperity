package com.rfizzle.prosperity.loot;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;

/**
 * A vanilla {@link ChestMenu} over a player's instanced inventory. The only addition is a close
 * hook: when the menu is removed (screen closed, or the player disconnects), {@code onClose} runs
 * server-side once, letting {@link InstancedLootInteraction} persist the inventory and play the
 * lid-close cue. No custom screen — the client renders the matching vanilla chest GUI.
 */
public final class InstancedLootMenu extends ChestMenu {

    private final Runnable onClose;
    private boolean closed;

    private InstancedLootMenu(MenuType<?> type, int syncId, Inventory playerInventory,
            Container container, int rows, Runnable onClose) {
        super(type, syncId, playerInventory, container, rows);
        this.onClose = onClose;
    }

    /** Build a menu sized to {@code container} (a multiple of nine slots, one to six rows). */
    public static InstancedLootMenu create(int syncId, Inventory playerInventory, Container container,
            Runnable onClose) {
        int rows = rowsFor(container.getContainerSize());
        return new InstancedLootMenu(menuTypeFor(rows), syncId, playerInventory, container, rows, onClose);
    }

    /** Chest rows for a container size: 27 → 3, 9 → 1, 54 → 6. */
    public static int rowsFor(int size) {
        return size / 9;
    }

    private static MenuType<?> menuTypeFor(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };
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
