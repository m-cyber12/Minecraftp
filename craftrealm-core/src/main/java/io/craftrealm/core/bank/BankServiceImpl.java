package io.craftrealm.core.bank;

import io.craftrealm.api.model.BankAccount;
import io.craftrealm.api.model.EconomyResult;
import io.craftrealm.api.model.Loan;
import io.craftrealm.api.model.LoanStatus;
import io.craftrealm.api.model.TransactionType;
import io.craftrealm.api.service.BankService;
import io.craftrealm.api.service.EconomyService;
import io.craftrealm.core.config.BankSettings;
import io.craftrealm.core.db.Database;
import io.craftrealm.core.repo.BankRepository;
import io.craftrealm.core.repo.LoanRepository;
import io.craftrealm.core.repo.TransactionRepository;
import io.craftrealm.core.util.Money;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Savings, interest and loans.
 *
 * <p>The bank owns its own table, so a vault balance is not part of the wallet
 * money supply until it is withdrawn. Every movement into or out of the vault is
 * still written to the global ledger, which is what makes a missing Crown
 * traceable.</p>
 */
public final class BankServiceImpl implements BankService {

    private final Database database;
    private final BankRepository accounts;
    private final LoanRepository loans;
    private final TransactionRepository ledger;
    private final EconomyService economy;
    private final BankSettings settings;
    private final Map<UUID, List<Loan>> loanCache = new ConcurrentHashMap<>();

    public BankServiceImpl(Database database, EconomyService economy, BankSettings settings) {
        this.database = database;
        this.accounts = new BankRepository(database);
        this.loans = new LoanRepository(database);
        this.ledger = new TransactionRepository(database);
        this.economy = economy;
        this.settings = settings;
    }

    // ------------------------------------------------------------------ vault

    @Override
    public CompletableFuture<BankAccount> account(UUID uuid) {
        return accounts.ensure(uuid);
    }

    @Override
    public CompletableFuture<EconomyResult> deposit(UUID uuid, double amount) {
        double value = Money.round(amount);
        if (!Money.isValidAmount(value)) {
            return CompletableFuture.completedFuture(EconomyResult.fail(EconomyResult.Reason.INVALID_AMOUNT));
        }
        return accounts.ensure(uuid).thenCompose(account ->
                economy.withdraw(uuid, value, TransactionType.BANK_DEPOSIT, "bank deposit")
                    .thenCompose(result -> {
                        if (!result.success()) {
                            return CompletableFuture.completedFuture(result);
                        }
                        return accounts.add(uuid, value).thenApply(added -> {
                            if (!added) {
                                // Compensation: put the money straight back in the wallet.
                                economy.deposit(uuid, value, TransactionType.AUCTION_REFUND, "bank deposit rollback");
                                return EconomyResult.fail(EconomyResult.Reason.ERROR, result.balanceAfter());
                            }
                            return result;
                        });
                    }));
    }

    @Override
    public CompletableFuture<EconomyResult> withdraw(UUID uuid, double amount) {
        double value = Money.round(amount);
        if (!Money.isValidAmount(value)) {
            return CompletableFuture.completedFuture(EconomyResult.fail(EconomyResult.Reason.INVALID_AMOUNT));
        }
        return accounts.ensure(uuid).thenCompose(account ->
                accounts.subtractIfEnough(uuid, value).thenCompose(withdrawn -> {
                    if (!withdrawn) {
                        return CompletableFuture.completedFuture(
                                EconomyResult.fail(EconomyResult.Reason.INSUFFICIENT_FUNDS, account.balance()));
                    }
                    return economy.deposit(uuid, value, TransactionType.BANK_WITHDRAW, "bank withdrawal")
                            .thenApply(result -> {
                                if (!result.success()) {
                                    accounts.add(uuid, value);
                                }
                                return result;
                            });
                }));
    }

