package com.rfizzle.prosperity.loot;

import com.rfizzle.prosperity.api.LootModifierContext;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * The internal {@link LootModifierContext} implementation. Constructed by {@link LootModifiers} per
 * generation and kept off the stable API surface (package-private), so third-party mods depend only on
 * the interface. The {@code customData} bag is fresh per instance, giving each generation an isolated
 * scratch space.
 */
final class LootModifierContextImpl implements LootModifierContext {

    private final ServerPlayer player;
    private final BlockPos containerPos;
    private final ResourceLocation lootTable;
    private final CompoundTag customData = new CompoundTag();
    private float luck;
    private float stackMultiplier;

    LootModifierContextImpl(ServerPlayer player, BlockPos containerPos, ResourceLocation lootTable,
            float luck, float stackMultiplier) {
        this.player = player;
        this.containerPos = containerPos;
        this.lootTable = lootTable;
        this.luck = luck;
        this.stackMultiplier = stackMultiplier;
    }

    @Override
    public ServerPlayer player() {
        return player;
    }

    @Override
    public BlockPos containerPos() {
        return containerPos;
    }

    @Override
    public ResourceLocation lootTable() {
        return lootTable;
    }

    @Override
    public float luck() {
        return luck;
    }

    @Override
    public void setLuck(float luck) {
        this.luck = luck;
    }

    @Override
    public void addLuck(float bonus) {
        this.luck += bonus;
    }

    @Override
    public float stackMultiplier() {
        return stackMultiplier;
    }

    @Override
    public void setStackMultiplier(float multiplier) {
        this.stackMultiplier = multiplier;
    }

    @Override
    public void multiplyStacks(float factor) {
        this.stackMultiplier *= factor;
    }

    @Override
    public CompoundTag customData() {
        return customData;
    }
}
