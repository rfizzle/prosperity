package com.rfizzle.prosperity.concurrency;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.config.ProsperityConfig;
import com.rfizzle.prosperity.loot.injection.LootInjectionManager;
import com.rfizzle.prosperity.loot.injection.LootInjectionManager.Tiered;
import com.rfizzle.prosperity.network.ProsperityNetworking;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Pure-JUnit stress over Prosperity's three concurrent shared-state surfaces (S-043), modeled on
 * {@code mercantile/.../concurrency/ThreadSafetyTest}. Each test starts N worker threads on a
 * single {@link CountDownLatch} gate, captures the first thrown {@link Throwable}, and fails if the
 * pool does not drain — so a corrupt-state exception or a hang surfaces as a test failure rather
 * than a silent pass.
 *
 * <ul>
 *   <li><b>Rate limiter</b> — {@link ProsperityNetworking}'s sliding-window {@code allowRequest}
 *       backed by a {@link ConcurrentHashMap}; the per-player window is single-threaded by design
 *       (one netty thread per player), but the map is touched cross-thread by distinct players and
 *       by disconnect cleanup.</li>
 *   <li><b>Injection registry</b> — {@link LootInjectionManager}'s {@code volatile} registry,
 *       rebuilt wholesale and published atomically; readers must always see a complete, immutable
 *       snapshot.</li>
 *   <li><b>Config reference</b> — {@link Prosperity}'s {@code volatile} config, published via
 *       {@code getConfig()}.</li>
 * </ul>
 */
class ThreadSafetyTest {

    private static final int THREADS = 8;
    private static final int ITERATIONS = 200;

    @AfterEach
    void cleanup() throws Exception {
        // Reset the volatile registry and config so a test never leaks a snapshot into the next.
        setRegistry(Map.of());
        configField().set(null, null);
    }

    // --- 1. ProsperityNetworking.allowRequest — ConcurrentHashMap sliding window ---

    /**
     * Each thread drives its own player UUID (the real one-netty-thread-per-player invariant), so
     * every window is single-threaded and, with {@code ITERATIONS} below the cap and the run inside
     * one window, every request must be admitted. A corrupted counter would drop admits or throw.
     */
    @Test
    void rateLimiterConcurrentDistinctPlayers() throws Exception {
        Map<UUID, long[]> windows = new ConcurrentHashMap<>();
        Method allow = allowRequestMethod();
        int cap = 512;

        UUID[] ids = new UUID[THREADS];
        AtomicInteger[] admits = new AtomicInteger[THREADS];
        for (int t = 0; t < THREADS; t++) {
            ids[t] = UUID.randomUUID();
            admits[t] = new AtomicInteger();
        }

        runConcurrent(THREADS, (tid) -> {
            for (int i = 0; i < ITERATIONS; i++) {
                if ((boolean) allow.invoke(null, windows, cap, ids[tid])) {
                    admits[tid].incrementAndGet();
                }
            }
        });

        for (int t = 0; t < THREADS; t++) {
            assertTrue(admits[t].get() == ITERATIONS,
                    "Each distinct-player window should admit every request below the cap within one "
                            + "window; thread " + t + " admitted " + admits[t].get());
        }
    }

    /** A single window admits exactly {@code cap} requests, then denies the rest. */
    @Test
    void rateLimiterDeniesAboveWindowCap() throws Exception {
        Map<UUID, long[]> windows = new ConcurrentHashMap<>();
        Method allow = allowRequestMethod();
        int cap = 64;
        UUID id = UUID.randomUUID();

        int admitted = 0;
        for (int i = 0; i < cap + 50; i++) {
            if ((boolean) allow.invoke(null, windows, cap, id)) {
                admitted++;
            }
        }
        assertTrue(admitted == cap, "Window should admit exactly the cap; got " + admitted);
    }

    /**
     * Workers hammer {@code allowRequest} on their own UUIDs while a disconnect thread concurrently
     * {@code remove}s those UUIDs (the real netty-receiver-vs-DISCONNECT race on the shared map). The
     * map must absorb {@code computeIfAbsent} vs {@code remove} on the same keys with no exception.
     */
    @Test
    void rateLimiterConcurrentWithDisconnectRemoval() throws Exception {
        Map<UUID, long[]> windows = new ConcurrentHashMap<>();
        Method allow = allowRequestMethod();
        int cap = 512;

        UUID[] ids = new UUID[THREADS];
        for (int t = 0; t < THREADS; t++) {
            ids[t] = UUID.randomUUID();
        }

        // THREADS request workers + 1 disconnect sweeper.
        runConcurrent(THREADS + 1, (tid) -> {
            if (tid == THREADS) {
                for (int i = 0; i < ITERATIONS * THREADS; i++) {
                    windows.remove(ids[i % THREADS]);
                }
            } else {
                for (int i = 0; i < ITERATIONS; i++) {
                    allow.invoke(null, windows, cap, ids[tid]);
                }
            }
        });
    }

    // --- 2. LootInjectionManager — volatile registry swap vs lookup ---

    /**
     * Writer threads run the real {@link LootInjectionManager#reload} build-and-swap path (empty
     * resource manager, {@link RegistryAccess#EMPTY} — so no datapack/registry deref) while reader
     * threads call the lookups. Neither side may throw; readers iterate only immutable snapshots.
     */
    @Test
    void injectionConcurrentReloadVsLookup() throws Exception {
        ResourceManager empty = emptyResourceManager();
        // Establish a baseline snapshot before readers start.
        LootInjectionManager.reload(RegistryAccess.EMPTY, empty);
        ResourceLocation probe = Prosperity.id("any_table");

        runConcurrent(THREADS, (tid) -> {
            boolean writer = (tid % 2 == 0);
            for (int i = 0; i < ITERATIONS; i++) {
                if (writer) {
                    LootInjectionManager.reload(RegistryAccess.EMPTY, empty);
                } else {
                    Set<ResourceLocation> targets = LootInjectionManager.targets();
                    // Snapshot is immutable and self-consistent; iterating must never throw.
                    for (ResourceLocation t : targets) {
                        LootInjectionManager.injectionsFor(t);
                    }
                    LootInjectionManager.injectionsFor(probe);
                }
            }
        });
    }

