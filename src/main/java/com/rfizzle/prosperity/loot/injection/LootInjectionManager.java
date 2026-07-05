package com.rfizzle.prosperity.loot.injection;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.resource.conditions.v1.ResourceCondition;
import net.fabricmc.fabric.api.resource.conditions.v1.ResourceConditions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.storage.loot.LootTable;
import org.jetbrains.annotations.Nullable;

/**
 * Datapack-driven additive loot injection (S-014, SPEC §5). Loads tier-gated item entries from
 * {@code data/prosperity/loot_injections/<name>.json} into a registry keyed by target loot table,
 * then at generation time adds one weighted-random eligible item to a freshly rolled container.
 *
 * <p>Injection is purely additive and orthogonal to vanilla loot: it never replaces a rolled item,
 * only fills one empty slot with a tier-exclusive reward. Eligibility gates on two conditions that
 * both must pass: the container's resolved {@link DistanceTier} (the geographic/structure tier, not
 * the post-modifier luck) being at or above the entry's {@code min_tier}, compared by
 * {@link DistanceTier#minDistance()}; and the container's dimension being in the entry's
 * {@code dimensions} list (an empty list matches any dimension).
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
    /** Decorrelates the structure-completion draw from the per-container injection draw. */
    private static final long COMPLETION_SALT = 0xC2B2AE3D27D4EB4FL;
    /** Log-once latch for a failing finalizer, so a persistent failure cannot warn-spam per generation. */
    private static final AtomicBoolean FINALIZER_FAILURE_LOGGED = new AtomicBoolean(false);

    /** Target loot table &rarr; the tier-gated injection groups that apply to it. Deeply immutable. */
    private static volatile Map<ResourceLocation, List<Tiered>> REGISTRY = Map.of();

    /**
     * The last-writer-wins finalizer slot applied to the drawn stack before placement; defaults to
     * the identity. Lets a compat integration rewrite an injected prototype at generation time (e.g.
     * roll dynamic enchantments) without the manager referencing the sibling mod.
     */
    private static volatile InjectedStackFinalizer finalizer = (level, stack, tier, random) -> stack;

    private LootInjectionManager() {
    }

    /**
     * Rewrites a drawn injected stack before it is placed. Receives the generating level, a private
     * copy of the prototype stack, the container's resolved tier, and the injection draw's
     * deterministic {@link RandomSource} (consume freely; the draw is already made). Return the stack
     * to place — the input itself to leave the prototype as authored.
     */
    @FunctionalInterface
    public interface InjectedStackFinalizer {
        ItemStack apply(ServerLevel level, ItemStack stack, DistanceTier tier, RandomSource random);
    }

    /** Install {@code finalizer} as the injected-stack finalizer (last writer wins; null ignored). */
    public static void setInjectedStackFinalizer(@Nullable InjectedStackFinalizer finalizer) {
        if (finalizer != null) {
            LootInjectionManager.finalizer = finalizer;
        }
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

    /**
     * Add one eligible injected item to {@code items} for a generation of {@code table} at {@code tier}
     * in {@code level}, placed in the first empty slot. An entry applies when its {@code min_tier} is
     * at or below {@code tier} and its {@code dimensions} are empty or contain the level's dimension.
     * No-op when injection is disabled, the table is absent, no entry is eligible, or the container is
     * full. The draw is deterministic for a given {@code (seedBase, salt, uuid)} triple, so a
     * regeneration with the same salt regenerates the same bonus item; {@code salt} is the player's
     * refresh count under {@code randomizeLootOnRefresh} (and {@code 0} otherwise), letting the bonus
     * re-roll on a refresh in lockstep with the main loot. The drawn stack passes through the
     * installed {@link InjectedStackFinalizer} before placement, isolated so a throwing or
     * empty-returning finalizer falls back to the drawn stack unchanged.
     */
    public static void augment(NonNullList<ItemStack> items, @Nullable ResourceKey<LootTable> table,
            DistanceTier tier, ServerLevel level, long seedBase, long salt, UUID uuid) {
        if (!Prosperity.getConfig().enableLootInjection || table == null) {
            return;
        }
        long mixed = seedBase * INJECTION_SALT
                ^ uuid.getMostSignificantBits()
                ^ Long.rotateLeft(uuid.getLeastSignificantBits(), 32)
                ^ Long.rotateLeft(salt * INJECTION_SALT, 29);
        RandomSource random = RandomSource.create(mixed == 0L ? 1L : mixed);
        ItemStack injected = pick(table.location(), tier, level.dimension().location(), random,
                level.registryAccess());
        if (injected == null || injected.isEmpty()) {
            return;
        }
        injected = finalizeInjected(level, injected, tier, random);
        for (int slot = 0; slot < items.size(); slot++) {
            if (items.get(slot).isEmpty()) {
                items.set(slot, injected);
                return;
            }
        }
    }

    /**
     * One extra reward for the structure completion bonus: the same weighted tier-and-dimension
     * eligible draw as {@link #augment}, decorrelated by {@link #COMPLETION_SALT} so it never mirrors
     * the container's regular injected item, and run through the installed finalizer. Deterministic
     * for a given {@code (seedBase, salt, uuid, structureSeed)} tuple; {@code structureSeed}
     * identifies the structure instance, so two structures completed by the same player draw
     * differently even when their final containers share a table and a {@code 0} seed (every
     * template-placed chest does). Returns {@code null} when the table is absent or has no eligible
     * entry. Gated by {@code enableStructureCompletionBonus} at the caller, not by
     * {@code enableLootInjection} &mdash; the completion bonus is its own feature that draws from the
     * injection pool, not an injection.
     */
    @Nullable
    public static ItemStack completionBonus(@Nullable ResourceKey<LootTable> table, DistanceTier tier,
            ServerLevel level, long seedBase, long salt, UUID uuid, long structureSeed) {
        if (table == null) {
            return null;
        }
        long mixed = seedBase * INJECTION_SALT
                ^ uuid.getMostSignificantBits()
                ^ Long.rotateLeft(uuid.getLeastSignificantBits(), 32)
                ^ Long.rotateLeft(salt * INJECTION_SALT, 29)
                ^ Long.rotateLeft(structureSeed * COMPLETION_SALT, 17)
                ^ COMPLETION_SALT;
        RandomSource random = RandomSource.create(mixed == 0L ? 1L : mixed);
        ItemStack drawn = pick(table.location(), tier, level.dimension().location(), random,
                level.registryAccess());
        if (drawn == null || drawn.isEmpty()) {
            return null;
        }
        return finalizeInjected(level, drawn, tier, random);
    }

    /**
     * Run {@code stack} through the installed finalizer with host-side error isolation: a finalizer
     * that throws or returns null/empty must never break loot generation, so any such outcome falls
     * back to the drawn stack unchanged.
     */
    private static ItemStack finalizeInjected(ServerLevel level, ItemStack stack, DistanceTier tier,
            RandomSource random) {
        try {
            ItemStack finalized = finalizer.apply(level, stack, tier, random);
            return finalized == null || finalized.isEmpty() ? stack : finalized;
        } catch (Throwable e) {
            if (FINALIZER_FAILURE_LOGGED.compareAndSet(false, true)) {
                Prosperity.LOGGER.warn("Injected-stack finalizer failed; placing the authored stack"
                        + " for this and any further failing generations", e);
            }
            return stack;
        }
    }

    /**
     * One weighted-random eligible injected item for {@code table} at {@code tier} in {@code dimension},
     * or {@code null} when the table has no injections or none are eligible. Generative entries resolve
     * their enchantment tag against {@code registries} at draw time.
     */
    @Nullable
    public static ItemStack pick(ResourceLocation table, DistanceTier tier, ResourceLocation dimension,
            RandomSource random, HolderLookup.Provider registries) {
        Map<ResourceLocation, List<Tiered>> registry = REGISTRY;
        List<Tiered> list = registry.get(table);
        if (list == null || list.isEmpty()) {
            return null;
        }
        return draw(eligibleEntries(list, tier, dimension, Prosperity.getConfig()), random, registries);
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

    /**
     * The injection groups eligible at {@code containerTier} in {@code dimension}: their {@code min_tier}
     * resolves and is at or below the tier, and their {@code dimensions} are empty (any) or contain the
     * dimension. The two gates compose — both must pass.
     */
    public static List<Entry> eligibleEntries(List<Tiered> list, DistanceTier containerTier,
            ResourceLocation dimension, ProsperityConfig cfg) {
        List<Entry> out = new ArrayList<>();
        for (Tiered tiered : list) {
            DistanceTier min = LootScaling.tierByName(cfg, tiered.minTier());
            boolean tierOk = min != null && containerTier.minDistance() >= min.minDistance();
            boolean dimensionOk = tiered.dimensions().isEmpty() || tiered.dimensions().contains(dimension);
            if (tierOk && dimensionOk) {
                out.addAll(tiered.entries());
            }
        }
        return out;
    }

    /**
     * One entry chosen by weight, realized to a stack; {@code null} when nothing can be drawn. A
     * generative entry whose tag resolves empty or absent against {@code registries} is dropped from
     * the pool <em>before</em> weighting, so the draw slot falls to the remaining entries rather than
     * being wasted. Both the resolution order (tag order) and the weighted pick are deterministic for
     * a given {@code random} seed.
     */
    @Nullable
    public static ItemStack draw(List<Entry> eligible, RandomSource random, HolderLookup.Provider registries) {
        List<Entry> pool = new ArrayList<>(eligible.size());
        List<HolderSet.Named<Enchantment>> resolved = new ArrayList<>(eligible.size());
        for (Entry entry : eligible) {
            HolderSet.Named<Enchantment> holders = null;
            if (entry.enchantRandomly().isPresent()) {
                holders = resolveTag(registries, entry.enchantRandomly().get());
                if (holders == null || holders.size() == 0) {
                    continue;
                }
            }
            pool.add(entry);
            resolved.add(holders);
        }
        if (pool.isEmpty()) {
            return null;
        }
        int total = 0;
        for (Entry entry : pool) {
            total += entry.weight();
        }
        int roll = random.nextInt(total);
        int chosen = pool.size() - 1;
        for (int i = 0; i < pool.size(); i++) {
            roll -= pool.get(i).weight();
            if (roll < 0) {
                chosen = i;
                break;
            }
        }
        Entry entry = pool.get(chosen);
        HolderSet.Named<Enchantment> holders = resolved.get(chosen);
        return holders == null ? entry.stack().copy()
                : generate(entry.stack(), holders, entry.level(), random);
    }

    /** The tag's holder set in {@code registries}, or {@code null} when the tag is absent. */
    @Nullable
    private static HolderSet.Named<Enchantment> resolveTag(HolderLookup.Provider registries,
            TagKey<Enchantment> tag) {
        return registries.lookup(Registries.ENCHANTMENT)
                .flatMap(lookup -> lookup.get(tag))
                .orElse(null);
    }

    /**
     * Realize a generative entry: one enchantment drawn uniformly from {@code holders} (known
     * non-empty), stored on a copy of {@code prototype} at the level {@code policy} yields. The stack's
     * item routes the write &mdash; {@link EnchantmentHelper#updateEnchantments} targets
     * {@code stored_enchantments} on an enchanted book.
     */
    private static ItemStack generate(ItemStack prototype, HolderSet.Named<Enchantment> holders,
            LevelPolicy policy, RandomSource random) {
        Holder<Enchantment> holder = holders.get(random.nextInt(holders.size()));
        int level = policyLevel(policy, holder.value().getMinLevel(), holder.value().getMaxLevel(), random);
        ItemStack stack = prototype.copy();
        EnchantmentHelper.updateEnchantments(stack, mutable -> mutable.set(holder, level));
        return stack;
    }

    /**
     * The enchantment level a policy yields within {@code [min, max]}: {@code mid} is the rounded-up
     * midpoint {@code ceil(max/2)} (floored at {@code min}), {@code max} the top level, and
     * {@code uniform} a uniform draw over the whole range &mdash; vanilla {@code enchant_randomly}
     * semantics, the only policy that consumes {@code random}.
     */
    public static int policyLevel(LevelPolicy policy, int min, int max, RandomSource random) {
        return switch (policy) {
            case MID -> Math.max(min, (max + 1) / 2);
            case MAX -> max;
            case UNIFORM -> Mth.nextInt(random, min, max);
        };
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
                        List.copyOf(injection.entries()));
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

    /**
     * One target loot table, the minimum tier to apply at, the dimensions it is restricted to (empty
     * means any), the mod ids that must all be loaded for it to apply (empty means unconditional), and
     * the items to inject. The {@code requires_mods} gate is a finer-grained complement to the file-level
     * {@code fabric:load_conditions} header: it lets a single file mix unconditional injections with ones
     * scoped to a sibling mod, evaluated at load time only.
     */
    public record RawInjection(ResourceLocation target, String minTier, List<ResourceLocation> dimensions,
            List<String> requiresMods, List<Entry> entries) {
        public static final Codec<RawInjection> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        ResourceLocation.CODEC.fieldOf("target").forGetter(RawInjection::target),
                        Codec.STRING.fieldOf("min_tier").forGetter(RawInjection::minTier),
                        ResourceLocation.CODEC.listOf().optionalFieldOf("dimensions", List.of())
                                .forGetter(RawInjection::dimensions),
                        Codec.STRING.listOf().optionalFieldOf("requires_mods", List.of())
                                .forGetter(RawInjection::requiresMods),
                        Entry.CODEC.listOf().fieldOf("entries").forGetter(RawInjection::entries)
                ).apply(instance, RawInjection::new));
    }

    /**
     * An injection group reduced to what the registry needs: the gating tier name, the dimensions it is
     * restricted to (empty means any), and its entries.
     */
    public record Tiered(String minTier, List<ResourceLocation> dimensions, List<Entry> entries) {
    }

    /**
     * The level a generative entry stores its drawn enchantment at, relative to the enchantment's own
     * {@code [min, max]} range. See {@link #policyLevel}.
     */
    public enum LevelPolicy implements StringRepresentable {
        UNIFORM("uniform"),
        MID("mid"),
        MAX("max");

        static final Codec<LevelPolicy> CODEC = StringRepresentable.fromEnum(LevelPolicy::values);

        private final String name;

        LevelPolicy(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    /** An enchantment-tag id with an optional {@code #} prefix (both forms accepted). */
    private static final Codec<TagKey<Enchantment>> ENCHANTMENT_TAG_CODEC = Codec.STRING.comapFlatMap(
            raw -> ResourceLocation.read(raw.startsWith("#") ? raw.substring(1) : raw)
                    .map(id -> TagKey.create(Registries.ENCHANTMENT, id)),
            tag -> "#" + tag.location());

    /**
     * One injectable item, in one of two mutually exclusive shapes. A <b>literal</b> entry is a
     * prototype {@link ItemStack} (id, count, full data components) placed as authored. A
     * <b>generative</b> entry instead names an enchantment tag ({@code enchant_randomly}) and a
     * {@link LevelPolicy} ({@code level}): at draw time one enchantment is picked uniformly from the
     * tag and stored on the prototype at the policy level, so a mod's whole catalog is covered without
     * enumerating it. Both carry a relative selection {@code weight}. The codec round-trips
     * {@code {item, count, components | enchant_randomly + level, weight}} and rejects an entry mixing
     * {@code components} with {@code enchant_randomly}.
     */
    public record Entry(ItemStack stack, Optional<TagKey<Enchantment>> enchantRandomly, LevelPolicy level,
            int weight) {

        /** A literal entry: the prototype stack as authored, no generative fields. */
        public Entry(ItemStack stack, int weight) {
            this(stack, Optional.empty(), LevelPolicy.UNIFORM, weight);
        }

        public static final Codec<Entry> CODEC = RecordCodecBuilder.<Entry>create(instance ->
                instance.group(
                        BuiltInRegistries.ITEM.byNameCodec().fieldOf("item")
                                .forGetter(entry -> entry.stack.getItem()),
                        ExtraCodecs.POSITIVE_INT.optionalFieldOf("count", 1)
                                .forGetter(entry -> entry.stack.getCount()),
                        DataComponentPatch.CODEC.optionalFieldOf("components", DataComponentPatch.EMPTY)
                                .forGetter(entry -> entry.stack.getComponentsPatch()),
                        ENCHANTMENT_TAG_CODEC.optionalFieldOf("enchant_randomly")
                                .forGetter(Entry::enchantRandomly),
                        LevelPolicy.CODEC.optionalFieldOf("level", LevelPolicy.UNIFORM)
                                .forGetter(Entry::level),
                        ExtraCodecs.POSITIVE_INT.optionalFieldOf("weight", 1).forGetter(Entry::weight)
                ).apply(instance, LootInjectionManager::makeEntry)
        ).validate(entry -> entry.enchantRandomly().isPresent() && !entry.stack().getComponentsPatch().isEmpty()
                ? DataResult.error(() -> "components and enchant_randomly are mutually exclusive")
                : DataResult.success(entry));
    }

    private static Entry makeEntry(Item item, int count, DataComponentPatch components,
            Optional<TagKey<Enchantment>> enchantRandomly, LevelPolicy level, int weight) {
        ItemStack stack = new ItemStack(item, count);
        stack.applyComponents(components);
        return new Entry(stack, enchantRandomly, level, weight);
    }
}
