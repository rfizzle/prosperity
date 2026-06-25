package com.rfizzle.prosperity.loot;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.attachment.InstancedLootData;
import com.rfizzle.prosperity.attachment.ProsperityAttachments;
import com.rfizzle.prosperity.network.UnlootedMinecartsS2CPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.vehicle.AbstractMinecartContainer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

/**
 * Server-side scan that answers the minecart half of a client's per-chunk indicator request (S-038):
 * the network ids of every container minecart in a chunk that the requesting player has not yet
 * generated. The entity parallel of {@link UnlootedContainers}.
 *
 * <p>A container minecart is unlooted for a player when it is a loot container
 * ({@link MinecartContainerAdapter#isLootContainer}) and the player has no stored inventory in its
 * attachment. Carts are addressed by network id ({@link AbstractMinecartContainer#getId()}) rather
 * than {@code BlockPos} because they move; the client anchors each sparkle to the live entity it
 * resolves by id every frame.</p>
 */
public final class UnlootedMinecarts {

    private UnlootedMinecarts() {
    }

    /**
     * The unlooted container-minecart ids in {@code chunkPos} for {@code player}. A cart straddling a
     * chunk border may be reported by both chunks' scans; the client's flat id set makes that
     * idempotent.
     */
    public static List<Integer> scanChunk(ServerLevel level, ChunkPos chunkPos, UUID player) {
        AABB chunkColumn = new AABB(
                chunkPos.getMinBlockX(), level.getMinBuildHeight(), chunkPos.getMinBlockZ(),
                chunkPos.getMaxBlockX() + 1, level.getMaxBuildHeight(), chunkPos.getMaxBlockZ() + 1);

        List<Integer> ids = new ArrayList<>();
        for (AbstractMinecartContainer cart : level.getEntitiesOfClass(AbstractMinecartContainer.class, chunkColumn)) {
            MinecartContainerAdapter adapter = new MinecartContainerAdapter(level, cart);
            if (!adapter.isLootContainer()) {
                continue;
            }
            // Blacklisted carts never show the unlooted sparkle (SPEC §7).
            if (InstancedLootInteraction.isBlacklisted(cart.getLootTable())) {
                continue;
            }
            InstancedLootData data = ProsperityAttachments.get(cart);
            if (data != null && data.hasInventory(player)) {
                continue;
            }
            ids.add(cart.getId());
            if (ids.size() >= UnlootedMinecartsS2CPayload.MAX_ENTRIES) {
                Prosperity.LOGGER.warn("Unlooted-minecart scan of {} hit the {}-entry cap; truncating",
                        chunkPos, UnlootedMinecartsS2CPayload.MAX_ENTRIES);
                break;
            }
        }
        return ids;
    }
}
