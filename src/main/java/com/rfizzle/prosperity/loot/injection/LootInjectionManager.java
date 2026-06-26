package com.rfizzle.prosperity.loot.injection;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.config.DistanceTier;
import com.rfizzle.prosperity.config.ProsperityConfig;
import com.rfizzle.prosperity.loot.LootScaling;
import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootTable;
import org.jetbrains.annotations.Nullable;

/**
 * Datapack-driven additive loot injection (S-014, SPEC §5). Loads tier-gated item entries from
 * {@code data/prosperity/loot_injections/<name>.json} into a registry keyed by target loot table,
 * then at generation time adds one weighted-random eligible item to a freshly rolled container.
 *
 * <p>Injection is purely additive and orthogonal to vanilla loot: it never replaces a rolled item,
 * only fills one empty slot with a tier-exclusive reward. Eligibility gates on the container's
 * resolved {@link DistanceTier} (the geographic/structure tier, not the post-modifier luck) being at
 * or above the entry's {@code min_tier}, compared by {@link DistanceTier#minDistance()}.
 *
 * <p>The wildcard target {@code prosperity:all_chests} expands at load time to every loot table whose
 * path contains a {@code chests/} segment, scanned from the live resource manager.
 *
 * <p><b>Threading:</b> {@link #REGISTRY} is rebuilt wholesale on the server thread during a data-pack
 * reload and published atomically; its contents are deeply immutable. Generation reads it on the
 * server thread. Each read snapshots the volatile reference once into a local.
 */
public final class LootInjectionManager {

    /** Directory under {@code data/<namespace>/} holding injection files. */
    private static final String DATA_PATH = "loot_injections";
    /** Special target expanding to every {@code **}{@code /chests/**} loot table. */
    private static final ResourceLocation ALL_CHESTS = Prosperity.id("all_chests");
    /** Decorrelates the injection draw from the loot-roll seed so it does not mirror the rolled items. */
    private static final long INJECTION_SALT = 0x9E3779B97F4A7C15L;

    /** Target loot table &rarr; the tier-gated injection groups that apply to it. Deeply immutable. */
    private static volatile Map<ResourceLocation, List<Tiered>> REGISTRY = Map.of();

    private LootInjectionManager() {
    }

