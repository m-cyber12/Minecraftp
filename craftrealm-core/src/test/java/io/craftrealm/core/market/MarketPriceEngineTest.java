package io.craftrealm.core.market;

import io.craftrealm.api.model.PriceTrend;
import io.craftrealm.core.config.MarketSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketPriceEngineTest {

    private final MarketPriceEngine engine = new MarketPriceEngine(
            new MarketSettings(1.25, 0.02, 64, 0.35, 3.0, 0.08, 0.03));

    @Test
    void sellingLowersThePrice() {
        double pressure = engine.pressureAfterSell(0.0, 64);
        // 64 units at 2% elasticity over a 64 unit reference = one full step.
        assertEquals(0.02, pressure, 0.0001);
        assertEquals(9.8, engine.priceFor(10.0, pressure), 0.0001);
    }

    @Test
    void buyingRaisesThePrice() {
        double pressure = engine.pressureAfterBuy(0.0, 64);
        assertTrue(engine.priceFor(10.0, pressure) > 10.0);
    }

    @Test
    void priceNeverFallsBelowTheFloor() {
        double pressure = 0.0;
        for (int i = 0; i < 10_000; i++) {
            pressure = engine.pressureAfterSell(pressure, 64);
        }
        assertEquals(3.5, engine.priceFor(10.0, pressure), 0.0001);
    }

    @Test
    void priceNeverExceedsTheCeiling() {
        double pressure = 0.0;
        for (int i = 0; i < 10_000; i++) {
            pressure = engine.pressureAfterBuy(pressure, 64);
        }
        assertEquals(30.0, engine.priceFor(10.0, pressure), 0.0001);
    }

    @Test
    void pressureRecoversTowardsEquilibrium() {
        double pressure = 0.6;
        double afterOneHour = engine.pressureAfterRecovery(pressure, 1);
        double afterTenHours = engine.pressureAfterRecovery(pressure, 10);
        assertTrue(afterOneHour < pressure);
        assertTrue(afterTenHours < afterOneHour);
        assertTrue(afterTenHours > 0);
    }

    @Test
    void trendUsesADeadBand() {
        assertEquals(PriceTrend.STABLE, engine.trend(10.0, 10.1));
        assertEquals(PriceTrend.RISING, engine.trend(10.0, 12.0));
        assertEquals(PriceTrend.FALLING, engine.trend(10.0, 8.0));
    }

    @Test
    void buyPriceAddsTheSpread() {
        assertEquals(12.5, engine.buyPrice(10.0), 0.0001);
    }
}