    @Override
    public CompletableFuture<Double> accruedInterest(UUID uuid) {
        return accounts.ensure(uuid).thenApply(account -> InterestCalculator.accrued(
                account.balance(), settings.interestFraction(), account.lastInterestAt(),
                System.currentTimeMillis(), settings.periodMillis(), settings.maxInterestBalance()));
    }

    @Override
    public CompletableFuture<Double> accrue(UUID uuid) {
        return accounts.ensure(uuid).thenCompose(account -> {
            long now = System.currentTimeMillis();
            double interest = InterestCalculator.accrued(account.balance(), settings.interestFraction(),
                    account.lastInterestAt(), now, settings.periodMillis(), settings.maxInterestBalance());
            if (interest < 0.01) {
                return CompletableFuture.completedFuture(0.0);
            }
            return accounts.creditInterest(uuid, interest, now).thenCompose(credited -> {
                if (!credited) {
                    return CompletableFuture.completedFuture(0.0);
                }
                return ledger.insert(TransactionType.BANK_INTEREST, null, uuid, interest,
                                "savings interest", null, null, false, 0, "bank")
                        .thenApply(ignored -> interest);
            });
        });
    }

    @Override
    public CompletableFuture<Integer> runInterestCycle() {
        long cutoff = System.currentTimeMillis() - settings.periodMillis();
        return accounts.due(cutoff).thenCompose(due -> {
            CompletableFuture<?>[] futures = due.stream()
                    .map(account -> accrue(account.owner()).exceptionally(error -> 0.0))
                    .toArray(CompletableFuture[]::new);
            return CompletableFuture.allOf(futures).thenApply(ignored -> due.size());
        });
    }

    // ------------------------------------------------------------------ loans

    @Override
    public CompletableFuture<Double> loanLimit(UUID uuid) {
        if (!settings.loansEnabled()) {
            return CompletableFuture.completedFuture(0.0);
        }
        return economy.balance(uuid).thenCompose(wallet ->
                loans.outstandingTotal(uuid).thenApply(outstanding -> {
                    if (outstanding > 0) {
                        return 0.0; // one loan at a time keeps the debt story simple
                    }
                    return LoanSchedule.maxPrincipal(wallet, settings.loanLimitMultiplier(), settings.maxLoan());
                }));
    }

    @Override
    public CompletableFuture<Loan> takeLoan(UUID uuid, double amount) {
        if (!settings.loansEnabled()) {
            return CompletableFuture.completedFuture(null);
        }
        double principal = Money.round(amount);
        if (!Money.isValidAmount(principal)) {
            return CompletableFuture.completedFuture(null);
        }
        return loanLimit(uuid).thenCompose(limit -> {
            if (principal > limit) {
                return CompletableFuture.completedFuture(null);
            }
            long now = System.currentTimeMillis();
            long due = LoanSchedule.dueDate(now, settings.termDays());
            double owed = LoanSchedule.totalOwed(principal, settings.dailyRateFraction(), settings.termDays());
            return loans.create(uuid, principal, owed, settings.dailyRateFraction(), due)
                    .thenCompose(loan -> economy.deposit(uuid, principal, TransactionType.LOAN_ISSUE,
                            "loan #" + loan.id()).thenApply(result -> {
                        if (!result.success()) {
                            loans.updateOutstanding(loan.id(), owed, LoanStatus.REPAID);
                            return null;
                        }
                        invalidate(uuid);
                        return loan;
                    }));
        });
    }

