package io.craftrealm.core.config;

/**
 * Tuning for the supply/demand market.
 *
 * @param buyMultiplier   what players pay on top of the market price
 * @param elasticity      price movement per reference volume traded (0.02 = 2%)
 * @param referenceVolume units that make up one "elasticity" step
 * @param minFactor       price floor as a fraction of base
 * @param maxFactor       price ceiling as a fraction of base
 * @param recoveryPerHour fraction of the gap back to base recovered each hour
 * @param trendThreshold  minimum deviation from base before an arrow is shown
 */
public record MarketSettings(double buyMultiplier, double elasticity, int referenceVolume,
                             double minFactor, double maxFactor, double recoveryPerHour,
                             double trendThreshold) {

    public static MarketSettings defaults() {
        return new MarketSettings(1.25, 0.02, 64, 0.35, 3.0, 0.08, 0.03);
    }
}
