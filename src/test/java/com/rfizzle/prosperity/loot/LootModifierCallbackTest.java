package com.rfizzle.prosperity.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rfizzle.prosperity.api.LootModifierCallback;
import com.rfizzle.prosperity.api.LootModifierContext;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * Guards the API-STANDARD §3.1 invoker posture of {@link LootModifierCallback#EVENT}: a listener
 * that throws is skipped and the listeners after it still fire against the same context, so a
 * misbehaving guest cannot silently drop another mod's {@code addLuck} contribution.
 */
class LootModifierCallbackTest {

    private static final ResourceLocation TABLE =
            ResourceLocation.fromNamespaceAndPath("minecraft", "chests/simple_dungeon");

    private static LootModifierContext context() {
        return new LootModifierContextImpl(null, BlockPos.ZERO, TABLE, 0.0f, 1.0f);
    }

    @Test
    void throwingListenerIsSkippedAndLaterListenersStillFire() {
        List<String> order = new ArrayList<>();
        LootModifierCallback.EVENT.register(ctx -> {
            order.add("first");
            ctx.addLuck(1.0f);
        });
        LootModifierCallback.EVENT.register(ctx -> {
            order.add("thrower");
            throw new IllegalStateException("guest bug");
        });
        // An Error, not just an Exception: what a listener compiled against an older signature throws.
        LootModifierCallback.EVENT.register(ctx -> {
            order.add("error-thrower");
            throw new AbstractMethodError("stale guest");
        });
        LootModifierCallback.EVENT.register(ctx -> {
            order.add("last");
            ctx.addLuck(2.0f);
        });

        LootModifierContext ctx = context();
        LootModifierCallback.EVENT.invoker().onModifyLoot(ctx);

        assertEquals(List.of("first", "thrower", "error-thrower", "last"), order,
                "every listener fires, in registration order, regardless of an earlier throw");
        assertEquals(3.0f, ctx.luck(), 1e-6f,
                "the contributions on both sides of the throwing listeners survive");
        assertTrue(LootModifierCallback.LISTENER_FAILURE_LOGGED.get(), "the failure is recorded once");
    }
}
