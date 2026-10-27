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
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS cr_bank_accounts (
                        uuid TEXT PRIMARY KEY NOT NULL,
                        balance_cents INTEGER NOT NULL DEFAULT 0 CHECK(balance_cents >= 0),
                        last_interest INTEGER NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS cr_loans (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        uuid TEXT NOT NULL,
                        principal_cents INTEGER NOT NULL,
                        outstanding_cents INTEGER NOT NULL,
                        due_at INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_cr_loans_owner ON cr_loans(uuid, status)");
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

    public CompletableFuture<BankSnapshot> bankBalance(UUID uuid, String name, long initialWalletCents) {
        return supply(connection -> {
            ensureAccount(connection, uuid, name, initialWalletCents);
            ensureBankAccount(connection, uuid);
            return new BankSnapshot(readBalance(connection, uuid), readBankBalance(connection, uuid));
        });
    }

    public CompletableFuture<BankResult> moveBank(UUID uuid, String name, long amountCents, boolean deposit,
                                                   long initialWalletCents, long maxBalanceCents) {
        return supply(connection -> {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                ensureAccount(connection, uuid, name, initialWalletCents);
                ensureBankAccount(connection, uuid);
                long now = System.currentTimeMillis();
                String walletSql = deposit
                        ? "UPDATE cr_accounts SET balance_cents=balance_cents-?,updated_at=? WHERE uuid=? AND balance_cents>=?"
                        : "UPDATE cr_accounts SET balance_cents=balance_cents+?,updated_at=? WHERE uuid=? AND balance_cents<=?";
                try (PreparedStatement wallet = connection.prepareStatement(walletSql)) {
                    wallet.setLong(1, amountCents);
                    wallet.setLong(2, now);
                    wallet.setString(3, uuid.toString());
                    wallet.setLong(4, deposit ? amountCents : maxBalanceCents - amountCents);
                    if (wallet.executeUpdate() == 0) {
                        connection.rollback();
                        return BankResult.failed(deposit ? "wallet-funds" : "wallet-limit");
                    }
                }
                String bankSql = deposit
                        ? "UPDATE cr_bank_accounts SET balance_cents=balance_cents+? WHERE uuid=? AND balance_cents<=?"
                        : "UPDATE cr_bank_accounts SET balance_cents=balance_cents-? WHERE uuid=? AND balance_cents>=?";
                try (PreparedStatement bank = connection.prepareStatement(bankSql)) {
                    bank.setLong(1, amountCents);
                    bank.setString(2, uuid.toString());
                    bank.setLong(3, deposit ? maxBalanceCents - amountCents : amountCents);
                    if (bank.executeUpdate() == 0) {
                        connection.rollback();
                        return BankResult.failed(deposit ? "bank-limit" : "bank-funds");
                    }
                }
                insertTransaction(connection, deposit ? "BANK_DEPOSIT" : "BANK_WITHDRAW", uuid,
                        uuid, amountCents, "bank transfer", uuid, now);
                BankSnapshot result = new BankSnapshot(readBalance(connection, uuid), readBankBalance(connection, uuid));
                connection.commit();
                return BankResult.success(result);
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        });
    }

    public CompletableFuture<LoanResult> takeLoan(UUID uuid, String name, long amountCents,
                                                   long initialWalletCents, long maxWalletCents,
                                                   long termMillis) {
        return supply(connection -> {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                ensureAccount(connection, uuid, name, initialWalletCents);
                try (PreparedStatement active = connection.prepareStatement(
                        "SELECT 1 FROM cr_loans WHERE uuid=? AND status IN('ACTIVE','DEFAULTED') LIMIT 1")) {
                    active.setString(1, uuid.toString());
                    try (ResultSet rows = active.executeQuery()) {
                        if (rows.next()) {
                            connection.rollback();
                            return LoanResult.failed("loan-active");
                        }
                    }
                }
                long now = System.currentTimeMillis();
                try (PreparedStatement credit = connection.prepareStatement(
                        "UPDATE cr_accounts SET balance_cents=balance_cents+?,updated_at=? WHERE uuid=? AND balance_cents<=?")) {
                    credit.setLong(1, amountCents);
                    credit.setLong(2, now);
                    credit.setString(3, uuid.toString());
                    credit.setLong(4, maxWalletCents - amountCents);
                    if (credit.executeUpdate() == 0) {
                        connection.rollback();
                        return LoanResult.failed("wallet-limit");
                    }
                }
                long dueAt = now + termMillis;
                try (PreparedStatement loan = connection.prepareStatement(
                        "INSERT INTO cr_loans(uuid,principal_cents,outstanding_cents,due_at,status,created_at) VALUES(?,?,?,?,?,?)")) {
                    loan.setString(1, uuid.toString());
                    loan.setLong(2, amountCents);
                    loan.setLong(3, amountCents);
                    loan.setLong(4, dueAt);
                    loan.setString(5, "ACTIVE");
                    loan.setLong(6, now);
                    loan.executeUpdate();
                }
                insertTransaction(connection, "LOAN_ISSUE", null, uuid, amountCents,
                        "player loan", uuid, now);
                long wallet = readBalance(connection, uuid);
                connection.commit();
                return LoanResult.success(wallet, dueAt, amountCents);
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        });
    }

    public CompletableFuture<LoanResult> repayLoan(UUID uuid, String name, long requestedCents,
                                                    long initialWalletCents) {
        return supply(connection -> {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                ensureAccount(connection, uuid, name, initialWalletCents);
                long loanId;
                long outstanding;
                String status;
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT id,outstanding_cents,status FROM cr_loans WHERE uuid=? AND status IN('ACTIVE','DEFAULTED') ORDER BY id LIMIT 1")) {
                    query.setString(1, uuid.toString());
                    try (ResultSet rows = query.executeQuery()) {
                        if (!rows.next()) {
                            connection.rollback();
                            return LoanResult.failed("loan-none");
                        }
                        loanId = rows.getLong("id");
                        outstanding = rows.getLong("outstanding_cents");
                        status = rows.getString("status");
                    }
                }
                long amount = Math.min(requestedCents, outstanding);
                long now = System.currentTimeMillis();
                try (PreparedStatement debit = connection.prepareStatement(
                        "UPDATE cr_accounts SET balance_cents=balance_cents-?,updated_at=? WHERE uuid=? AND balance_cents>=?")) {
                    debit.setLong(1, amount);
                    debit.setLong(2, now);
                    debit.setString(3, uuid.toString());
                    debit.setLong(4, amount);
                    if (debit.executeUpdate() == 0) {
                        connection.rollback();
                        return LoanResult.failed("wallet-funds");
                    }
                }
                long remainder = outstanding - amount;
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE cr_loans SET outstanding_cents=?,status=? WHERE id=?")) {
                    update.setLong(1, remainder);
                    update.setString(2, remainder == 0 ? "PAID" : status);
                    update.setLong(3, loanId);
                    update.executeUpdate();
                }
                insertTransaction(connection, "LOAN_REPAY", uuid, null, amount,
                        "loan repayment", uuid, now);
                long wallet = readBalance(connection, uuid);
                connection.commit();
                return LoanResult.success(wallet, 0, remainder);
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        });
    }

    /** Credits simple daily interest for every fully elapsed interest period. */
    public CompletableFuture<Integer> processInterest(long now, long periodMillis, double dailyPercent,
                                                       long maxBankBalanceCents) {
        return supply(connection -> {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            int changed = 0;
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT uuid,balance_cents,last_interest FROM cr_bank_accounts");
                 ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    UUID uuid = UUID.fromString(rows.getString("uuid"));
                    long balance = rows.getLong("balance_cents");
                    long last = rows.getLong("last_interest");
                    long periods = Math.max(0, (now - last) / periodMillis);
                    if (periods == 0) {
                        continue;
                    }
                    long interest = java.math.BigDecimal.valueOf(balance)
                            .multiply(java.math.BigDecimal.valueOf(dailyPercent))
                            .multiply(java.math.BigDecimal.valueOf(periods))
                            .divide(java.math.BigDecimal.valueOf(100), 0, java.math.RoundingMode.HALF_UP)
                            .longValueExact();
                    interest = Math.min(interest, Math.max(0, maxBankBalanceCents - balance));
                    long nextInterest = last + periods * periodMillis;
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE cr_bank_accounts SET balance_cents=?,last_interest=? WHERE uuid=?")) {
                        update.setLong(1, balance + interest);
                        update.setLong(2, nextInterest);
                        update.setString(3, uuid.toString());
                        update.executeUpdate();
                    }
                    if (interest > 0) {
                        insertTransaction(connection, "BANK_INTEREST", null, uuid, interest,
                                "daily bank interest", null, now);
                    }
                    changed++;
                }
                connection.commit();
                return changed;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        });
    }

    /** Applies one default penalty when a loan first passes its due date. */
    public CompletableFuture<Integer> processOverdueLoans(long now, double penaltyPercent) {
        return supply(connection -> {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            int count = 0;
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT id,uuid,outstanding_cents FROM cr_loans WHERE status='ACTIVE' AND due_at<?")) {
                query.setLong(1, now);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        long id = rows.getLong("id");
                        UUID uuid = UUID.fromString(rows.getString("uuid"));
                        long outstanding = rows.getLong("outstanding_cents");
                        long penalty = java.math.BigDecimal.valueOf(outstanding)
                                .multiply(java.math.BigDecimal.valueOf(penaltyPercent))
                                .divide(java.math.BigDecimal.valueOf(100), 0, java.math.RoundingMode.HALF_UP)
                                .longValueExact();
                        try (PreparedStatement update = connection.prepareStatement(
                                "UPDATE cr_loans SET outstanding_cents=?,status='DEFAULTED' WHERE id=? AND status='ACTIVE'")) {
                            update.setLong(1, Math.addExact(outstanding, penalty));
                            update.setLong(2, id);
                            update.executeUpdate();
                        }
                        if (penalty > 0) {
                            insertTransaction(connection, "LOAN_PENALTY", null, uuid, penalty,
                                    "overdue loan penalty", null, now);
                        }
                        count++;
                    }
                }
                connection.commit();
                return count;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        });
    }

    private static void ensureBankAccount(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT OR IGNORE INTO cr_bank_accounts(uuid,balance_cents,last_interest,created_at) VALUES(?,0,?,?)")) {
            long now = System.currentTimeMillis();
            insert.setString(1, uuid.toString());
            insert.setLong(2, now);
            insert.setLong(3, now);
            insert.executeUpdate();
        }
    }

    private static long readBankBalance(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT balance_cents FROM cr_bank_accounts WHERE uuid=?")) {
            query.setString(1, uuid.toString());
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) throw new SQLException("missing bank account " + uuid);
                return rows.getLong(1);
            }
        }
    }

    private static void insertTransaction(Connection connection, String type, UUID from, UUID to,
                                          long amount, String reason, UUID actor, long now) throws SQLException {
        try (PreparedStatement transaction = connection.prepareStatement(
                "INSERT INTO cr_transactions(type,from_uuid,to_uuid,amount_cents,reason,actor,created_at) VALUES(?,?,?,?,?,?,?)")) {
            transaction.setString(1, type);
            transaction.setString(2, from == null ? null : from.toString());
            transaction.setString(3, to == null ? null : to.toString());
            transaction.setLong(4, amount);
            transaction.setString(5, reason);
            transaction.setString(6, actor == null ? null : actor.toString());
            transaction.setLong(7, now);
            transaction.executeUpdate();
        }
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

    public record BankSnapshot(long walletCents, long bankCents) { }

    public record BankResult(boolean success, String failure, BankSnapshot balances) {
        static BankResult success(BankSnapshot balances) { return new BankResult(true, null, balances); }
        static BankResult failed(String reason) { return new BankResult(false, reason, null); }
    }

    public record LoanResult(boolean success, String failure, long walletCents, long dueAt, long remainingCents) {
        static LoanResult success(long wallet, long dueAt, long remaining) {
            return new LoanResult(true, null, wallet, dueAt, remaining);
        }
        static LoanResult failed(String reason) { return new LoanResult(false, reason, 0, 0, 0); }
    }

    public static final class StorageFailure extends RuntimeException {
        public StorageFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
