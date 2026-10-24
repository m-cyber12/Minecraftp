package io.craftrealm.core.market;

import io.craftrealm.api.model.PriceTrend;
import io.craftrealm.core.config.MarketSettings;
import io.craftrealm.core.util.Money;

/**
 * Supply and demand, as maths.
 *
 * <p>The model is deliberately simple enough to explain to a player in one line:
 * every unit sold adds pressure, every unit bought removes it, and the price is
 * {@code base × (1 − pressure)}. Pressure decays back towards zero over time, so
 * a flooded market recovers if players stop dumping wheat.</p>
 *
 * <p>Pressure is clamped so the price always stays inside
 * {@code [minFactor, maxFactor] × base} — no material can become worthless or
 * unobtainable.</p>
 *
 * <p>Pure and stateless: no Bukkit, no database. Everything here is unit tested.</p>
 */
public final class MarketPriceEngine {

    private final MarketSettings settings;

    public MarketPriceEngine(MarketSettings settings) {
        this.settings = settings;
    }

    /** Pressure change caused by selling {@code amount} units to the market. */
    public double pressureAfterSell(double pressure, int amount) {
        if (amount <= 0) {
            return pressure;
        }
        return clampPressure(pressure + step() * amount);
    }

    /** Pressure change caused by buying {@code amount} units from the market. */
    public double pressureAfterBuy(double pressure, int amount) {
        if (amount <= 0) {
            return pressure;
        }
        return clampPressure(pressure - step() * amount);
    }

    /**
     * Lets pressure decay towards equilibrium.
     *
     * @param hours elapsed hours
     */
    public double pressureAfterRecovery(double pressure, double hours) {
        if (hours <= 0) {
            return pressure;
        }
        double keep = Math.pow(1.0 - Math.min(1.0, Math.max(0.0, settings.recoveryPerHour())), hours);
        return clampPressure(pressure * keep);
    }

    /** Price implied by a pressure value. */
    public double priceFor(double basePrice, double pressure) {
        if (basePrice <= 0) {
            return 0.0;
        }
        double factor = Math.max(settings.minFactor(),
                Math.min(settings.maxFactor(), 1.0 - pressure));
        return Money.round(basePrice * factor);
    }

    /** Direction of the price relative to its anchor, with a dead-band. */
    public PriceTrend trend(double basePrice, double price) {
        if (basePrice <= 0) {
            return PriceTrend.STABLE;
        }
        double ratio = price / basePrice;
        if (ratio > 1.0 + settings.trendThreshold()) {
            return PriceTrend.RISING;
        }
        if (ratio < 1.0 - settings.trendThreshold()) {
            return PriceTrend.FALLING;
        }
        return PriceTrend.STABLE;
    }

    /** What players pay when buying from the market. */
    public double buyPrice(double marketPrice) {
        return Money.round(marketPrice * Math.max(1.0, settings.buyMultiplier()));
    }

    private double step() {
        return settings.elasticity() / Math.max(1, settings.referenceVolume());
    }

    private double clampPressure(double pressure) {
        double lower = 1.0 - settings.maxFactor();
        double upper = 1.0 - settings.minFactor();
        return Math.max(lower, Math.min(upper, pressure));
    }
}
