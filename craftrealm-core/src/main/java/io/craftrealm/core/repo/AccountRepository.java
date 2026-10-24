package io.craftrealm.core.repo;

import io.craftrealm.api.model.BalanceEntry;
import io.craftrealm.api.model.ShardEntry;
import io.craftrealm.core.db.Database;
import io.craftrealm.core.db.Jdbc;
import io.craftrealm.core.economy.AccountRow;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Accounts: Crowns and Shards.
 *
 * <p>All balance mutations are <b>conditional SQL statements</b> rather than
 * read-modify-write. That is the single most important anti-dupe measure in the
 * plugin: two concurrent withdrawals of the last 100 Crowns can only ever
 * succeed once, even across servers sharing a MySQL database.</p>
 */
public final class AccountRepository {

    private final Database database;

    public AccountRepository(Database database) {
        this.database = database;
    }

    public CompletableFuture<Optional<AccountRow>> find(UUID uuid) {
        return database.supply(connection -> Jdbc.one(connection,
                "SELECT uuid, name, balance, shards FROM cr_accounts WHERE uuid = ?",
                row -> new AccountRow(
                        UUID.fromString(row.getString("uuid")),
                        row.getString("name"),
                        row.getDouble("balance"),
                        row.getLong("shards")),
                uuid));
    }

    /**
     * Inserts the row when missing. The starting balance is only applied on
     * creation, so a player who spends everything never gets a second grant.
     */
    public CompletableFuture<Void> ensure(UUID uuid, String name, double startingBalance) {
        return database.run(connection -> {
            long now = System.currentTimeMillis();
            int inserted = Jdbc.update(connection,
                    "INSERT INTO cr_accounts (uuid, name, balance, shards, created_at, updated_at) "
                            + "SELECT ?, ?, ?, 0, ?, ? WHERE NOT EXISTS (SELECT 1 FROM cr_accounts WHERE uuid = ?)",
                    uuid, name, startingBalance, now, now, uuid);
            if (inserted == 0 && name != null) {
                Jdbc.update(connection, "UPDATE cr_accounts SET name = ? WHERE uuid = ? AND (name IS NULL OR name <> ?)",
                        name, uuid, name);
            }
        });
    }

    public CompletableFuture<Boolean> setName(UUID uuid, String name) {
        return database.supply(connection -> Jdbc.update(connection,
                "UPDATE cr_accounts SET name = ? WHERE uuid = ?", name, uuid) > 0);
    }

    /** Unconditional delta; used for server sources/sinks and staff adjustments. */
    public CompletableFuture<Boolean> add(UUID uuid, double delta) {
        return database.supply(connection -> Jdbc.update(connection,
                "UPDATE cr_accounts SET balance = balance + ?, updated_at = ? WHERE uuid = ?",
                delta, System.currentTimeMillis(), uuid) > 0);
    }

    /** Conditional withdrawal: never lets a balance go negative. */
    public CompletableFuture<Boolean> subtractIfEnough(UUID uuid, double amount) {
        return database.supply(connection -> Jdbc.update(connection,
                "UPDATE cr_accounts SET balance = balance - ?, updated_at = ? "
                        + "WHERE uuid = ? AND balance >= ?",
                amount, System.currentTimeMillis(), uuid, amount) > 0);
    }

    public CompletableFuture<Boolean> setBalance(UUID uuid, double balance) {
        return database.supply(connection -> Jdbc.update(connection,
                "UPDATE cr_accounts SET balance = ?, updated_at = ? WHERE uuid = ?",
                balance, System.currentTimeMillis(), uuid) > 0);
    }

    /**
     * Moves money between two accounts in one transaction: either both rows change
     * or neither does.
     */
    public CompletableFuture<Boolean> transfer(UUID from, UUID to, double amount) {
        return database.inTransaction(connection -> {
            long now = System.currentTimeMillis();
            int debited = Jdbc.update(connection,
                    "UPDATE cr_accounts SET balance = balance - ?, updated_at = ? "
                            + "WHERE uuid = ? AND balance >= ?",
                    amount, now, from, amount);
            if (debited == 0) {
                return false;
            }
            int credited = Jdbc.update(connection,
                    "UPDATE cr_accounts SET balance = balance + ?, updated_at = ? WHERE uuid = ?",
                    amount, now, to);
            if (credited == 0) {
                // Destination vanished mid-flight: abort so the debit rolls back.
                throw new IllegalStateException("destination account " + to + " does not exist");
            }
            return true;
        });
    }

    // --------------------------------------------------- connection-level API
    // These are used inside a caller-managed transaction (see EconomyServiceImpl)
    // so the balance change and its ledger row commit together.

