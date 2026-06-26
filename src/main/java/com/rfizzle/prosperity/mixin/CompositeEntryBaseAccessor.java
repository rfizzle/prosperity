package com.rfizzle.prosperity.mixin;

import java.util.List;
import net.minecraft.world.level.storage.loot.entries.CompositeEntryBase;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the children of a composite entry (alternatives / group / sequence) so the loot index
 * (S-025) can recurse into them.
 */
@Mixin(CompositeEntryBase.class)
public interface CompositeEntryBaseAccessor {
    @Accessor("children")
    List<LootPoolEntryContainer> prosperity$getChildren();
}
