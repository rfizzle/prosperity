package com.rfizzle.prosperity.loot.injection;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.rfizzle.prosperity.Prosperity;
import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.resource.conditions.v1.ResourceCondition;
import net.fabricmc.fabric.api.resource.conditions.v1.ResourceConditions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * The datapack-driven loot-injection registry (S-014, SPEC §5): loads tier-gated item entries from
 * {@code data/prosperity/loot_injections/<name>.json} files into a map keyed by target loot table
 * and publishes the assembled result for the generation path ({@link LootInjectionManager}) and the
 * loot index (S-025) to read.
 *
 * <p>The wildcard target {@code prosperity:all_chests} expands at load time to every loot table whose
 * path contains a {@code chests/} segment, scanned from the live resource manager.
 *
 * <p><b>Threading:</b> {@link #REGISTRY} is rebuilt wholesale on the server thread during a data-pack
 * reload and published atomically; its contents are deeply immutable. Generation reads it on the
 * server thread. Each read snapshots the volatile reference once into a local.
 */
public final class InjectionRegistry {

    /** Directory under {@code data/<namespace>/} holding injection files. */
    private static final String DATA_PATH = "loot_injections";
    /** Special target expanding to every {@code **}{@code /chests/**} loot table. */
    private static final ResourceLocation ALL_CHESTS = Prosperity.id("all_chests");

    /** Target loot table &rarr; the tier-gated injection groups that apply to it. Deeply immutable. */
    private static volatile Map<ResourceLocation, List<Tiered>> REGISTRY = Map.of();

    private InjectionRegistry() {
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
                if (json instanceof JsonObject obj && !conditionsMet(obj, registries, fileId)) {
                    continue;
                }
                FileData data = FileData.CODEC.parse(ops, json).getOrThrow();
                files.add(new Loaded(fileId, data));
            } catch (Exception e) {
                Prosperity.LOGGER.error("Failed to load loot injection {}", fileId, e);
            }
        }

        Set<ResourceLocation> chestTables = scanChestTables(manager);
        Map<ResourceLocation, List<Tiered>> next =
                build(files, chestTables, FabricLoader.getInstance()::isModLoaded);
        int targets = next.size();
        int entries = next.values().stream().flatMap(List::stream).mapToInt(t -> t.entries().size()).sum();
        REGISTRY = next;
        Prosperity.LOGGER.info("Loaded {} loot injection entries across {} target tables", entries, targets);
    }

    /**
     * Evaluate a file's optional {@code fabric:load_conditions} header. A file without the header always
     * loads; one carrying it loads only when its conditions pass — giving {@code not}/{@code and}/
     * {@code or}/{@code fabric:all_mods_loaded} for free. An unmet gate is an expected, benign skip
     * (logged at {@code DEBUG}), consistent with the loader's graceful-degradation posture, so a sibling
     * mod being absent does not spam the log.
     */
    private static boolean conditionsMet(JsonObject obj, RegistryAccess registries, ResourceLocation fileId) {
        if (!obj.has(ResourceConditions.CONDITIONS_KEY)) {
            return true;
        }
        ResourceCondition condition = ResourceCondition.CONDITION_CODEC
                .parse(JsonOps.INSTANCE, obj.get(ResourceConditions.CONDITIONS_KEY)).getOrThrow();
        // Consumed once and never reused, so strip the header before the file hits FileData.CODEC.
        obj.remove(ResourceConditions.CONDITIONS_KEY);
        boolean met = condition.test(registries);
        if (!met) {
            Prosperity.LOGGER.debug("Skipping loot injection {}: load conditions not met", fileId);
        }
        return met;
    }

    /** The tier-gated injection groups registered for {@code table} (immutable), or empty when none. */
    static List<Tiered> forTable(ResourceLocation table) {
        List<Tiered> list = REGISTRY.get(table);
        return list == null ? List.of() : list;
    }

    /** The target loot tables that currently carry any injection (immutable snapshot). */
    public static Set<ResourceLocation> targets() {
        return REGISTRY.keySet();
    }

    /**
     * The injections registered for {@code table}, flattened to {@link InjectedView}s (prototype
     * stack, gating tier name, and the generative enchantment tag when present) for the loot index
     * (S-025). The wildcard is already expanded into concrete tables in the registry, so this
     * returns the entries that actually apply to {@code table}. Empty when none do.
     */
    public static List<InjectedView> injectionsFor(ResourceLocation table) {
        List<Tiered> list = REGISTRY.get(table);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<InjectedView> out = new ArrayList<>();
        for (Tiered tiered : list) {
            for (Entry entry : tiered.entries()) {
                out.add(new InjectedView(entry.stack(), tiered.minTier(), entry.enchantRandomly()));
            }
        }
        return List.copyOf(out);
    }

    /**
     * Build the registry from parsed files (sorted by id for deterministic {@code replace} ordering)
     * against the set of concrete chest tables the wildcard expands to. A file with {@code replace:true}
     * clears prior injections for each (expanded) target it touches before adding its own. An injection
     * whose {@code requires_mods} list names a mod for which {@code modLoaded} returns {@code false} is
     * silently dropped before it can be added or clear anything &mdash; the same graceful-degradation
     * posture as an unmet file-level condition.
     */
    static Map<ResourceLocation, List<Tiered>> build(List<Loaded> files, Set<ResourceLocation> chestTables,
            Predicate<String> modLoaded) {
        List<Loaded> ordered = new ArrayList<>(files);
        ordered.sort(Comparator.comparing(loaded -> loaded.id().toString()));

        Map<ResourceLocation, List<Tiered>> acc = new HashMap<>();
        for (Loaded loaded : ordered) {
            FileData data = loaded.data();
            Set<ResourceLocation> clearedThisFile = new HashSet<>();
            for (RawInjection injection : data.injections()) {
                if (!injection.requiresMods().stream().allMatch(modLoaded)) {
                    Prosperity.LOGGER.debug("Skipping injection for {} in {}: required mod absent",
                            injection.target(), loaded.id());
                    continue;
                }
                Tiered tiered = new Tiered(injection.minTier(), List.copyOf(injection.dimensions()),
                        injection.chance(), List.copyOf(injection.entries()));
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
}
