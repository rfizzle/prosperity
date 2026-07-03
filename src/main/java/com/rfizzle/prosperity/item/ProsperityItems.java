package com.rfizzle.prosperity.item;

import com.rfizzle.prosperity.Prosperity;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;

/**
 * The mod's registered items. Prosperity ships almost no custom content by design — the one
 * exception is the Prospector's Compass, whose needle points at the nearest container the holder
 * has not yet looted (the same per-player unlooted set the sparkle indicators visualize). The item
 * itself is behaviorless on the server; all needle logic is a client-side item property reading
 * the indicator cache.
 */
public final class ProsperityItems {

    public static final Item PROSPECTORS_COMPASS =
            new Item(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON));

    private static boolean registered = false;

    private ProsperityItems() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        Registry.register(BuiltInRegistries.ITEM, Prosperity.id("prospectors_compass"),
                PROSPECTORS_COMPASS);
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES)
                .register(entries -> entries.addAfter(Items.COMPASS, PROSPECTORS_COMPASS));
    }
}
