package com.rfizzle.prosperity.loot.injection;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;

/** One injection file: an optional {@code replace} flag and its list of per-target injections. */
public record FileData(boolean replace, List<RawInjection> injections) {
    public static final Codec<FileData> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.BOOL.optionalFieldOf("replace", false).forGetter(FileData::replace),
                    RawInjection.CODEC.listOf().fieldOf("injections").forGetter(FileData::injections)
            ).apply(instance, FileData::new));
}
