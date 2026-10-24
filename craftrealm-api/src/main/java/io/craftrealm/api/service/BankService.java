package io.craftrealm.api.service;

import io.craftrealm.api.model.BankAccount;
import io.craftrealm.api.model.EconomyResult;
import io.craftrealm.api.model.Loan;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The player bank: savings with a daily interest rate, plus loans with a due
 * date and penalties for late repayment.
 */
public interface BankService {

    CompletableFuture<BankAccount> account(UUID uuid);

    /** Crowns from the wallet into the vault. */
    CompletableFuture<EconomyResult> deposit(UUID uuid, double amount);

    /** Crowns from the vault into the wallet. */
    CompletableFuture<EconomyResult> withdraw(UUID uuid, double amount);

    /** Interest that would be credited right now (for the GUI preview). */
    CompletableFuture<Double> accruedInterest(UUID uuid);

    /** Credits interest to one account if a full period has elapsed. */
    CompletableFuture<Double> accrue(UUID uuid);

    /** Scheduled: credits interest to every account that is due. */
    CompletableFuture<Integer> runInterestCycle();

    /** Largest loan the player may take right now. */
    CompletableFuture<Double> loanLimit(UUID uuid);

    /** Opens a loan, paying the principal into the wallet. */
    CompletableFuture<Loan> takeLoan(UUID uuid, double amount);

    /** Repays from the wallet, oldest loan first. */
    CompletableFuture<EconomyResult> repay(UUID uuid, long loanId, double amount);

    List<Loan> loansCached(UUID uuid);

    CompletableFuture<List<Loan>> loans(UUID uuid);

    /** Scheduled: marks overdue loans and applies the default penalty. */
    CompletableFuture<Integer> processOverdue();
}