    /**
     * Alternates the volatile registry between two pre-built immutable snapshots with distinct key
     * sets while readers call {@link LootInjectionManager#targets()}. Every observed snapshot must be
     * complete (exactly one of the two key sets, never a partial mix) and immutable.
     */
    @Test
    void injectionVolatileSwapPublishesCompleteSnapshot() throws Exception {
        ResourceLocation t1 = Prosperity.id("table_one");
        ResourceLocation t2 = Prosperity.id("table_two");
        // Empty entry lists keep this snapshot ItemStack-free, so the test needs no Bootstrap.
        Tiered tiered = new Tiered("local", List.of(), List.of());
        Map<ResourceLocation, List<Tiered>> snapA = Map.of(t1, List.of(tiered));
        Map<ResourceLocation, List<Tiered>> snapB = Map.of(t1, List.of(tiered), t2, List.of(tiered));

        setRegistry(snapA);

        runConcurrent(THREADS, (tid) -> {
            boolean writer = (tid % 2 == 0);
            for (int i = 0; i < ITERATIONS; i++) {
                if (writer) {
                    setRegistry((i % 2 == 0) ? snapB : snapA);
                } else {
                    Set<ResourceLocation> targets = LootInjectionManager.targets();
                    int size = targets.size();
                    if (size == 1) {
                        assertTrue(targets.contains(t1), "Snapshot A must contain t1");
                    } else if (size == 2) {
                        assertTrue(targets.contains(t1) && targets.contains(t2),
                                "Snapshot B must contain both t1 and t2");
                    } else {
                        fail("Observed a partial registry snapshot of size " + size);
                    }
                }
            }
        });

        // The published key set is immutable — no reader could corrupt it in place.
        assertThrows(UnsupportedOperationException.class,
                () -> LootInjectionManager.targets().add(t1));
    }

    // --- 3. Prosperity.config — volatile reference publish ---

    /** All readers observe the single published config instance. */
    @Test
    void configConcurrentReadsReturnSameInstance() throws Exception {
        ProsperityConfig expected = new ProsperityConfig();
        configField().set(null, expected);

        int n = 16;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ProsperityConfig>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return Prosperity.getConfig();
            }));
        }
        start.countDown();
        pool.shutdown();
        if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
            pool.shutdownNow();
            fail("Thread pool did not terminate — worker task may have hung");
        }
        for (Future<ProsperityConfig> f : futures) {
            assertSame(expected, f.get(), "All threads must see the same published config");
        }
    }

    /** Concurrent publishers vs readers: once a config is published, no reader sees a null. */
    @Test
    void configVolatilePublishIsVisible() throws Exception {
        configField().set(null, new ProsperityConfig());

        runConcurrent(THREADS, (tid) -> {
            boolean writer = (tid % 2 == 0);
            for (int i = 0; i < ITERATIONS; i++) {
                if (writer) {
                    configField().set(null, new ProsperityConfig());
                } else {
                    assertTrue(Prosperity.getConfig() != null,
                            "A reader must never see a null config after first publish");
                }
            }
        });
    }

    // --- helpers ---

    /** A worker body indexed by thread id; checked exceptions are captured and fail the test. */
    @FunctionalInterface
    private interface Worker {
        void run(int threadId) throws Exception;
    }

    /**
     * Run {@code n} workers behind one start gate, capture the first {@link Throwable}, and fail if
     * the pool does not drain in time or any worker threw.
     */
    private static void runConcurrent(int n, Worker worker) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < n; t++) {
            final int tid = t;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    worker.run(tid);
                } catch (Throwable e) {
                    error.compareAndSet(null, e);
                }
                return null;
            }));
        }
        start.countDown();
        pool.shutdown();
        if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
            pool.shutdownNow();
            fail("Thread pool did not terminate — worker task may have hung");
        }
        assertNull(error.get(), () -> "A worker thread threw: " + error.get());
    }

    private static Method allowRequestMethod() throws Exception {
        Method m = ProsperityNetworking.class.getDeclaredMethod(
                "allowRequest", Map.class, int.class, UUID.class);
        m.setAccessible(true);
        return m;
    }

    private static Field configField() throws Exception {
        Field f = Prosperity.class.getDeclaredField("config");
        f.setAccessible(true);
        return f;
    }

    private static void setRegistry(Map<ResourceLocation, List<Tiered>> value) throws Exception {
        Field f = LootInjectionManager.class.getDeclaredField("REGISTRY");
        f.setAccessible(true);
        f.set(null, value);
    }

    private static ResourceManager emptyResourceManager() {
        return new ResourceManager() {
            @Override public Set<String> getNamespaces() { return Set.of(); }
            @Override public Optional<Resource> getResource(ResourceLocation id) { return Optional.empty(); }
            @Override public List<Resource> getResourceStack(ResourceLocation id) { return List.of(); }
            @Override public Map<ResourceLocation, Resource> listResources(String p, Predicate<ResourceLocation> f) { return Map.of(); }
            @Override public Map<ResourceLocation, List<Resource>> listResourceStacks(String p, Predicate<ResourceLocation> f) { return Map.of(); }
            @Override public Stream<PackResources> listPacks() { return Stream.empty(); }
        };
    }
}
