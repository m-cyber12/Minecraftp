package io.craftrealm.core.config;

/**
 * Savings interest and loan terms.
 *
 * @param interestRatePercent interest credited per period, e.g. {@code 1.5} for 1.5%
 * @param periodMillis        length of an interest period
 * @param maxInterestBalance  savings above this do not earn interest
 * @param loansEnabled        master switch
 * @param maxLoan             absolute ceiling for a single loan
 * @param loanLimitMultiplier max loan = wallet balance × this (capped by maxLoan)
 * @param dailyRatePercent    interest charged on the outstanding principal per day
 * @param termDays            days until the loan is due
 * @param penaltyPercent      penalty applied to the outstanding balance on default
 * @param defaultAfterDays    grace period before a loan is marked defaulted
 */
public record BankSettings(double interestRatePercent, long periodMillis, double maxInterestBalance,
                           boolean loansEnabled, double maxLoan, double loanLimitMultiplier,
                           double dailyRatePercent, int termDays, double penaltyPercent,
                           int defaultAfterDays) {

    public static BankSettings defaults() {
        return new BankSettings(1.5, 86_400_000L, 1_000_000.0, true, 25_000.0, 2.0,
                2.0, 7, 10.0, 3);
    }

    public double interestFraction() {
        return interestRatePercent / 100.0;
    }

    public double dailyRateFraction() {
        return dailyRatePercent / 100.0;
    }
}
