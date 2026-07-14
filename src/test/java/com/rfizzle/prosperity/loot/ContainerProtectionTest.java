package com.rfizzle.prosperity.loot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rfizzle.prosperity.attachment.InstancedLootData;
import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.NonNullList;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Pure-JUnit coverage of the protection predicate's core rule (S-017): a container has pending loot
 * (so it stays protected) while it has never generated an instance, while at least one online player
 * has not generated theirs, or — when its loot is refreshable — indefinitely. The world-dependent
 * gates (config, creative, block type, managed loot container) are exercised in
 * {@code ContainerProtectionGameTest}.
 */
class ContainerProtectionTest {

    private static final UUID ALICE = new UUID(0L, 1L);
    private static final UUID BOB = new UUID(0L, 2L);

    // InstancedLootData's codecs touch ItemStack statics, which require the vanilla registries; bootstrap
    // so this class stands alone rather than relying on another test booting the JVM-global registry first.
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

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

    @Test
    void neverGeneratedContainerHasPendingLoot() {
        // No instance yet (null attachment or ungenerated): no one has claimed loot, so it is pending
        // for everyone — this is what makes a fresh, unopened chest protected, including in singleplayer.
        assertTrue(ContainerProtection.anyLootPending(null, List.of(ALICE), false));
        assertTrue(ContainerProtection.anyLootPending(new InstancedLootData(), List.of(ALICE), false));
    }

    @Test
    void generatedContainerFollowsOnlinePendingRule() {
        InstancedLootData data = new InstancedLootData();
        data.markGenerated(null, 0L);
        generatedFor(data, ALICE);
        // Alice opened it and is the only player online → emptied for everyone → not pending.
        assertFalse(ContainerProtection.anyLootPending(data, List.of(ALICE), false));
        // Bob is online but has not opened it → still pending.
        assertTrue(ContainerProtection.anyLootPending(data, List.of(ALICE, BOB), false));
    }

    @Test
    void refreshableGeneratedContainerStaysPending() {
        InstancedLootData data = new InstancedLootData();
        data.markGenerated(null, 0L);
        generatedFor(data, ALICE);
        // With loot refresh on, a container everyone has emptied still has loot coming — it stays
        // pending (protected) so no one can break it and deny the refresh to the rest.
        assertTrue(ContainerProtection.anyLootPending(data, List.of(ALICE), true));
        // The refresh flag never rescues a container that never generated an instance — already pending.
        assertTrue(ContainerProtection.anyLootPending(null, List.of(ALICE), true));
    }
}
