package io.craftrealm.core.bank;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InterestCalculatorTest {

    private static final long DAY = 86_400_000L;

    @Test
    void noInterestBeforeAFullPeriod() {
        long now = 10 * DAY;
        assertEquals(0.0, InterestCalculator.accrued(1000, 0.015, now - DAY / 2, now, DAY, 0));
    }

    @Test
    void creditsOnePeriodOfSimpleInterest() {
        long now = 10 * DAY;
        assertEquals(15.0, InterestCalculator.accrued(1000, 0.015, now - DAY, now, DAY, 0), 0.0001);
    }

    @Test
    void multipleMissedPeriodsStack() {
        long now = 10 * DAY;
        double interest = InterestCalculator.accrued(1000, 0.015, now - 3 * DAY, now, DAY, 0);
        assertEquals(45.0, interest, 0.0001);
    }

    @Test
    void balanceAboveTheCapEarnsNothingExtra() {
        long now = 10 * DAY;
        double capped = InterestCalculator.accrued(1_000_000, 0.015, now - DAY, now, DAY, 500_000);
        assertEquals(7_500.0, capped, 0.0001);
    }

    @Test
    void emptyVaultEarnsNothing() {
        long now = 10 * DAY;
        assertEquals(0.0, InterestCalculator.accrued(0, 0.015, now - 5 * DAY, now, DAY, 0));
    }
}
