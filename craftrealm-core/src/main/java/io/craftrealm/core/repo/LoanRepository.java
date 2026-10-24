package io.craftrealm.core.repo;

import io.craftrealm.api.model.Loan;
import io.craftrealm.api.model.LoanStatus;
import io.craftrealm.core.db.Database;
import io.craftrealm.core.db.Jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Persistence for bank loans. */
public final class LoanRepository {

    private final Database database;

    public LoanRepository(Database database) {
        this.database = database;
    }

    public CompletableFuture<Loan> create(UUID owner, double principal, double outstanding,
                                          double dailyRate, long dueAt) {
        return database.supply(connection -> {
            long now = System.currentTimeMillis();
            long id = Jdbc.insert(connection,
                    "INSERT INTO cr_loans (owner, principal, outstanding, daily_rate, due_at, created_at, status) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    owner, principal, outstanding, dailyRate, dueAt, now, LoanStatus.ACTIVE);
            return new Loan(id, owner, principal, outstanding, dailyRate, dueAt, now, LoanStatus.ACTIVE);
        });
    }

    public CompletableFuture<Optional<Loan>> find(long id) {
        return database.supply(connection -> Jdbc.one(connection,
                "SELECT * FROM cr_loans WHERE id = ?", LoanRepository::map, id));
    }

    public CompletableFuture<List<Loan>> byOwner(UUID owner) {
        return database.supply(connection -> Jdbc.list(connection,
                "SELECT * FROM cr_loans WHERE owner = ? ORDER BY created_at ASC",
                LoanRepository::map, owner));
    }

    public CompletableFuture<Double> outstandingTotal(UUID owner) {
        return database.supply(connection -> Jdbc.scalarDouble(connection,
                "SELECT COALESCE(SUM(outstanding), 0) FROM cr_loans WHERE owner = ? AND status = ?",
                owner, LoanStatus.ACTIVE));
    }

    public CompletableFuture<Boolean> updateOutstanding(long id, double outstanding, LoanStatus status) {
        return database.supply(connection -> Jdbc.update(connection,
                "UPDATE cr_loans SET outstanding = ?, status = ? WHERE id = ?",
                outstanding, status, id) > 0);
    }

    public CompletableFuture<Boolean> repay(long id, double amount) {
        return database.supply(connection -> Jdbc.update(connection,
                "UPDATE cr_loans SET outstanding = outstanding - ? WHERE id = ? AND outstanding >= ?",
                amount, id, amount) > 0);
    }

    public CompletableFuture<List<Loan>> active() {
        return database.supply(connection -> Jdbc.list(connection,
                "SELECT * FROM cr_loans WHERE status = ? ORDER BY due_at ASC",
                LoanRepository::map, LoanStatus.ACTIVE));
    }

    public CompletableFuture<List<Loan>> overdue(long now) {
        return database.supply(connection -> Jdbc.list(connection,
                "SELECT * FROM cr_loans WHERE status = ? AND due_at < ?",
                LoanRepository::map, LoanStatus.ACTIVE, now));
    }

    public CompletableFuture<Double> totalLent() {
        return database.supply(connection -> Jdbc.scalarDouble(connection,
                "SELECT COALESCE(SUM(outstanding), 0) FROM cr_loans WHERE status = ?", LoanStatus.ACTIVE));
    }

    private static Loan map(ResultSet row) throws SQLException {
        return new Loan(
                row.getLong("id"),
                UUID.fromString(row.getString("owner")),
                row.getDouble("principal"),
                row.getDouble("outstanding"),
                row.getDouble("daily_rate"),
                row.getLong("due_at"),
                row.getLong("created_at"),
                LoanStatus.valueOf(row.getString("status")));
    }
}
