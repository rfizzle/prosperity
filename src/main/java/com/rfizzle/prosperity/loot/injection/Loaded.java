package com.rfizzle.prosperity.loot.injection;

import net.minecraft.resources.ResourceLocation;

/** A parsed injection file with its source id, retained for deterministic ordering at build time. */
record Loaded(ResourceLocation id, FileData data) {
}
