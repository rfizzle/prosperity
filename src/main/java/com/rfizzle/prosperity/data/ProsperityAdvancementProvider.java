package com.rfizzle.prosperity.data;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.advancement.InstancedLootTrigger.TriggerInstance;
import com.rfizzle.prosperity.advancement.ProsperityCriteria;
import com.rfizzle.prosperity.item.ProsperityItems;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.datagen.v1.FabricDataOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricAdvancementProvider;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.advancements.Criterion;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;

/**
 * Datagen for Prosperity's milestone advancement tab (issue #50). Every advancement is granted by the
 * one {@link ProsperityCriteria#INSTANCED_LOOT} trigger with a different predicate, so the whole tab is
 * driven server-side at loot-generation time and needs no hand-written JSON.
 *
 * <p>Three chains hang off the root, mirroring the three milestone families in the issue:
 * <ul>
 *   <li><b>Tier</b> — first instanced container opened in each distance tier, in geographic order
 *       (Frontier → Wilderness → Outlands → Depths).</li>
 *   <li><b>Volume</b> — loot 10 / 50 / 250 instanced containers total.</li>
 *   <li><b>Variety</b> — loot containers in 3 / 8 / 15 distinct structure types.</li>
 * </ul>
 * Icons are vanilla items (the root uses the mod's Prospector's Compass); titles and descriptions are
 * {@code advancements.prosperity.*} translation keys.
 */
public final class ProsperityAdvancementProvider extends FabricAdvancementProvider {

    private static final ResourceLocation BACKGROUND =
            ResourceLocation.withDefaultNamespace("textures/gui/advancements/backgrounds/adventure.png");

    public ProsperityAdvancementProvider(FabricDataOutput output,
            CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries);
    }

    @Override
    public void generateAdvancement(HolderLookup.Provider registries, Consumer<AdvancementHolder> consumer) {
        AdvancementHolder root = Advancement.Builder.advancement()
                .display(
                        ProsperityItems.PROSPECTORS_COMPASS,
                        title("root"),
                        description("root"),
                        BACKGROUND,
                        AdvancementType.TASK,
                        true,
                        false,
                        false)
                .addCriterion("looted_container", criterion(TriggerInstance.containers(1)))
                .save(consumer, id("root"));

        // Tier chain — one grant the first time a container generates in each tier, so it is linear in
        // the same order the distances escalate. Depths caps the chain as a challenge.
        AdvancementHolder frontier = tierChild(consumer, root, "tier_frontier", Items.MAP,
                AdvancementType.TASK, "frontier");
        AdvancementHolder wilderness = tierChild(consumer, frontier, "tier_wilderness", Items.COMPASS,
                AdvancementType.GOAL, "wilderness");
        AdvancementHolder outlands = tierChild(consumer, wilderness, "tier_outlands", Items.SPYGLASS,
                AdvancementType.GOAL, "outlands");
        tierChild(consumer, outlands, "tier_depths", Items.RECOVERY_COMPASS,
                AdvancementType.CHALLENGE, "depths");

        // Volume chain — running lifetime total of containers looted.
        AdvancementHolder volume10 = countChild(consumer, root, "volume_10", Items.CHEST,
                AdvancementType.TASK, 10L);
        AdvancementHolder volume50 = countChild(consumer, volume10, "volume_50", Items.GOLD_INGOT,
                AdvancementType.GOAL, 50L);
        countChild(consumer, volume50, "volume_250", Items.DIAMOND,
                AdvancementType.CHALLENGE, 250L);

        // Variety chain — distinct structure types a container has been looted in.
        AdvancementHolder variety3 = structureChild(consumer, root, "variety_3", Items.CARTOGRAPHY_TABLE,
                AdvancementType.TASK, 3);
        AdvancementHolder variety8 = structureChild(consumer, variety3, "variety_8", Items.FILLED_MAP,
                AdvancementType.GOAL, 8);
        structureChild(consumer, variety8, "variety_15", Items.ECHO_SHARD,
                AdvancementType.CHALLENGE, 15);
    }

    private AdvancementHolder tierChild(Consumer<AdvancementHolder> consumer, AdvancementHolder parent,
            String name, ItemLike icon, AdvancementType type, String tierName) {
        return child(consumer, parent, name, icon, type, "reached_tier", TriggerInstance.tier(tierName));
    }

    private AdvancementHolder countChild(Consumer<AdvancementHolder> consumer, AdvancementHolder parent,
            String name, ItemLike icon, AdvancementType type, long min) {
        return child(consumer, parent, name, icon, type, "looted_containers", TriggerInstance.containers(min));
    }

    private AdvancementHolder structureChild(Consumer<AdvancementHolder> consumer, AdvancementHolder parent,
            String name, ItemLike icon, AdvancementType type, int min) {
        return child(consumer, parent, name, icon, type, "distinct_structures", TriggerInstance.structures(min));
    }

    private AdvancementHolder child(Consumer<AdvancementHolder> consumer, AdvancementHolder parent,
            String name, ItemLike icon, AdvancementType type, String criterionName, TriggerInstance instance) {
        return Advancement.Builder.advancement()
                .parent(parent)
                .display(icon, title(name), description(name), null, type, true, true, false)
                .addCriterion(criterionName, criterion(instance))
                .save(consumer, id(name));
    }

    private static Criterion<TriggerInstance> criterion(TriggerInstance instance) {
        return ProsperityCriteria.INSTANCED_LOOT.createCriterion(instance);
    }

    private static Component title(String name) {
        return Component.translatable("advancements.prosperity." + name + ".title");
    }

    private static Component description(String name) {
        return Component.translatable("advancements.prosperity." + name + ".description");
    }

    private static String id(String name) {
        return Prosperity.id(name).toString();
    }
}
