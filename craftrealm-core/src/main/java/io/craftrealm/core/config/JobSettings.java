package io.craftrealm.core.config;

import io.craftrealm.api.model.JobType;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.Map;

/**
 * Job payouts and progression.
 *
 * @param maxLevel            level cap
 * @param multiplierPerLevel  extra payout per level above 1 (0.05 = +5% per level)
 * @param xpBase              XP needed for level 2
 * @param xpGrowth            multiplier applied per level
 * @param dailyCap            Crowns a single job may pay per day
 * @param xpPerCrown          XP granted per Crown earned (so harder work levels faster)
 * @param payouts             per-material base payouts, per job
 * @param hunterPayouts       per-mob payouts for the Hunter job
 */
public record JobSettings(int maxLevel, double multiplierPerLevel, long xpBase, double xpGrowth,
                          double dailyCap, double xpPerCrown, Map<JobType, Map<Material, Double>> payouts,
                          Map<EntityType, Double> hunterPayouts) {

    public static JobSettings defaults() {
        return new JobSettings(50, 0.05, 100L, 1.15, 5_000.0, 4.0, Map.of(), Map.of());
    }

    /** Base payout for killing a mob. */
    public double hunterPayout(EntityType type) {
        return hunterPayouts.getOrDefault(type, 0.0);
    }

    /** Base payout for a material in a job, {@code 0} when not configured. */
    public double payout(JobType job, Material material) {
        Map<Material, Double> table = payouts.get(job);
        if (table == null) {
            return 0.0;
        }
        return table.getOrDefault(material, 0.0);
    }
}
