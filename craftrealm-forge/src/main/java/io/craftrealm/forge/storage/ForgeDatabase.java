package io.craftrealm.forge.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** SQLite-backed economy storage. All gameplay queries execute off the server thread. */
public final class ForgeDatabase implements AutoCloseable {

    private final Path file;
    private final int threads;
    private final Logger logger;
    private final AtomicInteger threadNumber = new AtomicInteger();
    private ExecutorService io;
    private HikariDataSource pool;

    public ForgeDatabase(Path file, int threads, Logger logger) {
        this.file = file;
        this.threads = Math.max(1, threads);
        this.logger = logger;
    }

    /** Opens SQLite and applies the initial schema during server startup. */
    public void open() throws SQLException, IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        HikariConfig config = new HikariConfig();
        config.setPoolName("CraftRealm-Forge-SQLite");
        config.setDriverClassName("org.sqlite.JDBC");
        config.setJdbcUrl("jdbc:sqlite:" + file.toAbsolutePath());
        config.setMaximumPoolSize(Math.max(1, threads));
        config.setMinimumIdle(1);
        config.setConnectionTimeout(5_000);
        config.setValidationTimeout(3_000);
        config.addDataSourceProperty("journal_mode", "WAL");
        config.addDataSourceProperty("synchronous", "NORMAL");
        config.addDataSourceProperty("busy_timeout", "5000");
        config.addDataSourceProperty("foreign_keys", "true");
        pool = new HikariDataSource(config);
        try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS cr_accounts (
                        uuid TEXT PRIMARY KEY NOT NULL,
                        name TEXT NOT NULL,
                        balance_cents INTEGER NOT NULL DEFAULT 0 CHECK(balance_cents >= 0),
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS cr_transactions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        type TEXT NOT NULL,
                        from_uuid TEXT,
                        to_uuid TEXT,
                        amount_cents INTEGER NOT NULL CHECK(amount_cents > 0),
                        reason TEXT NOT NULL,
                        actor TEXT,
                        created_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_cr_transactions_from ON cr_transactions(from_uuid, id DESC)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_cr_transactions_to ON cr_transactions(to_uuid, id DESC)");
            if (!connection.isValid(3)) {
                throw new SQLException("SQLite connection failed validation");
            }
        } catch (SQLException failure) {
            pool.close();
            pool = null;
            throw failure;
        }
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "CraftRealm-Forge-DB-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        io = Executors.newFixedThreadPool(threads, factory);
        logger.info("CraftRealm Forge storage ready: {}", file.toAbsolutePath());
    }

    public CompletableFuture<Long> balance(UUID player, String name, long initialBalanceCents) {
        return supply(connection -> {
            ensureAccount(connection, player, name, initialBalanceCents);
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT balance_cents FROM cr_accounts WHERE uuid = ?")) {
                query.setString(1, player.toString());
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) {
                        throw new SQLException("account vanished after insert: " + player);
                    }
                    return rows.getLong(1);
                }
            }
        });
    }

    public CompletableFuture<Payment> pay(UUID payer, String payerName, UUID recipient, String recipientName,
                                           long amountCents, long initialBalanceCents, long maxBalanceCents) {
        return supply(connection -> {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                ensureAccount(connection, payer, payerName, initialBalanceCents);
                ensureAccount(connection, recipient, recipientName, initialBalanceCents);
                long now = System.currentTimeMillis();
                int changed;
                try (PreparedStatement debit = connection.prepareStatement(
                        "UPDATE cr_accounts SET balance_cents = balance_cents - ?, name = ?, updated_at = ? "
                                + "WHERE uuid = ? AND balance_cents >= ?")) {
                    debit.setLong(1, amountCents);
                    debit.setString(2, payerName);
                    debit.setLong(3, now);
                    debit.setString(4, payer.toString());
                    debit.setLong(5, amountCents);
                    changed = debit.executeUpdate();
                }
                if (changed == 0) {
                    connection.rollback();
                    return Payment.insufficient();
                }
                try (PreparedStatement credit = connection.prepareStatement(
                        "UPDATE cr_accounts SET balance_cents = balance_cents + ?, name = ?, updated_at = ? "
                                + "WHERE uuid = ? AND balance_cents <= ?")) {
                    credit.setLong(1, amountCents);
                    credit.setString(2, recipientName);
                    credit.setLong(3, now);
                    credit.setString(4, recipient.toString());
                    credit.setLong(5, maxBalanceCents - amountCents);
                    if (credit.executeUpdate() == 0) {
                        connection.rollback();
                        return Payment.recipientAtLimit();
                    }
                }
                try (PreparedStatement transaction = connection.prepareStatement(
                        "INSERT INTO cr_transactions(type,from_uuid,to_uuid,amount_cents,reason,actor,created_at) "
                                + "VALUES('PAY',?,?,?,?,?,?)")) {
                    transaction.setString(1, payer.toString());
                    transaction.setString(2, recipient.toString());
                    transaction.setLong(3, amountCents);
                    transaction.setString(4, "player payment");
                    transaction.setString(5, payer.toString());
                    transaction.setLong(6, now);
                    transaction.executeUpdate();
                }
                long payerBalance = readBalance(connection, payer);
                long recipientBalance = readBalance(connection, recipient);
                connection.commit();
                return Payment.success(payerBalance, recipientBalance);
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        });
    }

    public CompletableFuture<List<AccountBalance>> top(int limit) {
        return supply(connection -> {
            List<AccountBalance> result = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT uuid,name,balance_cents FROM cr_accounts ORDER BY balance_cents DESC, name ASC LIMIT ?")) {
                query.setInt(1, Math.max(1, Math.min(100, limit)));
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        result.add(new AccountBalance(UUID.fromString(rows.getString("uuid")),
                                rows.getString("name"), rows.getLong("balance_cents")));
                    }
                }
            }
            return result;
        });
    }

    private static void ensureAccount(Connection connection, UUID uuid, String name, long initialCents)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT OR IGNORE INTO cr_accounts(uuid,name,balance_cents,created_at,updated_at) VALUES(?,?,?,?,?)")) {
            long now = System.currentTimeMillis();
            insert.setString(1, uuid.toString());
            insert.setString(2, name == null || name.isBlank() ? uuid.toString() : name);
            insert.setLong(3, Math.max(0, initialCents));
            insert.setLong(4, now);
            insert.setLong(5, now);
            insert.executeUpdate();
        }
        try (PreparedStatement rename = connection.prepareStatement(
                "UPDATE cr_accounts SET name = ?, updated_at = ? WHERE uuid = ?")) {
            rename.setString(1, name == null || name.isBlank() ? uuid.toString() : name);
            rename.setLong(2, System.currentTimeMillis());
            rename.setString(3, uuid.toString());
            rename.executeUpdate();
        }
    }

    private static long readBalance(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT balance_cents FROM cr_accounts WHERE uuid = ?")) {
            query.setString(1, uuid.toString());
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("missing account " + uuid);
                }
                return rows.getLong(1);
            }
        }
    }

    private <T> CompletableFuture<T> supply(SqlWork<T> work) {
        ExecutorService executor = io;
        HikariDataSource datasource = pool;
        if (executor == null || datasource == null || datasource.isClosed()) {
            return CompletableFuture.failedFuture(new IllegalStateException("CraftRealm storage is not open"));
        }
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = datasource.getConnection()) {
                return work.apply(connection);
            } catch (SQLException failure) {
                throw new StorageFailure("CraftRealm database operation failed", failure);
            }
        }, executor);
    }

    @Override
    public void close() {
        if (io != null) {
            io.shutdown();
            try {
                if (!io.awaitTermination(8, java.util.concurrent.TimeUnit.SECONDS)) {
                    io.shutdownNow();
                }
            } catch (InterruptedException interrupted) {
                io.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (pool != null) {
            pool.close();
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T apply(Connection connection) throws SQLException;
    }

    public record AccountBalance(UUID uuid, String name, long cents) { }

    public record Payment(boolean paid, boolean insufficientFunds, boolean recipientLimit,
                          long payerBalanceCents, long recipientBalanceCents) {
        static Payment success(long payer, long recipient) {
            return new Payment(true, false, false, payer, recipient);
        }

        static Payment insufficient() {
            return new Payment(false, true, false, 0, 0);
        }

        static Payment recipientAtLimit() {
            return new Payment(false, false, true, 0, 0);
        }
    }

    public static final class StorageFailure extends RuntimeException {
        public StorageFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
