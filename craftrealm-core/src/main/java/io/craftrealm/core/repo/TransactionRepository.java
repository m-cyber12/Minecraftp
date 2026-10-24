package io.craftrealm.core.repo;

import io.craftrealm.api.model.Transaction;
import io.craftrealm.api.model.TransactionType;
import io.craftrealm.core.db.Database;
import io.craftrealm.core.db.Jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The append-only ledger.
 *
 * <p>{@code op_id} is {@code UNIQUE}; when a caller replays an operation the
 * insert fails and {@link #insert} reports {@link InsertResult#DUPLICATE} instead
 * of writing money twice.</p>
 */
public final class TransactionRepository {

    /** Outcome of an append. */
    public enum InsertResult {
        OK,
        DUPLICATE,
        FAILED
    }

    /** An append plus the generated id (or {@code -1}). */
    public record AppendResult(InsertResult result, long id) {
    }

    private static final String COLUMNS =
            "type, from_uuid, to_uuid, amount, reason, actor, op_id, server, created_at, reverted, rollback_of";

    private final Database database;

    public TransactionRepository(Database database) {
        this.database = database;
    }

    public CompletableFuture<AppendResult> insert(TransactionType type, UUID from, UUID to, double amount,
                                                  String reason, UUID actor, String operationId,
                                                  boolean reverted, long rollbackOf, String server) {
        return database.supply(connection -> {
            try {
                String sql = "INSERT INTO cr_transactions (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?)";
                long id = Jdbc.insert(connection, sql, type, from, to, amount, reason, actor, operationId,
                        server, System.currentTimeMillis(), reverted, rollbackOf);
                return new AppendResult(InsertResult.OK, id);
            } catch (SQLException exception) {
                if (isDuplicate(exception)) {
                    return new AppendResult(InsertResult.DUPLICATE, -1L);
                }
                throw exception;
            }
        });
    }

    /**
     * Appends a ledger row on a caller-managed connection. Any unique-key clash
     * propagates so the surrounding transaction rolls back — that is what makes
     * idempotent operations safe.
     */
    public static long append(java.sql.Connection connection, TransactionType type, UUID from, UUID to,
                              double amount, String reason, UUID actor, String operationId,
                              boolean reverted, long rollbackOf, String server) throws SQLException {
        long now = System.currentTimeMillis();
        String sql = "INSERT INTO cr_transactions (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?)";
        return Jdbc.insert(connection, sql, type, from, to, amount, reason, actor, operationId,
                server, now, reverted, rollbackOf);
    }

    /** Cheap pre-check so an operation id is rejected before any money moves. */
    public static boolean operationExists(java.sql.Connection connection, String operationId)
            throws SQLException {
        return operationId != null && Jdbc.scalar(connection,
                "SELECT COUNT(*) FROM cr_transactions WHERE op_id = ?", operationId) > 0;
    }

    public CompletableFuture<Optional<Transaction>> find(long id) {
        return database.supply(connection -> Jdbc.one(connection,
                "SELECT * FROM cr_transactions WHERE id = ?", TransactionRepository::map, id));
    }

    public CompletableFuture<Boolean> markReverted(long id) {
        return database.supply(connection -> Jdbc.update(connection,
                "UPDATE cr_transactions SET reverted = 1 WHERE id = ?", id) > 0);
    }

    public CompletableFuture<List<Transaction>> forPlayer(UUID uuid, int limit) {
        return database.supply(connection -> Jdbc.list(connection,
                "SELECT * FROM cr_transactions WHERE from_uuid = ? OR to_uuid = ? "
                        + "ORDER BY id DESC LIMIT ?",
                TransactionRepository::map, uuid, uuid, Math.max(1, limit)));
    }

    public CompletableFuture<List<Transaction>> recent(int limit) {
        return database.supply(connection -> Jdbc.list(connection,
                "SELECT * FROM cr_transactions ORDER BY id DESC LIMIT ?",
                TransactionRepository::map, Math.max(1, limit)));
    }

    /** Large transfers, for the suspicious activity panel. */
    public CompletableFuture<List<Transaction>> above(double threshold, int limit) {
        return database.supply(connection -> Jdbc.list(connection,
                "SELECT * FROM cr_transactions WHERE amount >= ? AND from_uuid IS NOT NULL "
                        + "ORDER BY id DESC LIMIT ?",
                TransactionRepository::map, threshold, Math.max(1, limit)));
    }

    /** Ledger totals per category, used by the economy dashboard. */
    public CompletableFuture<Double> sumOf(TransactionType.Category category, long since) {
        StringBuilder placeholders = new StringBuilder();
        List<TransactionType> types = java.util.Arrays.stream(TransactionType.values())
                .filter(type -> type.category() == category)
                .toList();
        if (types.isEmpty()) {
            return CompletableFuture.completedFuture(0.0);
        }
        for (int i = 0; i < types.size(); i++) {
            placeholders.append(i == 0 ? "?" : ", ?");
        }
        String sql = "SELECT COALESCE(SUM(amount), 0) FROM cr_transactions "
                + "WHERE type IN (" + placeholders + ") AND created_at >= ?";
        Object[] params = new Object[types.size() + 1];
        for (int i = 0; i < types.size(); i++) {
            params[i] = types.get(i);
        }
        params[types.size()] = since;
        return database.supply(connection -> Jdbc.scalarDouble(connection, sql, params));
    }

    public CompletableFuture<Boolean> operationExists(String operationId) {
        if (operationId == null) {
            return CompletableFuture.completedFuture(false);
        }
        return database.supply(connection -> Jdbc.scalar(connection,
                "SELECT COUNT(*) FROM cr_transactions WHERE op_id = ?", operationId) > 0);
    }

    private static boolean isDuplicate(SQLException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase();
        String state = exception.getSQLState() == null ? "" : exception.getSQLState();
        return state.equals("23505")                     // standard
                || message.contains("unique")            // sqlite / mysql
                || message.contains("duplicate");
    }

    private static Transaction map(ResultSet row) throws SQLException {
        return new Transaction(
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
                row.getString("server"));
    }
}
