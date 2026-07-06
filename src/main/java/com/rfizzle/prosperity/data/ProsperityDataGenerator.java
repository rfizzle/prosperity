package com.rfizzle.prosperity.data;

import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;

public final class ProsperityDataGenerator implements DataGeneratorEntrypoint {
    @Override
    public void onInitializeDataGenerator(FabricDataGenerator fabricDataGenerator) {
        // Most of Prosperity's data (loot injections, marker files) stays hand-authored under
        // src/main/resources. The milestone advancements (issue #50) are the one datagen'd set — every
        // one is granted by the same criterion trigger, so generating them keeps the predicates in sync
        // with the trigger's field names.
        FabricDataGenerator.Pack pack = fabricDataGenerator.createPack();
        pack.addProvider(ProsperityAdvancementProvider::new);
    }
}
