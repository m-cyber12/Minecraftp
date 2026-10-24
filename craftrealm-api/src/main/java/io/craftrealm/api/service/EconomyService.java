package io.craftrealm.api.service;

import io.craftrealm.api.model.BalanceEntry;
import io.craftrealm.api.model.EconomyResult;
import io.craftrealm.api.model.Transaction;
import io.craftrealm.api.model.TransactionType;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Crowns — the primary currency.
 *
 * <p>All mutations are asynchronous, atomic and appended to the ledger. Reads of
 * {@link #cachedBalance(UUID)} are instant and are what gameplay code should use
 * for display; {@link #balance(UUID)} hits the database.</p>
 */
public interface EconomyService {

    /** Configured display name, e.g. {@code Crowns}. */
    String currencyName();

    /** Configured symbol, e.g. {@code ⛃}. */
    String currencySymbol();

    /** Decimal places used for display and rounding. */
    int decimals();

    /** e.g. {@code "1,250.00 ⛃"}. Safe to call from any thread. */
    String format(double amount);

    /** Compact form for GUIs, e.g. {@code "1.2k"}. */
    String formatShort(double amount);

    /** Cached balance, {@code 0} if the account is not loaded. Main-thread safe. */
    double cachedBalance(UUID uuid);

    /** Authoritative balance, loaded from storage. */
    CompletableFuture<Double> balance(UUID uuid);

    /** Loads (or creates) an account into the cache, typically on join. */
    CompletableFuture<Void> loadAccount(UUID uuid, String name);

    /** Drops a cached account, typically on quit. */
    void unloadAccount(UUID uuid);

    /** Adds Crowns from a server source. */
    CompletableFuture<EconomyResult> deposit(UUID uuid, double amount, TransactionType type, String reason);

    /** Removes Crowns into a server sink. */
    CompletableFuture<EconomyResult> withdraw(UUID uuid, double amount, TransactionType type, String reason);

    /** Staff override. Always audited. */
    CompletableFuture<EconomyResult> setBalance(UUID uuid, double amount, UUID actor, String reason);

    /** Player to player transfer. */
    default CompletableFuture<EconomyResult> transfer(UUID from, UUID to, double amount,
                                                      TransactionType type, String reason) {
        return transfer(from, to, amount, type, reason, null);
    }

    /**
     * Player to player transfer with an optional idempotency key. Replaying an
     * operation id that already exists in the ledger fails with
     * {@link EconomyResult.Reason#DUPLICATE_OPERATION} instead of moving money twice.
     */
    CompletableFuture<EconomyResult> transfer(UUID from, UUID to, double amount,
                                              TransactionType type, String reason, String operationId);

    /**
     * Credits an escrow refund. The funds are recorded with the given operation id
     * so a retried collection cannot pay twice.
     */
    CompletableFuture<EconomyResult> credit(UUID uuid, double amount, TransactionType type,
                                            String reason, String operationId);

    /** Highest balances, richest first. */
    CompletableFuture<List<BalanceEntry>> top(int limit);

    /** Sum of every stored balance — the "money supply". */
    CompletableFuture<Double> totalInCirculation();

    /** Ledger entries touching this player, newest first. */
    CompletableFuture<List<Transaction>> history(UUID uuid, int limit);

    /** Global ledger tail, newest first. */
    CompletableFuture<List<Transaction>> recent(int limit);

    /** Transactions above the configured alert threshold, newest first. */
    CompletableFuture<List<Transaction>> suspicious(int limit, double threshold);

    /**
     * Reverses one transaction atomically (money goes back where it came from).
     *
     * @return the amount that was actually reversed, {@code 0} when not possible
     */
    CompletableFuture<Double> rollback(long transactionId, UUID staff);
}
