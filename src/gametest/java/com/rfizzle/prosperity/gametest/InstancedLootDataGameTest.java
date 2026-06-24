package com.rfizzle.prosperity.gametest;

import com.rfizzle.prosperity.attachment.InstancedLootData;
import com.rfizzle.prosperity.attachment.ProsperityAttachments;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Runtime checks for the {@link InstancedLootData} Fabric data attachment (S-037). Proves a fresh
 * container carries no attachment (the byte-identical-to-vanilla guarantee is structural — the
 * attachment is created on demand, not auto-attached) and that attached data survives a
 * block-entity NBT save/load cycle — neither of which a Tier-2 test can see.
 */
public class InstancedLootDataGameTest implements FabricGameTest {

    private static final UUID PLAYER = new UUID(0x1234L, 0x5678L);

    /**
     * A naturally-placed container has no attachment until loot is generated, so it serializes to
     * nothing and stays byte-identical to vanilla on disk. The attachment is created on demand,
     * never attached up front — so interception gates on the loot table, not on its presence.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void freshContainerHasNoAttachment(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.CHEST);
        BlockEntity chest = helper.getBlockEntity(new BlockPos(1, 1, 1));

        helper.assertTrue(ProsperityAttachments.get(chest) == null,
                "a fresh container must carry no instanced-loot attachment");

        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void attachmentDataSurvivesBlockEntityReload(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        helper.setBlock(rel, Blocks.CHEST);
        BlockEntity chest = helper.getBlockEntity(rel);
        HolderLookup.Provider registries = helper.getLevel().registryAccess();

        ProsperityAttachments.update(chest, data -> {
            data.markGenerated(null, 0L);
            data.setLastGeneratedTick(PLAYER, 4242L);
            NonNullList<ItemStack> inv = data.getOrCreateInventory(PLAYER, 27);
            inv.set(0, new ItemStack(Items.DIAMOND, 5));
            inv.set(26, new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 1));
        });

        // Round-trip the block entity through NBT, as a chunk save/load would. Fabric serializes
        // the attachment on the block entity's own NBT, so it must restore from loadStatic.
        CompoundTag nbt = chest.saveWithFullMetadata(registries);
        BlockEntity reloaded = BlockEntity.loadStatic(helper.absolutePos(rel), chest.getBlockState(), nbt, registries);
        helper.assertTrue(reloaded != null, "block entity must reload from NBT");

        InstancedLootData restored = ProsperityAttachments.get(reloaded);
        helper.assertTrue(restored != null, "the instanced-loot attachment must survive reload");
        helper.assertTrue(restored.isGenerated(), "generated flag must survive reload");
        helper.assertTrue(restored.getLastGeneratedTick(PLAYER) == 4242L, "last-generated tick must survive reload");
        helper.assertTrue(restored.hasInventory(PLAYER), "player inventory must survive reload");

        NonNullList<ItemStack> restoredInv = restored.getInventory(PLAYER);
        helper.assertTrue(restoredInv.size() == 27, "inventory size must survive reload");
        helper.assertTrue(ItemStack.matches(new ItemStack(Items.DIAMOND, 5), restoredInv.get(0)),
                "slot 0 stack must survive reload");
        helper.assertTrue(ItemStack.matches(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 1), restoredInv.get(26)),
                "slot 26 stack must survive reload");

        helper.succeed();
    }
}
