package com.rfizzle.prosperity.data;

import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;

public final class ProsperityDataGenerator implements DataGeneratorEntrypoint {
    @Override
    public void onInitializeDataGenerator(FabricDataGenerator fabricDataGenerator) {
        // Prosperity's data (loot injections, marker files) is hand-authored under
        // src/main/resources; no generated providers are registered. The entrypoint stays
        // wired so a provider can be added later without re-plumbing datagen.
    }
}
