package io.craftrealm.core.bank;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoanScheduleTest {

    @Test
    void loanLimitIsWalletTimesMultiplierButCapped() {
        assertEquals(2000.0, LoanSchedule.maxPrincipal(1000, 2.0, 25_000), 0.0001);
        assertEquals(25_000.0, LoanSchedule.maxPrincipal(1_000_000, 2.0, 25_000), 0.0001);
        assertEquals(0.0, LoanSchedule.maxPrincipal(-50, 2.0, 25_000), 0.0001);
    }

    @Test
    void totalOwedUsesTheFixedTerm() {
        // 1000 at 2% per day for 7 days = 1140.
        assertEquals(1140.0, LoanSchedule.totalOwed(1000, 0.02, 7), 0.0001);
    }

    @Test
    void penaltyIsAppliedOnce() {
        assertEquals(110.0, LoanSchedule.penalty(100, 10), 0.0001);
        assertEquals(100.0, LoanSchedule.penalty(100, 0), 0.0001);
    }

    @Test
    void dueDateIsInTheFuture() {
        long now = 1_000_000L;
        assertEquals(now + 7 * 86_400_000L, LoanSchedule.dueDate(now, 7));
    }

    @Test
    void repaymentProgressIsClamped() {
        assertEquals(0.5, LoanSchedule.repaymentProgress(100, 50), 0.0001);
        assertEquals(1.0, LoanSchedule.repaymentProgress(100, -10), 0.0001);
    }
}
