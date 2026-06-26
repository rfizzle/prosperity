package com.rfizzle.prosperity.loot.index;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.config.ProsperityConfig;
import com.rfizzle.prosperity.loot.injection.LootInjectionManager;
import com.rfizzle.prosperity.loot.injection.LootInjectionManager.InjectedView;
import com.rfizzle.prosperity.loot.index.LootIndexEntry.Origin;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Function;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * The loader-agnostic data layer for the loot index (S-025, SPEC §11): the catalog of item sources
 * across structure loot tables, each tagged with its structure, minimum tier, and vanilla/injected
 * origin. The recipe-viewer plugins (S-029/030/031) read {@link #snapshot()}.
 *
 * <p>Loot tables live in the running server's reloadable registries, so the index is built
 * server-side on {@code SERVER_STARTING} and after a {@code /reload}, then published as an immutable
 * snapshot. Singleplayer's integrated server populates it in-JVM for the client viewers. On a remote
 * dedicated server the client has no loot data, so the server syncs the assembled index to the client
 * via {@link com.rfizzle.prosperity.network.LootIndexS2CPayload} and the client receiver publishes it
 * here through {@link #acceptSynced(List)} (S-047).
 *
 * <p><b>Threading:</b> {@link #SNAPSHOT} is rebuilt wholesale on the server thread and published
 * atomically; its contents are immutable. The remote client publishes the synced snapshot from the
 * client thread (only when no integrated server is running, so an integrated host's full in-JVM
 * snapshot is never overwritten by the capped sync). Viewers read the volatile reference.
 */
public final class LootIndexDataSource {

    private static volatile List<LootIndexEntry> SNAPSHOT = List.of();

    private LootIndexDataSource() {
    }

    /**
     * Register the server-side build hooks. Runs after {@link LootInjectionManager#init()} so the
     * injection registry is current when the index reads it.
     */
    public static void init() {
        ServerLifecycleEvents.SERVER_STARTING.register(LootIndexDataSource::rebuild);
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, manager, success) -> rebuild(server));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> SNAPSHOT = List.of());
    }

    /** The current loot index, or an empty list before the first build / on a loot-less client. */
    public static List<LootIndexEntry> snapshot() {
        return SNAPSHOT;
    }

    /**
     * Publish a snapshot received from the server over the network (S-047), so a remote client whose
     * own registries hold no loot data can still browse the index. Called by the client receiver only
     * when no integrated server is running — an integrated host already builds the full snapshot in
     * {@link #rebuild(MinecraftServer)} and must not be overwritten by the capped synced copy.
     */
    public static void acceptSynced(List<LootIndexEntry> rows) {
        SNAPSHOT = List.copyOf(rows);
    }

    public static int size() {
        return SNAPSHOT.size();
    }

    /**
     * Walk every structure loot table plus every injection target from {@code server}'s reloadable
     * registries, then publish the assembled index. Structure loot tables are those whose id path
     * contains a {@code chests/} segment; injection targets are included even when not chest tables.
     */
    public static void rebuild(MinecraftServer server) {
        ProsperityConfig cfg = Prosperity.getConfig();
        ReloadableServerRegistries.Holder registries = server.reloadableRegistries();
        Function<ResourceKey<LootTable>, LootTable> resolver = registries::getLootTable;

        TreeSet<ResourceLocation> tables = new TreeSet<>(Comparator.comparing(ResourceLocation::toString));
        for (ResourceLocation id : registries.getKeys(Registries.LOOT_TABLE)) {
            if (id.getPath().contains("chests/")) {
                tables.add(id);
            }
        }
        tables.addAll(LootInjectionManager.targets());

        Map<ResourceLocation, List<Item>> tableItems = new HashMap<>();
        Map<ResourceLocation, List<InjectedView>> injections = new HashMap<>();
        List<ResourceLocation> unmapped = new ArrayList<>();
        for (ResourceLocation id : tables) {
            LootTable table = registries.getLootTable(ResourceKey.create(Registries.LOOT_TABLE, id));
            tableItems.put(id, LootTableInspector.collect(table, resolver));
            List<InjectedView> injected = LootInjectionManager.injectionsFor(id);
            if (!injected.isEmpty()) {
                injections.put(id, injected);
            }
            if (LootTableStructures.structureFor(id, cfg).equals(LootTableStructures.OTHER)) {
                unmapped.add(id);
            }
        }

        List<LootIndexEntry> next = assemble(tableItems, injections, cfg);
        SNAPSHOT = next;
        Prosperity.LOGGER.info("Built loot index: {} entries across {} loot tables", next.size(), tables.size());
        if (!unmapped.isEmpty()) {
            Prosperity.LOGGER.info("Loot index: {} unmapped loot table(s) bucketed under 'Other'; add "
                    + "'lootTableStructures' config to assign a structure: {}", unmapped.size(), unmapped);
        }
    }

    /**
     * Assemble the index from already-extracted item sources and injections, ordered deterministically
     * (by loot-table id, then vanilla entries in extraction order followed by injected entries). Pure
     * — no registry or server access — so it is unit-testable over fixture data.
     */
    static List<LootIndexEntry> assemble(Map<ResourceLocation, List<Item>> tableItems,
            Map<ResourceLocation, List<InjectedView>> injections, ProsperityConfig cfg) {
        TreeSet<ResourceLocation> tables = new TreeSet<>(Comparator.comparing(ResourceLocation::toString));
        tables.addAll(tableItems.keySet());
        tables.addAll(injections.keySet());

        List<LootIndexEntry> out = new ArrayList<>();
        for (ResourceLocation table : tables) {
            ResourceLocation structure = LootTableStructures.structureFor(table, cfg);
            for (Item item : tableItems.getOrDefault(table, List.of())) {
                out.add(new LootIndexEntry(new ItemStack(item), table, structure, Optional.empty(), Origin.VANILLA));
            }
            for (InjectedView injected : injections.getOrDefault(table, List.of())) {
                out.add(new LootIndexEntry(injected.stack().copy(), table, structure,
                        Optional.of(injected.minTier()), Origin.INJECTED));
            }
        }
        return List.copyOf(out);
    }
}
