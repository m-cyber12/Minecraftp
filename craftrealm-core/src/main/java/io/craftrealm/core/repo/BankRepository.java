package io.craftrealm.core.repo;

import io.craftrealm.api.model.BankAccount;
import io.craftrealm.core.db.Database;
import io.craftrealm.core.db.Jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Savings accounts. Like the wallet, deposits and withdrawals are conditional
 * SQL so the bank can never be overdrawn by a race.
 */
public final class BankRepository {

    private final Database database;

    public BankRepository(Database database) {
        this.database = database;
    }

    public CompletableFuture<BankAccount> ensure(UUID uuid) {
        return database.supply(connection -> {
            long now = System.currentTimeMillis();
            Jdbc.update(connection,
                    "INSERT INTO cr_bank_accounts (uuid, balance, last_interest, created_at) "
                            + "SELECT ?, 0, 0, ? WHERE NOT EXISTS "
                            + "(SELECT 1 FROM cr_bank_accounts WHERE uuid = ?)", uuid, now, uuid);
            return Jdbc.one(connection, "SELECT * FROM cr_bank_accounts WHERE uuid = ?",
                    BankRepository::map, uuid).orElse(BankAccount.empty(uuid));
        });
    }

    public CompletableFuture<Optional<BankAccount>> find(UUID uuid) {
        return database.supply(connection -> Jdbc.one(connection,
                "SELECT * FROM cr_bank_accounts WHERE uuid = ?", BankRepository::map, uuid));
    }

    public CompletableFuture<Boolean> add(UUID uuid, double amount) {
        return database.supply(connection -> Jdbc.update(connection,
                "UPDATE cr_bank_accounts SET balance = balance + ? WHERE uuid = ?",
                amount, uuid) > 0);
    }

    public CompletableFuture<Boolean> subtractIfEnough(UUID uuid, double amount) {
        return database.supply(connection -> Jdbc.update(connection,
                "UPDATE cr_bank_accounts SET balance = balance - ? WHERE uuid = ? AND balance >= ?",
                amount, uuid, amount) > 0);
    }

    public CompletableFuture<Void> setLastInterest(UUID uuid, long timestamp) {
        return database.run(connection -> Jdbc.update(connection,
                "UPDATE cr_bank_accounts SET last_interest = ? WHERE uuid = ?", timestamp, uuid));
    }

    /** Credits interest and stamps the timestamp in one statement. */
    public CompletableFuture<Boolean> creditInterest(UUID uuid, double interest, long timestamp) {
        return database.supply(connection -> Jdbc.update(connection,
                "UPDATE cr_bank_accounts SET balance = balance + ?, last_interest = ? "
                        + "WHERE uuid = ? AND last_interest <= ?",
                interest, timestamp, uuid, timestamp) > 0);
    }

    /** Accounts whose interest period has elapsed. */
    public CompletableFuture<List<BankAccount>> due(long cutoff) {
        return database.supply(connection -> Jdbc.list(connection,
                "SELECT * FROM cr_bank_accounts WHERE last_interest <= ? AND balance > 0 ORDER BY balance DESC",
                BankRepository::map, cutoff));
    }

    public CompletableFuture<List<BankAccount>> all() {
        return database.supply(connection -> Jdbc.list(connection,
                "SELECT * FROM cr_bank_accounts ORDER BY balance DESC", BankRepository::map));
    }

    public CompletableFuture<Double> totalDeposits() {
        return database.supply(connection -> Jdbc.scalarDouble(connection,
                "SELECT COALESCE(SUM(balance), 0) FROM cr_bank_accounts"));
    }

    private static BankAccount map(ResultSet row) throws SQLException {
        return new BankAccount(
                UUID.fromString(row.getString("uuid")),
                row.getDouble("balance"),
                row.getLong("last_interest"),
                row.getLong("created_at"));
    }
}
