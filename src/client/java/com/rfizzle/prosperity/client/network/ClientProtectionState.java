package com.rfizzle.prosperity.client.network;

import com.rfizzle.prosperity.loot.ContainerProtection;
import net.minecraft.core.BlockPos;

/**
 * Client-side cache of the server's break-protection answer for the block the player is currently
 * breaking (S-017). A single target suffices because a player breaks one block at a time: the client
 * queries the server at break-start ({@code QueryProtectionC2S}) and stores the reply here, and the
 * common {@code getDestroyProgress} mixin reads it through {@link ContainerProtection.ClientProtectionView}
 * to slow the cracking animation to match the server gate.
 *
 * <p>Fields are {@code volatile}: the network receiver writes on the client thread (via
 * {@code client.execute}) while the mixin reads on the render/main thread.
 */
public final class ClientProtectionState implements ContainerProtection.ClientProtectionView {

    private static final ClientProtectionState INSTANCE = new ClientProtectionState();

    private volatile BlockPos target;
    private volatile float multiplier = 1.0f;

    private ClientProtectionState() {
    }

    public static ClientProtectionState get() {
        return INSTANCE;
    }

    /** Record the server's answer for a break target. */
    public void setResult(BlockPos pos, float multiplier) {
        this.target = pos;
        this.multiplier = multiplier;
    }

    /** Forget the current target (on disconnect), reverting to vanilla break speed. */
    public void clear() {
        this.target = null;
        this.multiplier = 1.0f;
    }

    @Override
    public float multiplierFor(BlockPos pos) {
        BlockPos current = target;
        return pos.equals(current) ? multiplier : 1.0f;
    }
}
