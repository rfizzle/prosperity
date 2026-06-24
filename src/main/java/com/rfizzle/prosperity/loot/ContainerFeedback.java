package com.rfizzle.prosperity.loot;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Open/close sound and lid-animation cues for instanced containers, shared by the block-entity and
 * minecart adapters and the double-chest path. We drive the lid animation that vanilla would
 * normally run through its opener counter without ever touching that counter, so nothing reverts it.
 *
 * <p>Chests and shulker boxes animate from a block event; barrels animate from the {@code OPEN}
 * blockstate. Minecarts have no lid, so they only get the sound.
 */
final class ContainerFeedback {

    private ContainerFeedback() {
    }

    static void playSound(ServerLevel level, BlockPos pos, SoundEvent sound) {
        float pitch = level.getRandom().nextFloat() * 0.1f + 0.9f;
        level.playSound(null, pos, sound, SoundSource.BLOCKS, 0.5f, pitch);
    }

    static void playSound(ServerLevel level, Vec3 pos, SoundEvent sound) {
        float pitch = level.getRandom().nextFloat() * 0.1f + 0.9f;
        level.playSound(null, pos.x, pos.y, pos.z, sound, SoundSource.BLOCKS, 0.5f, pitch);
    }

    static void animate(ServerLevel level, BlockPos pos, BlockEntity be, boolean open) {
        BlockState state = level.getBlockState(pos);
        if (be instanceof BarrelBlockEntity) {
            if (state.hasProperty(BarrelBlock.OPEN)) {
                level.setBlock(pos, state.setValue(BarrelBlock.OPEN, open), Block.UPDATE_ALL);
            }
        } else if (be instanceof ChestBlockEntity || be instanceof ShulkerBoxBlockEntity) {
            level.blockEvent(pos, state.getBlock(), 1, open ? 1 : 0);
        }
    }

    static SoundEvent openSound(BlockEntity be) {
        if (be instanceof BarrelBlockEntity) {
            return SoundEvents.BARREL_OPEN;
        }
        if (be instanceof ShulkerBoxBlockEntity) {
            return SoundEvents.SHULKER_BOX_OPEN;
        }
        return SoundEvents.CHEST_OPEN;
    }

    static SoundEvent closeSound(BlockEntity be) {
        if (be instanceof BarrelBlockEntity) {
            return SoundEvents.BARREL_CLOSE;
        }
        if (be instanceof ShulkerBoxBlockEntity) {
            return SoundEvents.SHULKER_BOX_CLOSE;
        }
        return SoundEvents.CHEST_CLOSE;
    }
}
