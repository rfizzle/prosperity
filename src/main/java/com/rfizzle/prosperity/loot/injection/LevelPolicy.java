package com.rfizzle.prosperity.loot.injection;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * The level a generative entry stores its drawn enchantment at, relative to the enchantment's own
 * {@code [min, max]} range. See {@link InjectionSelector#policyLevel}.
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
