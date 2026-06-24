package com.rfizzle.prosperity.gametest;

import com.rfizzle.prosperity.component.InstancedLootComponent;
import com.rfizzle.prosperity.component.ProsperityComponents;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Runtime checks for the {@link InstancedLootComponent} CCA attachment (S-002). Proves the
 * component rides exactly the {@code RandomizableContainerBlockEntity} family and that its
 * data survives a block-entity NBT save/load cycle — neither of which a Tier-2 test can see.
 */
public class InstancedLootComponentGameTest implements FabricGameTest {

    private static final UUID PLAYER = new UUID(0x1234L, 0x5678L);

    private BlockEntity place(GameTestHelper helper, BlockPos rel, Block block) {
        helper.setBlock(rel, block);
        return helper.getBlockEntity(rel);
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void attachesToContainerFamily(GameTestHelper helper) {
        BlockEntity chest = place(helper, new BlockPos(1, 1, 1), Blocks.CHEST);
        BlockEntity barrel = place(helper, new BlockPos(1, 1, 3), Blocks.BARREL);
        BlockEntity shulker = place(helper, new BlockPos(1, 1, 5), Blocks.SHULKER_BOX);
        BlockEntity dispenser = place(helper, new BlockPos(3, 1, 1), Blocks.DISPENSER);
        BlockEntity dropper = place(helper, new BlockPos(3, 1, 3), Blocks.DROPPER);

        helper.assertTrue(ProsperityComponents.INSTANCED_LOOT.maybeGet(chest).isPresent(),
                "chest must carry the instanced loot component");
        helper.assertTrue(ProsperityComponents.INSTANCED_LOOT.maybeGet(barrel).isPresent(),
                "barrel must carry the instanced loot component");
        helper.assertTrue(ProsperityComponents.INSTANCED_LOOT.maybeGet(shulker).isPresent(),
                "shulker box must carry the instanced loot component");
        helper.assertTrue(ProsperityComponents.INSTANCED_LOOT.maybeGet(dispenser).isPresent(),
                "dispenser must carry the instanced loot component");
        helper.assertTrue(ProsperityComponents.INSTANCED_LOOT.maybeGet(dropper).isPresent(),
                "dropper must carry the instanced loot component");

        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void skipsNonContainerBlockEntities(GameTestHelper helper) {
        BlockEntity enderChest = place(helper, new BlockPos(1, 1, 1), Blocks.ENDER_CHEST);

        helper.assertFalse(ProsperityComponents.INSTANCED_LOOT.maybeGet(enderChest).isPresent(),
                "ender chest must not carry the instanced loot component");

        helper.succeed();
    }

    /**
     * A naturally-placed container carries the component but it is pure latent storage: until
     * the interaction layer (S-005) generates loot, it reports ungenerated and holds nothing,
     * so player-placed (non-loot) containers behave as vanilla.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void freshContainerComponentIsInert(GameTestHelper helper) {
        BlockEntity chest = place(helper, new BlockPos(1, 1, 1), Blocks.CHEST);
        InstancedLootComponent component = ProsperityComponents.INSTANCED_LOOT.get(chest);

        helper.assertFalse(component.isGenerated(), "fresh container must report ungenerated");
        helper.assertFalse(component.hasInventory(PLAYER), "fresh container must hold no instance");
        helper.assertTrue(component.getOriginalLootTable() == null, "fresh container preserves no table");

        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void componentDataSurvivesBlockEntityReload(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        BlockEntity chest = place(helper, rel, Blocks.CHEST);
        HolderLookup.Provider registries = helper.getLevel().registryAccess();

        InstancedLootComponent original = ProsperityComponents.INSTANCED_LOOT.get(chest);
        original.markGenerated(null, 0L);
        original.setLastGeneratedTick(PLAYER, 4242L);
        NonNullList<ItemStack> inv = original.getOrCreateInventory(PLAYER, 27);
        inv.set(0, new ItemStack(Items.DIAMOND, 5));
        inv.set(26, new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 1));

        // Round-trip the block entity through NBT, as a chunk save/load would.
        CompoundTag nbt = chest.saveWithFullMetadata(registries);
        BlockEntity reloaded = BlockEntity.loadStatic(helper.absolutePos(rel), chest.getBlockState(), nbt, registries);
        helper.assertTrue(reloaded != null, "block entity must reload from NBT");

        InstancedLootComponent restored = ProsperityComponents.INSTANCED_LOOT.get(reloaded);
        helper.assertTrue(restored.isGenerated(), "generated flag must survive reload");
        helper.assertTrue(restored.getLastGeneratedTick(PLAYER) == 4242L, "last-generated tick must survive reload");
        helper.assertTrue(restored.hasInventory(PLAYER), "player inventory must survive reload");

        NonNullList<ItemStack> restoredInv = restored.getInventory(PLAYER);
        helper.assertTrue(restoredInv.size() == 27, "inventory size must survive reload");
        helper.assertTrue(ItemStack.matches(inv.get(0), restoredInv.get(0)), "slot 0 stack must survive reload");
        helper.assertTrue(ItemStack.matches(inv.get(26), restoredInv.get(26)), "slot 26 stack must survive reload");

        helper.succeed();
    }
}