    /**
     * Register the load hooks. The server lifecycle events (rather than a plain resource listener) give
     * us the server's frozen registry access, which the enchantment components on injected items need
     * to deserialize, plus the loaded loot tables to expand the wildcard. {@code SERVER_STARTING} covers
     * the initial load on every server type (the headless gametest server included, which loads its data
     * pack at construction without firing a reload), and {@code END_DATA_PACK_RELOAD} catches a runtime
     * {@code /reload}.
     */
    public static void init() {
        ServerLifecycleEvents.SERVER_STARTING.register(server ->
                reload(server.registryAccess(), server.getResourceManager()));
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) ->
                reload(server.registryAccess(), resourceManager));
    }

    /** Rebuild {@link #REGISTRY} from the injection files in {@code manager}. */
    public static void reload(RegistryAccess registries, ResourceManager manager) {
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        List<Loaded> files = new ArrayList<>();
        for (Map.Entry<ResourceLocation, Resource> entry :
                manager.listResources(DATA_PATH, id -> id.getPath().endsWith(".json")).entrySet()) {
            ResourceLocation fileId = entry.getKey();
            try (BufferedReader reader = entry.getValue().openAsReader()) {
                JsonElement json = JsonParser.parseReader(reader);
                FileData data = FileData.CODEC.parse(ops, json).getOrThrow();
                files.add(new Loaded(fileId, data));
            } catch (Exception e) {
                Prosperity.LOGGER.error("Failed to load loot injection {}", fileId, e);
            }
        }

        Set<ResourceLocation> chestTables = scanChestTables(manager);
        Map<ResourceLocation, List<Tiered>> next = build(files, chestTables);
        int targets = next.size();
        int entries = next.values().stream().flatMap(List::stream).mapToInt(t -> t.entries().size()).sum();
        REGISTRY = next;
        Prosperity.LOGGER.info("Loaded {} loot injection entries across {} target tables", entries, targets);
    }

    /**
     * Add one tier-eligible injected item to {@code items} for a generation of {@code table} at
     * {@code tier}, placed in the first empty slot. No-op when injection is disabled, the table is
     * absent, no entry is eligible, or the container is full. The draw is deterministic for a given
     * {@code (seedBase, uuid)} so a refresh regenerates the same bonus item.
     */
    public static void augment(NonNullList<ItemStack> items, @Nullable ResourceKey<LootTable> table,
            DistanceTier tier, long seedBase, UUID uuid) {
        if (!Prosperity.getConfig().enableLootInjection || table == null) {
            return;
        }
        long mixed = seedBase * INJECTION_SALT
                ^ uuid.getMostSignificantBits()
                ^ Long.rotateLeft(uuid.getLeastSignificantBits(), 32);
        RandomSource random = RandomSource.create(mixed == 0L ? 1L : mixed);
        ItemStack injected = pick(table.location(), tier, random);
        if (injected == null || injected.isEmpty()) {
            return;
        }
        for (int slot = 0; slot < items.size(); slot++) {
            if (items.get(slot).isEmpty()) {
                items.set(slot, injected);
                return;
            }
        }
    }

    /**
     * One weighted-random eligible injected item for {@code table} at {@code tier}, or {@code null}
     * when the table has no injections or none are eligible at the tier.
     */
    @Nullable
    public static ItemStack pick(ResourceLocation table, DistanceTier tier, RandomSource random) {
        Map<ResourceLocation, List<Tiered>> registry = REGISTRY;
        List<Tiered> list = registry.get(table);
        if (list == null || list.isEmpty()) {
            return null;
        }
        return draw(eligibleEntries(list, tier, Prosperity.getConfig()), random);
    }

    /** The target loot tables that currently carry any injection (immutable snapshot). */
    public static Set<ResourceLocation> targets() {
        return REGISTRY.keySet();
    }

    /**
     * The injections registered for {@code table}, flattened to (prototype stack, gating tier name)
     * pairs for the loot index (S-025). The wildcard is already expanded into concrete tables in the
     * registry, so this returns the entries that actually apply to {@code table}. Empty when none do.
     */
    public static List<InjectedView> injectionsFor(ResourceLocation table) {
        List<Tiered> list = REGISTRY.get(table);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<InjectedView> out = new ArrayList<>();
        for (Tiered tiered : list) {
            for (Entry entry : tiered.entries()) {
                out.add(new InjectedView(entry.stack(), tiered.minTier()));
            }
        }
        return List.copyOf(out);
    }

    /** A read-only view of one injectable entry for the loot index: its prototype stack and gating tier. */
    public record InjectedView(ItemStack stack, String minTier) {
    }

    /** The injection groups whose {@code min_tier} resolves and is at or below {@code containerTier}. */
    static List<Entry> eligibleEntries(List<Tiered> list, DistanceTier containerTier, ProsperityConfig cfg) {
        List<Entry> out = new ArrayList<>();
        for (Tiered tiered : list) {
            DistanceTier min = LootScaling.tierByName(cfg, tiered.minTier());
            if (min != null && containerTier.minDistance() >= min.minDistance()) {
                out.addAll(tiered.entries());
            }
        }
        return out;
    }

    /** A copy of one entry's stack, chosen by weight; {@code null} for an empty list. */
    @Nullable
    static ItemStack draw(List<Entry> eligible, RandomSource random) {
        if (eligible.isEmpty()) {
            return null;
        }
        int total = 0;
        for (Entry entry : eligible) {
            total += entry.weight();
        }
        int roll = random.nextInt(total);
        for (Entry entry : eligible) {
            roll -= entry.weight();
            if (roll < 0) {
                return entry.stack().copy();
            }
        }
        return eligible.get(eligible.size() - 1).stack().copy();
    }

    /**
     * Build the registry from parsed files (sorted by id for deterministic {@code replace} ordering)
     * against the set of concrete chest tables the wildcard expands to. A file with {@code replace:true}
     * clears prior injections for each (expanded) target it touches before adding its own.
     */
    static Map<ResourceLocation, List<Tiered>> build(List<Loaded> files, Set<ResourceLocation> chestTables) {
        List<Loaded> ordered = new ArrayList<>(files);
        ordered.sort(Comparator.comparing(loaded -> loaded.id().toString()));

        Map<ResourceLocation, List<Tiered>> acc = new HashMap<>();
        for (Loaded loaded : ordered) {
            FileData data = loaded.data();
            Set<ResourceLocation> clearedThisFile = new HashSet<>();
            for (RawInjection injection : data.injections()) {
                Tiered tiered = new Tiered(injection.minTier(), List.copyOf(injection.entries()));
                for (ResourceLocation target : expand(injection.target(), chestTables)) {
                    if (data.replace() && clearedThisFile.add(target)) {
                        acc.put(target, new ArrayList<>());
                    }
                    acc.computeIfAbsent(target, key -> new ArrayList<>()).add(tiered);
                }
            }
        }

        Map<ResourceLocation, List<Tiered>> immutable = new HashMap<>();
        acc.forEach((target, list) -> immutable.put(target, List.copyOf(list)));
        return Map.copyOf(immutable);
    }

    /** The concrete targets a declared target resolves to: the wildcard fans out, others stay literal. */
    private static List<ResourceLocation> expand(ResourceLocation target, Set<ResourceLocation> chestTables) {
        return target.equals(ALL_CHESTS) ? List.copyOf(chestTables) : List.of(target);
    }

    /** Every loot-table id under the loot-table dir whose path contains a {@code chests/} segment. */
    static Set<ResourceLocation> scanChestTables(ResourceManager manager) {
        String dir = Registries.elementsDirPath(Registries.LOOT_TABLE);
        String prefix = dir + "/";
        String suffix = ".json";
        Set<ResourceLocation> out = new HashSet<>();
        for (ResourceLocation fileId : manager.listResources(dir, id -> id.getPath().endsWith(suffix)).keySet()) {
            String path = fileId.getPath();
            String stripped = path.substring(prefix.length(), path.length() - suffix.length());
            if (stripped.contains("chests/")) {
                out.add(ResourceLocation.fromNamespaceAndPath(fileId.getNamespace(), stripped));
            }
        }
        return out;
    }

    /** A parsed injection file with its source id, retained for deterministic ordering at build time. */
    record Loaded(ResourceLocation id, FileData data) {
    }

    /** One injection file: an optional {@code replace} flag and its list of per-target injections. */
    public record FileData(boolean replace, List<RawInjection> injections) {
        public static final Codec<FileData> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        Codec.BOOL.optionalFieldOf("replace", false).forGetter(FileData::replace),
                        RawInjection.CODEC.listOf().fieldOf("injections").forGetter(FileData::injections)
                ).apply(instance, FileData::new));
    }

    /** One target loot table, the minimum tier to apply at, and the items to inject. */
    public record RawInjection(ResourceLocation target, String minTier, List<Entry> entries) {
        public static final Codec<RawInjection> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        ResourceLocation.CODEC.fieldOf("target").forGetter(RawInjection::target),
                        Codec.STRING.fieldOf("min_tier").forGetter(RawInjection::minTier),
                        Entry.CODEC.listOf().fieldOf("entries").forGetter(RawInjection::entries)
                ).apply(instance, RawInjection::new));
    }

    /** An injection group reduced to what the registry needs: the gating tier name and its entries. */
    record Tiered(String minTier, List<Entry> entries) {
    }

    /**
     * One injectable item: a prototype {@link ItemStack} (id, count, full data components) and its
     * relative selection weight. The codec round-trips {@code {item, count, components, weight}}.
     */
    public record Entry(ItemStack stack, int weight) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        BuiltInRegistries.ITEM.byNameCodec().fieldOf("item")
                                .forGetter(entry -> entry.stack.getItem()),
                        ExtraCodecs.POSITIVE_INT.optionalFieldOf("count", 1)
                                .forGetter(entry -> entry.stack.getCount()),
                        DataComponentPatch.CODEC.optionalFieldOf("components", DataComponentPatch.EMPTY)
                                .forGetter(entry -> entry.stack.getComponentsPatch()),
                        ExtraCodecs.POSITIVE_INT.optionalFieldOf("weight", 1).forGetter(Entry::weight)
                ).apply(instance, LootInjectionManager::makeEntry));
    }

    private static Entry makeEntry(Item item, int count, DataComponentPatch components, int weight) {
        ItemStack stack = new ItemStack(item, count);
        stack.applyComponents(components);
        return new Entry(stack, weight);
    }
}
