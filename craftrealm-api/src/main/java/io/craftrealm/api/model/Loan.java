package io.craftrealm.api.model;

import java.util.UUID;

/**
 * A loan issued by the bank.
 *
 * <p>Interest is simple (not compounding) and charged once per day on the
 * outstanding principal; see {@code LoanSchedule} for the maths.</p>
 *
 * @param id          row id
 * @param owner       borrower
 * @param principal   originally borrowed amount
 * @param outstanding remaining debt (principal + unpaid interest)
 * @param dailyRate   fraction charged per day, e.g. {@code 0.02} for 2%
 * @param dueAt       epoch millis the loan must be settled by
 * @param createdAt   epoch millis
 * @param status      lifecycle state
 */
public record Loan(long id, UUID owner, double principal, double outstanding,
                   double dailyRate, long dueAt, long createdAt, LoanStatus status) {

    public boolean overdue(long now) {
        return status == LoanStatus.ACTIVE && now > dueAt;
    }

    public double progress() {
        if (principal <= 0) {
            return 1.0;
        }
        return Math.max(0.0, Math.min(1.0, 1.0 - (outstanding / principal)));
    }
}
