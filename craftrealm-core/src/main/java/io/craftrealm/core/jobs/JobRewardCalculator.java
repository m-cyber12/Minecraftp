package io.craftrealm.core.jobs;

import io.craftrealm.core.util.Money;

/**
 * Turns "player mined one diamond" into a Crown amount, honouring the level
 * multiplier, the daily cap and the global event multiplier.
 */
public final class JobRewardCalculator {

    private final double multiplierPerLevel;
    private final double dailyCap;

    public JobRewardCalculator(double multiplierPerLevel, double dailyCap) {
        this.multiplierPerLevel = multiplierPerLevel;
        this.dailyCap = dailyCap;
    }

    /** Payout before the daily cap is applied. */
    public double rawPayout(double basePayout, int level, double eventMultiplier) {
        if (basePayout <= 0) {
            return 0.0;
        }
        return Money.round(basePayout * JobLevelCurve.multiplier(level, multiplierPerLevel)
                * Math.max(1.0, eventMultiplier));
    }

    /**
     * Payout after the cap.
     *
     * @param earnedToday Crowns already paid today in this job
     * @return the amount to actually pay, never negative
     */
    public double cappedPayout(double basePayout, int level, double eventMultiplier, double earnedToday) {
        double raw = rawPayout(basePayout, level, eventMultiplier);
        if (dailyCap <= 0) {
            return raw;
        }
        double remaining = Math.max(0.0, dailyCap - Math.max(0.0, earnedToday));
        return Money.round(Math.min(raw, remaining));
    }

    public double dailyCap() {
        return dailyCap;
    }
}