    /** Unconditional delta on an open connection. */
    public static int applyDelta(java.sql.Connection connection, UUID uuid, double delta)
            throws java.sql.SQLException {
        return Jdbc.update(connection,
                "UPDATE cr_accounts SET balance = balance + ?, updated_at = ? WHERE uuid = ?",
                delta, System.currentTimeMillis(), uuid);
    }

    /** Conditional debit on an open connection. */
    public static int subtractIfEnough(java.sql.Connection connection, UUID uuid, double amount)
            throws java.sql.SQLException {
        return Jdbc.update(connection,
                "UPDATE cr_accounts SET balance = balance - ?, updated_at = ? "
                        + "WHERE uuid = ? AND balance >= ?",
                amount, System.currentTimeMillis(), uuid, amount);
    }

    /** Absolute set on an open connection. */
    public static int setBalance(java.sql.Connection connection, UUID uuid, double balance)
            throws java.sql.SQLException {
        return Jdbc.update(connection,
                "UPDATE cr_accounts SET balance = ?, updated_at = ? WHERE uuid = ?",
                balance, System.currentTimeMillis(), uuid);
    }

    public static boolean exists(java.sql.Connection connection, UUID uuid) throws java.sql.SQLException {
        return Jdbc.scalar(connection, "SELECT COUNT(*) FROM cr_accounts WHERE uuid = ?", uuid) > 0;
    }

    public static double balanceOn(java.sql.Connection connection, UUID uuid) throws java.sql.SQLException {
        return Jdbc.scalarDouble(connection, "SELECT balance FROM cr_accounts WHERE uuid = ?", uuid);
    }

    public CompletableFuture<Double> balance(UUID uuid) {
        return database.supply(connection -> Jdbc.scalarDouble(connection,
                "SELECT balance FROM cr_accounts WHERE uuid = ?", uuid));
    }

    public CompletableFuture<List<BalanceEntry>> top(int limit) {
        return database.supply(connection -> Jdbc.list(connection,
                "SELECT uuid, name, balance FROM cr_accounts WHERE balance > 0 "
                        + "ORDER BY balance DESC LIMIT ?",
                row -> new BalanceEntry(
                        UUID.fromString(row.getString("uuid")),
                        row.getString("name"),
                        row.getDouble("balance")),
                Math.max(1, limit)));
    }

    public CompletableFuture<Double> totalInCirculation() {
        return database.supply(connection -> Jdbc.scalarDouble(connection,
                "SELECT COALESCE(SUM(balance), 0) FROM cr_accounts"));
    }

    public CompletableFuture<Long> accountCount() {
        return database.supply(connection -> Jdbc.scalar(connection, "SELECT COUNT(*) FROM cr_accounts"));
    }

    // ------------------------------------------------------------------ shards

    public CompletableFuture<Long> shards(UUID uuid) {
        return database.supply(connection -> Jdbc.scalar(connection,
                "SELECT COALESCE(shards, 0) FROM cr_accounts WHERE uuid = ?", uuid));
    }

    public CompletableFuture<Long> addShards(UUID uuid, long delta) {
        return database.supply(connection -> {
            Jdbc.update(connection,
                    "UPDATE cr_accounts SET shards = shards + ?, updated_at = ? WHERE uuid = ?",
                    delta, System.currentTimeMillis(), uuid);
            return Jdbc.scalar(connection, "SELECT COALESCE(shards, 0) FROM cr_accounts WHERE uuid = ?", uuid);
        });
    }

    /** Conditional shard spend; returns {@code false} when the wallet is too light. */
    public CompletableFuture<Boolean> takeShards(UUID uuid, long amount) {
        return database.supply(connection -> Jdbc.update(connection,
                "UPDATE cr_accounts SET shards = shards - ?, updated_at = ? "
                        + "WHERE uuid = ? AND shards >= ?",
                amount, System.currentTimeMillis(), uuid, amount) > 0);
    }

    public CompletableFuture<List<ShardEntry>> topShards(int limit) {
        return database.supply(connection -> Jdbc.list(connection,
                "SELECT uuid, name, shards FROM cr_accounts WHERE shards > 0 "
                        + "ORDER BY shards DESC LIMIT ?",
                row -> new ShardEntry(
                        UUID.fromString(row.getString("uuid")),
                        row.getString("name"),
                        row.getLong("shards")),
                Math.max(1, limit)));
    }

    public CompletableFuture<String> name(UUID uuid) {
        return database.supply(connection -> Jdbc.one(connection,
                "SELECT name FROM cr_accounts WHERE uuid = ?",
                row -> row.getString("name"), uuid).orElse(null));
    }
}
