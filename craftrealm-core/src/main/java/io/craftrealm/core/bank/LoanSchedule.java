package io.craftrealm.core.bank;

import io.craftrealm.core.util.Money;

/**
 * Loan arithmetic: how much may be borrowed, what it costs and when it must be
 * repaid. Pure functions, unit tested.
 *
 * <p>Model: the loan is issued with a fixed term and a fixed daily rate. The
 * total owed is quoted up-front ({@code principal × (1 + dailyRate × termDays)})
 * so the player knows the exact figure before accepting. Missing the due date by
 * more than the grace period applies a one-off penalty.</p>
 */
public final class LoanSchedule {

    private LoanSchedule() {
    }

    /** Largest loan a player may take, given their wallet and the server limits. */
    public static double maxPrincipal(double walletBalance, double multiplier, double maxLoan) {
        double byWallet = Math.max(0.0, walletBalance) * Math.max(0.0, multiplier);
        double limit = maxLoan > 0 ? Math.min(byWallet, maxLoan) : byWallet;
        return Money.round(limit);
    }

    /** Total repayment owed at the due date for a principal. */
    public static double totalOwed(double principal, double dailyRate, int termDays) {
        double factor = 1.0 + Math.max(0.0, dailyRate) * Math.max(1, termDays);
        return Money.round(principal * factor);
    }

    /** One-off default penalty. */
    public static double penalty(double outstanding, double penaltyPercent) {
        if (outstanding <= 0 || penaltyPercent <= 0) {
            return Money.round(Math.max(0.0, outstanding));
        }
        return Money.round(outstanding * (1.0 + penaltyPercent / 100.0));
    }

    /** Epoch millis at which a loan taken now falls due. */
    public static long dueDate(long now, int termDays) {
        return now + Math.max(1, termDays) * 86_400_000L;
    }

    /** Fraction of a loan already repaid, clamped to {@code [0,1]}. */
    public static double repaymentProgress(double principal, double outstanding) {
        if (principal <= 0) {
            return 1.0;
        }
        return Math.max(0.0, Math.min(1.0, 1.0 - outstanding / principal));
    }
}