    @Override
    public CompletableFuture<EconomyResult> repay(UUID uuid, long loanId, double amount) {
        double value = Money.round(amount);
        if (!Money.isValidAmount(value)) {
            return CompletableFuture.completedFuture(EconomyResult.fail(EconomyResult.Reason.INVALID_AMOUNT));
        }
        return loans.find(loanId).thenCompose(optional -> {
            if (optional.isEmpty() || !optional.get().owner().equals(uuid)) {
                return CompletableFuture.completedFuture(EconomyResult.fail(EconomyResult.Reason.NO_ACCOUNT));
            }
            Loan loan = optional.get();
            if (loan.status() != LoanStatus.ACTIVE) {
                return CompletableFuture.completedFuture(EconomyResult.fail(EconomyResult.Reason.ERROR));
            }
            double payment = Money.round(Math.min(value, loan.outstanding()));
            return economy.withdraw(uuid, payment, TransactionType.LOAN_REPAY, "loan repayment #" + loanId)
                    .thenCompose(result -> {
                        if (!result.success()) {
                            return CompletableFuture.completedFuture(result);
                        }
                        return loans.repay(loanId, payment).thenApply(repaid -> {
                            if (!repaid) {
                                economy.deposit(uuid, payment, TransactionType.AUCTION_REFUND, "repayment rollback");
                                return EconomyResult.fail(EconomyResult.Reason.ERROR, result.balanceAfter());
                            }
                            double remaining = Money.round(loan.outstanding() - payment);
                            if (remaining <= 0.009) {
                                loans.updateOutstanding(loanId, 0.0, LoanStatus.REPAID);
                            }
                            invalidate(uuid);
                            return result;
                        });
                    });
        });
    }

    @Override
    public List<Loan> loansCached(UUID uuid) {
        return loanCache.getOrDefault(uuid, List.of());
    }

    @Override
    public CompletableFuture<List<Loan>> loans(UUID uuid) {
        return loans.byOwner(uuid).thenApply(list -> {
            loanCache.put(uuid, list);
            return list;
        });
    }

    @Override
    public CompletableFuture<Integer> processOverdue() {
        return loans.overdue(System.currentTimeMillis()).thenCompose(overdue -> {
            CompletableFuture<?>[] work = overdue.stream()
                    .map(this::handleOverdue)
                    .toArray(CompletableFuture[]::new);
            return CompletableFuture.allOf(work).thenApply(ignored -> overdue.size());
        });
    }

    /**
     * Applies the one-off penalty when the grace period ends, then seizes wallet
     * funds towards the debt. Seizure is capped so a player is never pushed
     * negative.
     */
    private CompletableFuture<Void> handleOverdue(Loan loan) {
        long now = System.currentTimeMillis();
        long graceEnd = loan.dueAt() + settings.defaultAfterDays() * 86_400_000L;
        if (now < graceEnd) {
            return CompletableFuture.completedFuture(null);
        }
        // The penalty is applied exactly once: it happens on the ACTIVE -> DEFAULTED
        // transition, and `loans.overdue` only ever returns ACTIVE rows.
        double owed = LoanSchedule.penalty(loan.outstanding(), settings.penaltyPercent());
        return loans.updateOutstanding(loan.id(), owed, LoanStatus.DEFAULTED)
                .thenCompose(ignored -> economy.balance(loan.owner()))
                .thenCompose(wallet -> {
                    double seize = Money.round(Math.min(Math.max(0.0, wallet), owed));
                    if (seize < 0.01) {
                        return CompletableFuture.completedFuture(null);
                    }
                    return economy.withdraw(loan.owner(), seize, TransactionType.LOAN_PENALTY,
                                    "debt collection #" + loan.id())
                            .thenCompose(result -> {
                                if (!result.success()) {
                                    return CompletableFuture.completedFuture(null);
                                }
                                double remaining = Money.round(owed - seize);
                                return loans.updateOutstanding(loan.id(), remaining,
                                                remaining <= 0.009 ? LoanStatus.REPAID : LoanStatus.DEFAULTED)
                                        .thenApply(done -> null);
                            });
                });
    }

    private void invalidate(UUID uuid) {
        loans.byOwner(uuid).thenAccept(list -> loanCache.put(uuid, list));
    }

    /** Total currently owed by all players — shown on the economy dashboard. */
    public CompletableFuture<Double> totalOutstandingLoans() {
        return loans.totalLent();
    }

    public CompletableFuture<Double> totalDeposits() {
        return accounts.totalDeposits();
    }

    public BankSettings settings() {
        return settings;
    }

    /** Exposed for the web health page; the database handle stays package-private. */
    Database database() {
        return database;
    }
}
