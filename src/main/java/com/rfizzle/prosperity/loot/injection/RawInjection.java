package com.rfizzle.prosperity.loot.injection;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

/**
 * One target loot table, the minimum tier to apply at, the dimensions it is restricted to (empty
 * means any), the mod ids that must all be loaded for it to apply (empty means unconditional), the
 * per-generation {@code chance} the group survives its gate roll (default {@code 1.0}: always), and
 * the items to inject. The {@code requires_mods} gate is a finer-grained complement to the file-level
 * {@code fabric:load_conditions} header: it lets a single file mix unconditional injections with ones
 * scoped to a sibling mod, evaluated at load time only.
 */
public record RawInjection(ResourceLocation target, String minTier, List<ResourceLocation> dimensions,
        List<String> requiresMods, float chance, List<Entry> entries) {
    public static final Codec<RawInjection> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    ResourceLocation.CODEC.fieldOf("target").forGetter(RawInjection::target),
                    Codec.STRING.fieldOf("min_tier").forGetter(RawInjection::minTier),
                    ResourceLocation.CODEC.listOf().optionalFieldOf("dimensions", List.of())
                            .forGetter(RawInjection::dimensions),
                    Codec.STRING.listOf().optionalFieldOf("requires_mods", List.of())
                            .forGetter(RawInjection::requiresMods),
                    Codec.floatRange(0.0f, 1.0f).optionalFieldOf("chance", 1.0f)
                            .forGetter(RawInjection::chance),
                    Entry.CODEC.listOf().fieldOf("entries").forGetter(RawInjection::entries)
            ).apply(instance, RawInjection::new));
}
