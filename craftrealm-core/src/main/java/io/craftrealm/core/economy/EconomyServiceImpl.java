package io.craftrealm.core.economy;

import io.craftrealm.api.event.BalanceChangeEvent;
import io.craftrealm.api.event.TransactionCompletedEvent;
import io.craftrealm.api.model.BalanceEntry;
import io.craftrealm.api.model.EconomyResult;
import io.craftrealm.api.model.Transaction;
import io.craftrealm.api.model.TransactionType;
import io.craftrealm.api.service.EconomyService;
import io.craftrealm.core.config.EconomySettings;
import io.craftrealm.core.db.Database;
import io.craftrealm.core.db.Jdbc;
import io.craftrealm.core.repo.AccountRepository;
import io.craftrealm.core.repo.TransactionRepository;
import io.craftrealm.core.util.Events;
import io.craftrealm.core.util.Money;
import io.craftrealm.core.util.RateLimiter;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The Crowns ledger.
 *
 * <h2>Why this class looks paranoid</h2>
 * <ul>
 *   <li><b>Every write is a transaction.</b> The balance update and the ledger row
 *       are committed together; a crash can never leave money unaccounted for.</li>
 *   <li><b>Debits are conditional SQL.</b> {@code WHERE balance >= ?} means two
 *       concurrent purchases cannot overdraw an account, with or without the
 *       in-memory cache agreeing.</li>
 *   <li><b>Operation ids are unique.</b> Notes, coins and auction collections pass
 *       a key; replaying it is rejected by the database, not by hope.</li>
 *   <li><b>Amounts are validated and rounded</b> before they reach SQL, and each
 *       player is rate limited so a packet flood cannot hammer the pool.</li>
 * </ul>
 *
 * <p>Reads are served from a write-through cache for gameplay; the database stays
 * authoritative and is what {@link #balance(UUID)} returns.</p>
 */
public final class EconomyServiceImpl implements EconomyService {

    /** Thrown inside a transaction to signal "this operation id already ran". */
    private static final class DuplicateOperationException extends RuntimeException {
        private DuplicateOperationException() {
            super("duplicate operation");
        }
    }

    /** Small container for a committed mutation. */
    private record Mutation(EconomyResult result, Transaction transaction) {
    }

    private final Database database;
    private final AccountRepository accounts;
    private final TransactionRepository ledger;
    private final EconomySettings settings;
    private final RateLimiter limiter;
    private final ConcurrentHashMap<UUID, AccountRow> cache = new ConcurrentHashMap<>();

    private volatile BiConsumer<UUID, Transaction> suspiciousSink = (uuid, transaction) -> {
    };
    private volatile Function<UUID, EconomyResult.Reason> guard = uuid -> null;

    public EconomyServiceImpl(Database database, EconomySettings settings) {
        this.database = database;
        this.settings = settings;
        this.accounts = new AccountRepository(database);
        this.ledger = new TransactionRepository(database);
        this.limiter = new RateLimiter(settings.transactionsPerSecond());
    }

    /**
     * Installs the "may this player transact?" hook (frozen accounts, bans, …).
     * Return {@code null} to allow, or a reason to deny.
     */
    public void setTransactionGuard(Function<UUID, EconomyResult.Reason> guard) {
        this.guard = guard == null ? uuid -> null : guard;
    }

    /** Receives transfers above the alert threshold, for staff notifications. */
    public void setSuspiciousSink(BiConsumer<UUID, Transaction> sink) {
        this.suspiciousSink = sink == null ? (uuid, transaction) -> {
        } : sink;
    }

    // ------------------------------------------------------------------ config

    @Override
    public String currencyName() {
        return settings.currencyName();
    }

    @Override
    public String currencySymbol() {
        return settings.currencySymbol();
    }

    @Override
    public int decimals() {
        return settings.decimals();
    }

    @Override
    public String format(double amount) {
        return Money.format(amount, settings.currencySymbol(), settings.decimals(), false);
    }

    @Override
    public String formatShort(double amount) {
        String symbol = settings.currencySymbol();
        return symbol == null || symbol.isEmpty() ? Money.compact(amount) : Money.compact(amount) + " " + symbol;
    }

    // ------------------------------------------------------------------- reads

    @Override
    public double cachedBalance(UUID uuid) {
        AccountRow row = cache.get(uuid);
        return row == null ? 0.0 : row.balance();
    }

    @Override
    public CompletableFuture<Double> balance(UUID uuid) {
        return accounts.balance(uuid);
    }

    @Override
    public CompletableFuture<Void> loadAccount(UUID uuid, String name) {
        return accounts.ensure(uuid, name, settings.startingBalance())
                .thenCompose(ignored -> accounts.find(uuid))
                .thenAccept(row -> row.ifPresent(account -> cache.put(uuid, account)));
    }

    @Override
    public void unloadAccount(UUID uuid) {
        cache.remove(uuid);
        limiter.forget(uuid.toString());
    }

    // --------------------------------------------------------------- mutations

    @Override
    public CompletableFuture<EconomyResult> deposit(UUID uuid, double amount, TransactionType type, String reason) {
        return mutate(uuid, amount, true, type, reason, null, null);
    }

    @Override
    public CompletableFuture<EconomyResult> withdraw(UUID uuid, double amount, TransactionType type, String reason) {
        return mutate(uuid, amount, false, type, reason, null, null);
    }

    @Override
    public CompletableFuture<EconomyResult> credit(UUID uuid, double amount, TransactionType type,
                                                   String reason, String operationId) {
        return mutate(uuid, amount, true, type, reason, null, operationId);
    }

    @Override
    public CompletableFuture<EconomyResult> setBalance(UUID uuid, double amount, UUID actor, String reason) {
        double target = Money.round(amount, settings.decimals());
        if (target < 0 || !Double.isFinite(target)) {
            return CompletableFuture.completedFuture(EconomyResult.fail(EconomyResult.Reason.INVALID_AMOUNT));
        }
        double current = cachedBalance(uuid);
        return fireEvent(uuid, current, target, target - current, TransactionType.ADMIN_SET, reason, actor)
                .thenCompose(allowed -> {
                    if (!allowed) {
                        return CompletableFuture.completedFuture(EconomyResult.fail(EconomyResult.Reason.ERROR, current));
                    }
                    return database.inTransaction(connection -> {
                        if (!AccountRepository.exists(connection, uuid)) {
                            return new Mutation(EconomyResult.fail(EconomyResult.Reason.NO_ACCOUNT, 0.0), null);
                        }
                        AccountRepository.setBalance(connection, uuid, target);
                        long id = TransactionRepository.append(connection, TransactionType.ADMIN_SET, null, null,
                                Money.round(target - current, settings.decimals()), reason, actor, null, false, 0,
                                settings.serverId());
                        return new Mutation(EconomyResult.ok(target), readTransaction(connection, id));
                    }).thenApply(mutation -> publish(uuid, mutation));
                });
    }

    @Override
    public CompletableFuture<EconomyResult> transfer(UUID from, UUID to, double amount, TransactionType type,
                                                     String reason, String operationId) {
        double value = Money.round(amount, settings.decimals());
        EconomyResult validation = validate(from, value, settings.minTransfer());
        if (validation != null) {
            return CompletableFuture.completedFuture(validation);
        }
        if (from.equals(to)) {
            return CompletableFuture.completedFuture(EconomyResult.fail(EconomyResult.Reason.SELF_TRANSFER));
        }
        double current = cachedBalance(from);
        return preCreate(to)
                .thenCompose(ignored -> fireEvent(from, current, Money.round(current - value, settings.decimals()),
                        -value, type, reason, null))
                .thenCompose(allowed -> {
                    if (!allowed) {
                        return CompletableFuture.completedFuture(
                                EconomyResult.fail(EconomyResult.Reason.ERROR, current));
                    }
                    return database.inTransaction(connection -> {
                        if (TransactionRepository.operationExists(connection, operationId)) {
                            throw new DuplicateOperationException();
                        }
                        int debited = AccountRepository.subtractIfEnough(connection, from, value);
                        if (debited == 0) {
                            return new Mutation(EconomyResult.fail(EconomyResult.Reason.INSUFFICIENT_FUNDS,
                                    AccountRepository.balanceOn(connection, from)), null);
                        }
                        int credited = AccountRepository.applyDelta(connection, to, value);
                        if (credited == 0) {
                            throw new IllegalStateException("recipient account vanished: " + to);
                        }
                        long id = TransactionRepository.append(connection, type, from, to, value, reason, null,
                                operationId, false, 0, settings.serverId());
                        double after = AccountRepository.balanceOn(connection, from);
                        return new Mutation(EconomyResult.ok(after), readTransaction(connection, id));
                    }).thenApply(mutation -> publish(from, mutation));
                })
                .exceptionally(throwable -> duplicateOrFail(throwable, from));
    }

    @Override
    public CompletableFuture<List<BalanceEntry>> top(int limit) {
        return accounts.top(limit);
    }

    @Override
    public CompletableFuture<Double> totalInCirculation() {
        return accounts.totalInCirculation();
    }

    @Override
    public CompletableFuture<List<Transaction>> history(UUID uuid, int limit) {
        return ledger.forPlayer(uuid, limit);
    }

    @Override
    public CompletableFuture<List<Transaction>> recent(int limit) {
        return ledger.recent(limit);
    }

    @Override
    public CompletableFuture<List<Transaction>> suspicious(int limit, double threshold) {
        return ledger.above(threshold, limit);
    }

    // ----------------------------------------------------------------- rollback

    @Override
    public CompletableFuture<Double> rollback(long transactionId, UUID staff) {
        return database.inTransaction(connection -> {
            Transaction original = readTransaction(connection, transactionId);
            if (original == null || original.reverted() || original.type() == TransactionType.ROLLBACK) {
                return 0.0;
            }
            double reversed;
            if (original.isTransfer()) {
                // Try to pull the money back; if the receiver already spent part of it,
                // claw back whatever is left rather than pushing them negative.
                double available = AccountRepository.balanceOn(connection, original.to());
                double take = Money.round(Math.min(original.amount(), Math.max(0.0, available)), settings.decimals());
                if (take <= 0) {
                    return 0.0;
                }
                AccountRepository.subtractIfEnough(connection, original.to(), take);
                AccountRepository.applyDelta(connection, original.from(), take);
                reversed = take;
            } else if (original.deltaIsCredit()) {
                double available = AccountRepository.balanceOn(connection, original.account());
                double take = Money.round(Math.min(original.amount(), Math.max(0.0, available)), settings.decimals());
                if (take > 0) {
                    AccountRepository.subtractIfEnough(connection, original.account(), take);
                }
                reversed = take;
            } else {
                AccountRepository.applyDelta(connection, original.account(), original.amount());
                reversed = original.amount();
            }
            Jdbc.update(connection, "UPDATE cr_transactions SET reverted = 1 WHERE id = ?", transactionId);
            TransactionRepository.append(connection, TransactionType.ROLLBACK,
                    original.from(), original.to(), reversed, "rollback of #" + transactionId, staff,
                    "rollback:" + transactionId, false, transactionId, settings.serverId());
            return reversed;
        }).thenApply(reversed -> {
            if (reversed > 0) {
                invalidate(transactionId);
            }
            return reversed;
        });
    }

    // ------------------------------------------------------------------ engine

    /**
     * The single mutation path for a server source/sink.
     *
     * @param credit {@code true} to add Crowns, {@code false} to remove them
     */
    private CompletableFuture<EconomyResult> mutate(UUID uuid, double rawAmount, boolean credit,
                                                    TransactionType type, String reason, UUID actor,
                                                    String operationId) {
        double amount = Money.round(rawAmount, settings.decimals());
        EconomyResult validation = validate(uuid, amount, 0.0);
        if (validation != null) {
            return CompletableFuture.completedFuture(validation);
        }
        double current = cachedBalance(uuid);
        double delta = credit ? amount : -amount;
        double target = Money.round(current + delta, settings.decimals());
        return preCreate(uuid)
                .thenCompose(ignored -> fireEvent(uuid, current, target, delta, type, reason, actor))
                .thenCompose(allowed -> {
                    if (!allowed) {
                        return CompletableFuture.completedFuture(
                                EconomyResult.fail(EconomyResult.Reason.ERROR, current));
                    }
                    return database.inTransaction(connection -> {
                        if (TransactionRepository.operationExists(connection, operationId)) {
                            throw new DuplicateOperationException();
                        }
                        int rows = credit
                                ? AccountRepository.applyDelta(connection, uuid, amount)
                                : AccountRepository.subtractIfEnough(connection, uuid, amount);
                        if (rows == 0) {
                            EconomyResult.Reason why = credit
                                    ? EconomyResult.Reason.NO_ACCOUNT
                                    : EconomyResult.Reason.INSUFFICIENT_FUNDS;
                            return new Mutation(EconomyResult.fail(why,
                                    AccountRepository.balanceOn(connection, uuid)), null);
                        }
                        long id = TransactionRepository.append(connection, type,
                                credit ? null : uuid, credit ? uuid : null, amount, reason, actor,
                                operationId, false, 0, settings.serverId());
                        double after = AccountRepository.balanceOn(connection, uuid);
                        return new Mutation(EconomyResult.ok(after), readTransaction(connection, id));
                    }).thenApply(mutation -> publish(uuid, mutation));
                })
                .exceptionally(throwable -> duplicateOrFail(throwable, uuid));
    }

    /** Validates an amount and the per-player rate limit. Returns {@code null} when fine. */
    private EconomyResult validate(UUID uuid, double amount, double minimum) {
        if (!Money.isValidAmount(amount) || (minimum > 0 && amount < minimum)) {
            return EconomyResult.fail(EconomyResult.Reason.INVALID_AMOUNT, cachedBalance(uuid));
        }
        if (amount > settings.maxTransaction()) {
            return EconomyResult.fail(EconomyResult.Reason.LIMIT_EXCEEDED, cachedBalance(uuid));
        }
        EconomyResult.Reason denied = guard.apply(uuid);
        if (denied != null) {
            return EconomyResult.fail(denied, cachedBalance(uuid));
        }
        if (!limiter.tryAcquire(uuid.toString())) {
            return EconomyResult.fail(EconomyResult.Reason.RATE_LIMITED, cachedBalance(uuid));
        }
        return null;
    }

    /** Makes sure the row exists so a debit can never silently do nothing. */
    private CompletableFuture<Void> preCreate(UUID uuid) {
        if (cache.containsKey(uuid)) {
            return CompletableFuture.completedFuture(null);
        }
        return accounts.ensure(uuid, null, settings.startingBalance());
    }

    private CompletableFuture<Boolean> fireEvent(UUID uuid, double oldBalance, double newBalance,
                                                 double delta, TransactionType type, String reason, UUID actor) {
        return Events.callCancellable(new BalanceChangeEvent(uuid, oldBalance, newBalance, delta, type, reason, actor));
    }

    /** Applies cache updates and notifications after a committed mutation. */
    private EconomyResult publish(UUID uuid, Mutation mutation) {
        if (mutation == null) {
            return EconomyResult.fail(EconomyResult.Reason.ERROR);
        }
        if (mutation.result().success()) {
            cache.compute(uuid, (key, previous) -> previous == null
                    ? new AccountRow(uuid, null, mutation.result().balanceAfter(), 0L)
                    : previous.withBalance(mutation.result().balanceAfter()));
        }
        Transaction transaction = mutation.transaction();
        if (transaction != null) {
            Events.call(new TransactionCompletedEvent(transaction));
            if (transaction.amount() >= settings.alertThreshold() && transaction.isTransfer()) {
                suspiciousSink.accept(uuid, transaction);
            }
        }
        return mutation.result();
    }

    private EconomyResult duplicateOrFail(Throwable throwable, UUID uuid) {
        Throwable cause = unwrap(throwable);
        if (cause instanceof DuplicateOperationException) {
            return EconomyResult.fail(EconomyResult.Reason.DUPLICATE_OPERATION, cachedBalance(uuid));
        }
        String message = cause.getMessage() == null ? "" : cause.getMessage().toLowerCase();
        if (message.contains("unique") || message.contains("duplicate")) {
            return EconomyResult.fail(EconomyResult.Reason.DUPLICATE_OPERATION, cachedBalance(uuid));
        }
        io.craftrealm.core.CraftRealmCore.logger()
                .warning("Economy write failed: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
        return EconomyResult.fail(EconomyResult.Reason.ERROR, cachedBalance(uuid));
    }

    private void invalidate(long transactionId) {
        // Reload every cached account that might have been touched by a rollback.
        cache.keySet().forEach(uuid -> accounts.find(uuid).thenAccept(row -> row.ifPresent(r -> cache.put(uuid, r))));
    }

    private static Transaction readTransaction(Connection connection, long id) throws SQLException {
        return Jdbc.one(connection, "SELECT * FROM cr_transactions WHERE id = ?", row -> new Transaction(
                row.getLong("id"),
                TransactionType.valueOf(row.getString("type")),
                Jdbc.uuid(row, "from_uuid"),
                Jdbc.uuid(row, "to_uuid"),
                row.getDouble("amount"),
                row.getString("reason"),
                Jdbc.uuid(row, "actor"),
                row.getString("op_id"),
                row.getLong("created_at"),
                row.getInt("reverted") != 0,
                row.getLong("rollback_of"),
                row.getString("server")), id).orElse(null);
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        if (current instanceof io.craftrealm.core.db.StorageException && current.getCause() != null) {
            return current.getCause();
        }
        return current;
    }
}
