package com.rfizzle.prosperity.loot.injection;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;

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

    /** An enchantment-tag id with an optional {@code #} prefix (both forms accepted). */
    private static final Codec<TagKey<Enchantment>> ENCHANTMENT_TAG_CODEC = Codec.STRING.comapFlatMap(
            raw -> ResourceLocation.read(raw.startsWith("#") ? raw.substring(1) : raw)
                    .map(id -> TagKey.create(Registries.ENCHANTMENT, id)),
            tag -> "#" + tag.location());

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
            ).apply(instance, Entry::makeEntry)
    ).validate(entry -> entry.enchantRandomly().isPresent() && !entry.stack().getComponentsPatch().isEmpty()
            ? DataResult.error(() -> "components and enchant_randomly are mutually exclusive")
            : DataResult.success(entry));

    private static Entry makeEntry(Item item, int count, DataComponentPatch components,
            Optional<TagKey<Enchantment>> enchantRandomly, LevelPolicy level, int weight) {
        ItemStack stack = new ItemStack(item, count);
        stack.applyComponents(components);
        return new Entry(stack, enchantRandomly, level, weight);
    }
}
