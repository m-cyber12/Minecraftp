package io.craftrealm.core.bank;

import io.craftrealm.core.util.Money;

/**
 * Simple interest on savings. Pure maths, unit tested.
 *
 * <p>Interest is credited per completed period, not compounded per second: a
 * player who deposits and withdraws all day earns exactly what someone who
 * leaves the money alone earns, which keeps the bank predictable.</p>
 */
public final class InterestCalculator {

    private InterestCalculator() {
    }

    /** Completed periods between the last credit and now. */
    public static long periodsElapsed(long lastInterestAt, long now, long periodMillis) {
        if (periodMillis <= 0 || now <= lastInterestAt) {
            return 0L;
        }
        return (now - lastInterestAt) / periodMillis;
    }

    /**
     * Interest owed right now.
     *
     * @param balance        savings balance
     * @param rate           fraction per period (0.015 = 1.5%)
     * @param lastInterestAt epoch millis of the last credit
     * @param now            current epoch millis
     * @param periodMillis   length of one period
     * @param maxBalance     balances above this do not earn interest
     */
    public static double accrued(double balance, double rate, long lastInterestAt, long now,
                                 long periodMillis, double maxBalance) {
        if (balance <= 0 || rate <= 0) {
            return 0.0;
        }
        long periods = periodsElapsed(lastInterestAt, now, periodMillis);
        if (periods <= 0) {
            return 0.0;
        }
        double earning = maxBalance > 0 ? Math.min(balance, maxBalance) : balance;
        double interest = earning * rate * periods;
        return Money.round(interest);
    }

    /** When the next credit is due. */
    public static long nextPayout(long lastInterestAt, long periodMillis) {
        return lastInterestAt <= 0 ? System.currentTimeMillis() : lastInterestAt + periodMillis;
    }
}
