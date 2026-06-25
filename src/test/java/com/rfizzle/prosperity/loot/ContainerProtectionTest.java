package com.rfizzle.prosperity.loot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rfizzle.prosperity.attachment.InstancedLootData;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.NonNullList;
import org.junit.jupiter.api.Test;

/**
 * Pure-JUnit coverage of the protection predicate's core rule (S-017): a container stays protected
 * while at least one online player has not generated their instance. The world-dependent gates
 * (config, creative, block type, generated flag) are exercised in {@code ContainerProtectionGameTest}.
 */
class ContainerProtectionTest {

    private static final UUID ALICE = new UUID(0L, 1L);
    private static final UUID BOB = new UUID(0L, 2L);

    /** Marks {@code player} as having generated an instance, without touching ItemStack statics. */
    private static void generatedFor(InstancedLootData data, UUID player) {
        data.setInventory(player, NonNullList.create());
    }

    @Test
    void pendingOnlinePlayerKeepsProtection() {
        InstancedLootData data = new InstancedLootData();
        generatedFor(data, ALICE);
        // Bob is online but has not generated → still protected.
        assertTrue(ContainerProtection.anyOnlinePlayerPending(data, List.of(ALICE, BOB)));
    }

    @Test
    void allOnlinePlayersGeneratedLiftsProtection() {
        InstancedLootData data = new InstancedLootData();
        generatedFor(data, ALICE);
        assertFalse(ContainerProtection.anyOnlinePlayerPending(data, List.of(ALICE)));

        generatedFor(data, BOB);
        assertFalse(ContainerProtection.anyOnlinePlayerPending(data, List.of(ALICE, BOB)));
    }

    @Test
    void noOnlinePlayersIsNotProtected() {
        InstancedLootData data = new InstancedLootData();
        assertFalse(ContainerProtection.anyOnlinePlayerPending(data, List.of()));
    }
}
