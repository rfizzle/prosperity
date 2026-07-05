package com.rfizzle.prosperity.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

/**
 * A single parameterized criterion trigger fired once per real instanced-loot generation (issue #50).
 * It backs every Prosperity milestone advancement — tier, volume, and variety — so there is one
 * trigger to register, fire, and test rather than one per milestone.
 *
 * <p>Fired from {@code InstancedLootInteraction.recordStats}, the mod's single generation choke point,
 * which both container paths reach only after their return-visit / blacklist / vanilla-passthrough
 * early returns. So the trigger inherently counts real first-generations and refresh re-rolls, never
 * return visits — satisfying the issue's "respect blacklists, only count real generations" requirement
 * without any gating of its own.
 *
 * <p>The instance predicate carries three optional fields; an advancement sets only the one it cares
 * about and leaves the rest absent (a matcher for an absent field always passes):
 * <ul>
 *   <li>{@code tier} — exact effective-tier-name match, for the per-tier advancements. Because an
 *       advancement grants only once, "first container in tier X" needs no extra state: the first
 *       generation in that tier that satisfies the predicate grants it.</li>
 *   <li>{@code min_containers} — the running lifetime total this generation brought the player to must
 *       be at least this, for the volume advancements.</li>
 *   <li>{@code min_structures} — the running count of distinct structure types must be at least this,
 *       for the variety advancements.</li>
 * </ul>
 * The running totals are read from the player's {@code LootStatsData} at the same call site that
 * records them, so they already reflect this generation and persist across relog/restart.
 */
public final class InstancedLootTrigger extends SimpleCriterionTrigger<InstancedLootTrigger.TriggerInstance> {

    @Override
    public Codec<TriggerInstance> codec() {
        return TriggerInstance.CODEC;
    }

    /**
     * Fire the trigger for one generation. {@code tierName} is the effective tier of this container,
     * {@code containersLooted} and {@code distinctStructures} are the player's running lifetime totals
     * after this generation was recorded.
     */
    public void trigger(ServerPlayer player, String tierName, long containersLooted, int distinctStructures) {
        this.trigger(player, instance -> instance.matches(tierName, containersLooted, distinctStructures));
    }

    public record TriggerInstance(Optional<ContextAwarePredicate> player, Optional<String> tier,
            Optional<Long> minContainers, Optional<Integer> minStructures)
            implements SimpleCriterionTrigger.SimpleInstance {

        public static final Codec<TriggerInstance> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player")
                                .forGetter(TriggerInstance::player),
                        Codec.STRING.optionalFieldOf("tier").forGetter(TriggerInstance::tier),
                        Codec.LONG.optionalFieldOf("min_containers").forGetter(TriggerInstance::minContainers),
                        Codec.INT.optionalFieldOf("min_structures").forGetter(TriggerInstance::minStructures)
                ).apply(instance, TriggerInstance::new));

        /** A single-tier match, for the per-tier advancement chain. */
        public static TriggerInstance tier(String tierName) {
            return new TriggerInstance(Optional.empty(), Optional.of(tierName), Optional.empty(), Optional.empty());
        }

        /** A running-total threshold, for the volume advancement chain. */
        public static TriggerInstance containers(long min) {
            return new TriggerInstance(Optional.empty(), Optional.empty(), Optional.of(min), Optional.empty());
        }

        /** A distinct-structure-count threshold, for the variety advancement chain. */
        public static TriggerInstance structures(int min) {
            return new TriggerInstance(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(min));
        }

        boolean matches(String tierName, long containersLooted, int distinctStructures) {
            if (tier.isPresent() && !tier.get().equals(tierName)) {
                return false;
            }
            if (minContainers.isPresent() && containersLooted < minContainers.get()) {
                return false;
            }
            return minStructures.isEmpty() || distinctStructures >= minStructures.get();
        }
    }
}
