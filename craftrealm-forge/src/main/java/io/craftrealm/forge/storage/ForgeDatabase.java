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
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
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
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS cr_claims (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        dimension TEXT NOT NULL,
                        chunk_x INTEGER NOT NULL,
                        chunk_z INTEGER NOT NULL,
                        owner TEXT NOT NULL,
                        owner_name TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        last_upkeep INTEGER NOT NULL,
                        UNIQUE(dimension, chunk_x, chunk_z)
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_cr_claims_owner ON cr_claims(owner)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS cr_season_points (
                        season_key BIGINT NOT NULL,
                        uuid TEXT NOT NULL,
                        name TEXT NOT NULL,
                        points INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(season_key, uuid)
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_cr_season_points_rank ON cr_season_points(season_key, points DESC)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS cr_cosmetics (
                        uuid TEXT PRIMARY KEY NOT NULL,
                        trail TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS cr_daily_quests (
                        uuid TEXT PRIMARY KEY NOT NULL,
                        day_key BIGINT NOT NULL,
                        progress INTEGER NOT NULL DEFAULT 0,
                        claimed INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS cr_market (
                        item_id TEXT PRIMARY KEY NOT NULL,
                        base_price_cents INTEGER NOT NULL CHECK(base_price_cents > 0),
                        price_cents INTEGER NOT NULL CHECK(price_cents > 0),
                        traded_units INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS cr_player_job (
                        uuid TEXT PRIMARY KEY NOT NULL,
                        active_job TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS cr_jobs (
                        uuid TEXT NOT NULL,
                        job TEXT NOT NULL,
                        level INTEGER NOT NULL DEFAULT 1,
                        xp BIGINT NOT NULL DEFAULT 0,
                        earned_cents INTEGER NOT NULL DEFAULT 0,
                        day_key BIGINT NOT NULL,
                        PRIMARY KEY(uuid, job)
                    )
                    """);
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
        return supply(connection -> inTransaction(connection, ignored -> {
            ensureAccount(connection, player, name, initialBalanceCents);
            return readBalance(connection, player);
        }));
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
        return supply(connection -> inTransaction(connection, ignored -> {
            ensureAccount(connection, uuid, name, initialWalletCents);
            ensureBankAccount(connection, uuid);
            return new BankSnapshot(readBalance(connection, uuid), readBankBalance(connection, uuid));
        }));
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
            try {
                List<InterestRow> accounts = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT uuid,balance_cents,last_interest FROM cr_bank_accounts");
                 ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    accounts.add(new InterestRow(UUID.fromString(rows.getString("uuid")),
                            rows.getLong("balance_cents"), rows.getLong("last_interest")));
                }
            }
            for (InterestRow account : accounts) {
                long periods = Math.max(0, (now - account.lastInterest()) / periodMillis);
                if (periods == 0) {
                    continue;
                }
                long room = Math.max(0, maxBankBalanceCents - account.balanceCents());
                java.math.BigDecimal calculated = java.math.BigDecimal.valueOf(account.balanceCents())
                        .multiply(java.math.BigDecimal.valueOf(dailyPercent))
                        .multiply(java.math.BigDecimal.valueOf(periods))
                        .divide(java.math.BigDecimal.valueOf(100), 0, java.math.RoundingMode.HALF_UP);
                long interest = calculated.min(java.math.BigDecimal.valueOf(room)).longValueExact();
                long nextInterest = account.lastInterest() + periods * periodMillis;
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE cr_bank_accounts SET balance_cents=?,last_interest=? WHERE uuid=?")) {
                    update.setLong(1, account.balanceCents() + interest);
                    update.setLong(2, nextInterest);
                    update.setString(3, account.uuid().toString());
                    update.executeUpdate();
                }
                if (interest > 0) {
                    insertTransaction(connection, "BANK_INTEREST", null, account.uuid(), interest,
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
            try {
                List<LoanRow> overdue = new ArrayList<>();
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT id,uuid,outstanding_cents FROM cr_loans WHERE status='ACTIVE' AND due_at<?")) {
                    query.setLong(1, now);
                    try (ResultSet rows = query.executeQuery()) {
                        while (rows.next()) {
                            overdue.add(new LoanRow(rows.getLong("id"),
                                    UUID.fromString(rows.getString("uuid")), rows.getLong("outstanding_cents")));
                        }
                    }
                }
                for (LoanRow loan : overdue) {
                    long penalty = java.math.BigDecimal.valueOf(loan.outstandingCents())
                            .multiply(java.math.BigDecimal.valueOf(penaltyPercent))
                            .divide(java.math.BigDecimal.valueOf(100), 0, java.math.RoundingMode.HALF_UP)
                            .longValueExact();
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE cr_loans SET outstanding_cents=?,status='DEFAULTED' WHERE id=? AND status='ACTIVE'")) {
                        update.setLong(1, Math.addExact(loan.outstandingCents(), penalty));
                        update.setLong(2, loan.id());
                        if (update.executeUpdate() == 0) continue;
                    }
                    if (penalty > 0) {
                        insertTransaction(connection, "LOAN_PENALTY", null, loan.uuid(), penalty,
                                "overdue loan penalty", null, now);
                    }
                    count++;
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

    private record InterestRow(UUID uuid, long balanceCents, long lastInterest) { }
    private record LoanRow(long id, UUID uuid, long outstandingCents) { }

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

    /** Staff wallet adjustment with the administrator recorded in the immutable transaction ledger. */
    public CompletableFuture<AdminAdjustment> adminAdjust(UUID administrator, UUID target, String targetName,
                                                            long amountCents, boolean credit,
                                                            long initialBalanceCents, long maxBalanceCents) {
        if (amountCents <= 0) return CompletableFuture.completedFuture(AdminAdjustment.failed("amount"));
        return supply(connection -> {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                ensureAccount(connection, target, targetName, initialBalanceCents);
                long now = System.currentTimeMillis();
                String sql = credit
                        ? "UPDATE cr_accounts SET balance_cents=balance_cents+?,updated_at=? WHERE uuid=? AND balance_cents<=?"
                        : "UPDATE cr_accounts SET balance_cents=balance_cents-?,updated_at=? WHERE uuid=? AND balance_cents>=?";
                try (PreparedStatement update = connection.prepareStatement(sql)) {
                    update.setLong(1, amountCents);
                    update.setLong(2, now);
                    update.setString(3, target.toString());
                    update.setLong(4, credit ? maxBalanceCents - amountCents : amountCents);
                    if (update.executeUpdate() == 0) {
                        connection.rollback();
                        return AdminAdjustment.failed(credit ? "wallet-limit" : "wallet-funds");
                    }
                }
                insertTransaction(connection, credit ? "ADMIN_CREDIT" : "ADMIN_DEBIT",
                        credit ? null : target, credit ? target : null, amountCents,
                        credit ? "admin grant by " + administrator : "admin withdrawal by " + administrator,
                        administrator, now);
                long balance = readBalance(connection, target);
                connection.commit();
                return new AdminAdjustment(true, null, balance);
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        });
    }

    /** Inserts configured market items without overwriting prices already changed by players. */
    public CompletableFuture<Void> seedMarket(Map<String, Long> catalogue) {
        return supply(connection -> inTransaction(connection, ignored -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT OR IGNORE INTO cr_market(item_id,base_price_cents,price_cents,traded_units) VALUES(?,?,?,0)")) {
                for (Map.Entry<String, Long> item : catalogue.entrySet()) {
                    if (item.getValue() <= 0) continue;
                    insert.setString(1, item.getKey());
                    insert.setLong(2, item.getValue());
                    insert.setLong(3, item.getValue());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            return null;
        }));
    }

    public CompletableFuture<List<MarketQuote>> marketQuotes() {
        return supply(connection -> {
            List<MarketQuote> quotes = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT item_id,base_price_cents,price_cents,traded_units FROM cr_market ORDER BY item_id");
                 ResultSet rows = query.executeQuery()) {
                while (rows.next()) quotes.add(new MarketQuote(rows.getString(1), rows.getLong(2),
                        rows.getLong(3), rows.getLong(4)));
            }
            return List.copyOf(quotes);
        });
    }

    public CompletableFuture<MarketQuote> marketQuote(String itemId) {
        return supply(connection -> {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT item_id,base_price_cents,price_cents,traded_units FROM cr_market WHERE item_id=?")) {
                query.setString(1, itemId);
                try (ResultSet rows = query.executeQuery()) {
                    return rows.next() ? new MarketQuote(rows.getString(1), rows.getLong(2),
                            rows.getLong(3), rows.getLong(4)) : null;
                }
            }
        });
    }

    /** Atomic wallet and dynamic-price update. Inventory delivery/removal must be done by the Forge adapter. */
    public CompletableFuture<MarketTrade> tradeMarket(UUID uuid, String playerName, String itemId, int quantity,
                                                       boolean buy, long initialBalanceCents,
                                                       long maxBalanceCents) {
        if (quantity < 1 || quantity > 64) return CompletableFuture.completedFuture(MarketTrade.failed("quantity"));
        return supply(connection -> {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                ensureAccount(connection, uuid, playerName, initialBalanceCents);
                long basePrice;
                long unitPrice;
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT base_price_cents,price_cents FROM cr_market WHERE item_id=?")) {
                    query.setString(1, itemId);
                    try (ResultSet rows = query.executeQuery()) {
                        if (!rows.next()) {
                            connection.rollback();
                            return MarketTrade.failed("unknown-item");
                        }
                        basePrice = rows.getLong(1);
                        unitPrice = rows.getLong(2);
                    }
                }
                long total = Math.multiplyExact(unitPrice, quantity);
                long now = System.currentTimeMillis();
                String updateSql = buy
                        ? "UPDATE cr_accounts SET balance_cents=balance_cents-?,updated_at=? WHERE uuid=? AND balance_cents>=?"
                        : "UPDATE cr_accounts SET balance_cents=balance_cents+?,updated_at=? WHERE uuid=? AND balance_cents<=?";
                try (PreparedStatement update = connection.prepareStatement(updateSql)) {
                    update.setLong(1, total);
                    update.setLong(2, now);
                    update.setString(3, uuid.toString());
                    update.setLong(4, buy ? total : maxBalanceCents - total);
                    if (update.executeUpdate() == 0) {
                        connection.rollback();
                        return MarketTrade.failed(buy ? "wallet-funds" : "wallet-limit");
                    }
                }
                long pressure = Math.multiplyExact(Math.max(1L, basePrice / 100L), quantity);
                long nextPrice = buy ? Math.min(Math.multiplyExact(basePrice, 5L), Math.addExact(unitPrice, pressure))
                        : Math.max(Math.max(1L, basePrice / 5L), unitPrice - Math.min(unitPrice - 1L, pressure));
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE cr_market SET price_cents=?,traded_units=traded_units+? WHERE item_id=?")) {
                    update.setLong(1, nextPrice);
                    update.setLong(2, quantity);
                    update.setString(3, itemId);
                    update.executeUpdate();
                }
                insertTransaction(connection, buy ? "SHOP_BUY" : "SHOP_SELL", buy ? uuid : null,
                        buy ? null : uuid, total, (buy ? "bought " : "sold ") + quantity + " " + itemId, uuid, now);
                long wallet = readBalance(connection, uuid);
                connection.commit();
                return MarketTrade.success(itemId, quantity, unitPrice, total, wallet, nextPrice);
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        });
    }

    /** Refunds a charged but undelivered shop order and records the compensating ledger entry. */
    public CompletableFuture<Void> refundMarketPurchase(UUID uuid, long amountCents, String itemId, int quantity) {
        if (amountCents <= 0) return CompletableFuture.completedFuture(null);
        return supply(connection -> inTransaction(connection, ignored -> {
            long now = System.currentTimeMillis();
            try (PreparedStatement credit = connection.prepareStatement(
                    "UPDATE cr_accounts SET balance_cents=balance_cents+?,updated_at=? WHERE uuid=?")) {
                credit.setLong(1, amountCents);
                credit.setLong(2, now);
                credit.setString(3, uuid.toString());
                if (credit.executeUpdate() == 0) throw new SQLException("cannot refund shop order without account " + uuid);
            }
            insertTransaction(connection, "SHOP_REFUND", null, uuid, amountCents,
                    "undelivered " + quantity + " " + itemId, null, now);
            return null;
        }));
    }

    public CompletableFuture<Boolean> chooseJob(UUID uuid, String job) {
        return supply(connection -> inTransaction(connection, ignored -> {
            long today = Math.floorDiv(System.currentTimeMillis(), 86_400_000L);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT OR IGNORE INTO cr_jobs(uuid,job,level,xp,earned_cents,day_key) VALUES(?,?,1,0,0,?)")) {
                insert.setString(1, uuid.toString());
                insert.setString(2, job);
                insert.setLong(3, today);
                insert.executeUpdate();
            }
            try (PreparedStatement upsert = connection.prepareStatement(
                    "INSERT INTO cr_player_job(uuid,active_job) VALUES(?,?) "
                            + "ON CONFLICT(uuid) DO UPDATE SET active_job=excluded.active_job")) {
                upsert.setString(1, uuid.toString());
                upsert.setString(2, job);
                upsert.executeUpdate();
            }
            return true;
        }));
    }

    public CompletableFuture<JobState> jobState(UUID uuid) {
        return supply(connection -> {
            String activeJob = null;
            try (PreparedStatement active = connection.prepareStatement(
                    "SELECT active_job FROM cr_player_job WHERE uuid=?")) {
                active.setString(1, uuid.toString());
                try (ResultSet rows = active.executeQuery()) {
                    if (rows.next()) activeJob = rows.getString(1);
                }
            }
            if (activeJob == null) return new JobState(null, 0, 0, 0, 0);
            long today = Math.floorDiv(System.currentTimeMillis(), 86_400_000L);
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT level,xp,earned_cents,day_key FROM cr_jobs WHERE uuid=? AND job=?")) {
                query.setString(1, uuid.toString());
                query.setString(2, activeJob);
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) return new JobState(activeJob, 1, 0, 0, today);
                    long earned = rows.getLong("day_key") == today ? rows.getLong("earned_cents") : 0;
                    return new JobState(activeJob, rows.getInt("level"), rows.getLong("xp"), earned, today);
                }
            }
        });
    }

    public CompletableFuture<String> cosmetic(UUID uuid) {
        return supply(connection -> {
            try (PreparedStatement query = connection.prepareStatement("SELECT trail FROM cr_cosmetics WHERE uuid=?")) {
                query.setString(1, uuid.toString());
                try (ResultSet rows = query.executeQuery()) {
                    return rows.next() ? rows.getString(1) : null;
                }
            }
        });
    }

    public CompletableFuture<java.util.Map<UUID, String>> loadCosmetics() {
        return supply(connection -> {
            java.util.Map<UUID, String> trails = new java.util.HashMap<>();
            try (PreparedStatement query = connection.prepareStatement("SELECT uuid,trail FROM cr_cosmetics");
                 ResultSet rows = query.executeQuery()) {
                while (rows.next()) trails.put(UUID.fromString(rows.getString(1)), rows.getString(2));
            }
            return java.util.Map.copyOf(trails);
        });
    }

    public CompletableFuture<Void> setCosmetic(UUID uuid, String trail) {
        return supply(connection -> inTransaction(connection, ignored -> {
            if (trail == null || trail.equals("NONE")) {
                try (PreparedStatement delete = connection.prepareStatement("DELETE FROM cr_cosmetics WHERE uuid=?")) {
                    delete.setString(1, uuid.toString());
                    delete.executeUpdate();
                }
            } else {
                try (PreparedStatement upsert = connection.prepareStatement(
                        "INSERT INTO cr_cosmetics(uuid,trail) VALUES(?,?) "
                                + "ON CONFLICT(uuid) DO UPDATE SET trail=excluded.trail")) {
                    upsert.setString(1, uuid.toString());
                    upsert.setString(2, trail);
                    upsert.executeUpdate();
                }
            }
            return null;
        }));
    }

    public CompletableFuture<QuestState> questState(UUID uuid, int actionsRequired) {
        return supply(connection -> {
            long today = Math.floorDiv(System.currentTimeMillis(), 86_400_000L);
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT day_key,progress,claimed FROM cr_daily_quests WHERE uuid=?")) {
                query.setString(1, uuid.toString());
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next() || rows.getLong("day_key") != today) {
                        return new QuestState(0, actionsRequired, false, today);
                    }
                    return new QuestState(rows.getInt("progress"), actionsRequired,
                            rows.getInt("claimed") != 0, today);
                }
            }
        });
    }

    public CompletableFuture<QuestClaim> claimQuest(UUID uuid, String playerName, int actionsRequired,
                                                     long rewardCents, long initialBalanceCents,
                                                     long maxBalanceCents) {
        if (actionsRequired < 1 || rewardCents < 0) {
            return CompletableFuture.completedFuture(QuestClaim.failed("configuration"));
        }
        return supply(connection -> {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                ensureAccount(connection, uuid, playerName, initialBalanceCents);
                long today = Math.floorDiv(System.currentTimeMillis(), 86_400_000L);
                int progress;
                boolean claimed;
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT day_key,progress,claimed FROM cr_daily_quests WHERE uuid=?")) {
                    query.setString(1, uuid.toString());
                    try (ResultSet rows = query.executeQuery()) {
                        if (!rows.next() || rows.getLong("day_key") != today) {
                            connection.rollback();
                            return QuestClaim.failed("not-ready");
                        }
                        progress = rows.getInt("progress");
                        claimed = rows.getInt("claimed") != 0;
                    }
                }
                if (claimed) {
                    connection.rollback();
                    return QuestClaim.failed("already-claimed");
                }
                if (progress < actionsRequired) {
                    connection.rollback();
                    return QuestClaim.failed("not-ready");
                }
                long now = System.currentTimeMillis();
                if (rewardCents > 0) {
                    try (PreparedStatement credit = connection.prepareStatement(
                            "UPDATE cr_accounts SET balance_cents=balance_cents+?,updated_at=? WHERE uuid=? AND balance_cents<=?")) {
                        credit.setLong(1, rewardCents);
                        credit.setLong(2, now);
                        credit.setString(3, uuid.toString());
                        credit.setLong(4, maxBalanceCents - rewardCents);
                        if (credit.executeUpdate() == 0) {
                            connection.rollback();
                            return QuestClaim.failed("wallet-limit");
                        }
                    }
                    insertTransaction(connection, "QUEST_REWARD", null, uuid, rewardCents,
                            "daily profession quest", null, now);
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE cr_daily_quests SET claimed=1 WHERE uuid=? AND day_key=? AND claimed=0")) {
                    update.setString(1, uuid.toString());
                    update.setLong(2, today);
                    if (update.executeUpdate() != 1) {
                        connection.rollback();
                        return QuestClaim.failed("already-claimed");
                    }
                }
                long balance = readBalance(connection, uuid);
                connection.commit();
                return new QuestClaim(true, null, rewardCents, balance);
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        });
    }

    public CompletableFuture<Long> seasonScore(UUID uuid, long seasonKey) {
        return supply(connection -> {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT points FROM cr_season_points WHERE season_key=? AND uuid=?")) {
                query.setLong(1, seasonKey);
                query.setString(2, uuid.toString());
                try (ResultSet rows = query.executeQuery()) {
                    return rows.next() ? rows.getLong(1) : 0L;
                }
            }
        });
    }

    public CompletableFuture<List<SeasonRank>> seasonTop(long seasonKey, int limit) {
        return supply(connection -> {
            List<SeasonRank> ranks = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT uuid,name,points FROM cr_season_points WHERE season_key=? ORDER BY points DESC,name ASC LIMIT ?")) {
                query.setLong(1, seasonKey);
                query.setInt(2, Math.max(1, Math.min(100, limit)));
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) ranks.add(new SeasonRank(UUID.fromString(rows.getString(1)),
                            rows.getString(2), rows.getLong(3)));
                }
            }
            return List.copyOf(ranks);
        });
    }

    public CompletableFuture<JobAward> awardJob(UUID uuid, String playerName, String job,
                                                  long basePayoutCents, long dailyCapCents,
                                                  double multiplierPerLevel, int xpPerAction, int maxLevel,
                                                  long seasonKey, long initialBalanceCents,
                                                  long maxBalanceCents) {
        return supply(connection -> {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                String activeJob = null;
                try (PreparedStatement active = connection.prepareStatement(
                        "SELECT active_job FROM cr_player_job WHERE uuid=?")) {
                    active.setString(1, uuid.toString());
                    try (ResultSet rows = active.executeQuery()) {
                        if (rows.next()) activeJob = rows.getString(1);
                    }
                }
                if (!job.equals(activeJob)) {
                    connection.rollback();
                    return JobAward.failed("not-employed");
                }
                ensureAccount(connection, uuid, playerName, initialBalanceCents);
                long today = Math.floorDiv(System.currentTimeMillis(), 86_400_000L);
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT OR IGNORE INTO cr_jobs(uuid,job,level,xp,earned_cents,day_key) VALUES(?,?,1,0,0,?)")) {
                    insert.setString(1, uuid.toString());
                    insert.setString(2, job);
                    insert.setLong(3, today);
                    insert.executeUpdate();
                }
                int level;
                long xp;
                long earned;
                long oldDay;
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT level,xp,earned_cents,day_key FROM cr_jobs WHERE uuid=? AND job=?")) {
                    query.setString(1, uuid.toString());
                    query.setString(2, job);
                    try (ResultSet rows = query.executeQuery()) {
                        if (!rows.next()) throw new SQLException("job progress missing after insert");
                        level = rows.getInt("level");
                        xp = rows.getLong("xp");
                        earned = rows.getLong("earned_cents");
                        oldDay = rows.getLong("day_key");
                    }
                }
                if (oldDay != today) earned = 0;
                long multiplierBasisPoints = Math.round((1.0 + Math.max(0.0, multiplierPerLevel) * (level - 1)) * 10_000);
                long amount = java.math.BigDecimal.valueOf(basePayoutCents)
                        .multiply(java.math.BigDecimal.valueOf(multiplierBasisPoints))
                        .divide(java.math.BigDecimal.valueOf(10_000), 0, java.math.RoundingMode.HALF_UP)
                        .longValueExact();
                amount = Math.min(amount, Math.max(0, dailyCapCents - earned));
                if (amount <= 0) {
                    connection.rollback();
                    return JobAward.failed("daily-cap");
                }
                long now = System.currentTimeMillis();
                try (PreparedStatement credit = connection.prepareStatement(
                        "UPDATE cr_accounts SET balance_cents=balance_cents+?,updated_at=? WHERE uuid=? AND balance_cents<=?")) {
                    credit.setLong(1, amount);
                    credit.setLong(2, now);
                    credit.setString(3, uuid.toString());
                    credit.setLong(4, maxBalanceCents - amount);
                    if (credit.executeUpdate() == 0) {
                        connection.rollback();
                        return JobAward.failed("wallet-limit");
                    }
                }
                long newXp = xp + Math.max(1, xpPerAction);
                int newLevel = level;
                while (newLevel < maxLevel && newXp >= (long) newLevel * 100L) {
                    newXp -= (long) newLevel * 100L;
                    newLevel++;
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE cr_jobs SET level=?,xp=?,earned_cents=?,day_key=? WHERE uuid=? AND job=?")) {
                    update.setInt(1, newLevel);
                    update.setLong(2, newXp);
                    update.setLong(3, earned + amount);
                    update.setLong(4, today);
                    update.setString(5, uuid.toString());
                    update.setString(6, job);
                    update.executeUpdate();
                }
                try (PreparedStatement season = connection.prepareStatement(
                        "INSERT INTO cr_season_points(season_key,uuid,name,points) VALUES(?,?,?,1) "
                                + "ON CONFLICT(season_key,uuid) DO UPDATE SET "
                                + "points=cr_season_points.points+1,name=excluded.name")) {
                    season.setLong(1, seasonKey);
                    season.setString(2, uuid.toString());
                    season.setString(3, playerName);
                    season.executeUpdate();
                }
                try (PreparedStatement quest = connection.prepareStatement(
                        "INSERT INTO cr_daily_quests(uuid,day_key,progress,claimed) VALUES(?,?,1,0) "
                                + "ON CONFLICT(uuid) DO UPDATE SET "
                                + "progress=CASE WHEN cr_daily_quests.day_key=excluded.day_key THEN cr_daily_quests.progress+1 ELSE 1 END, "
                                + "claimed=CASE WHEN cr_daily_quests.day_key=excluded.day_key THEN cr_daily_quests.claimed ELSE 0 END, "
                                + "day_key=excluded.day_key")) {
                    quest.setString(1, uuid.toString());
                    quest.setLong(2, today);
                    quest.executeUpdate();
                }
                insertTransaction(connection, "JOB_INCOME", null, uuid, amount,
                        job + " work", null, now);
                long wallet = readBalance(connection, uuid);
                connection.commit();
                return JobAward.success(amount, wallet, newLevel, newXp, newLevel > level, earned + amount);
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        });
    }

    public CompletableFuture<List<Claim>> loadClaims() {
        return supply(connection -> {
            List<Claim> result = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT dimension,chunk_x,chunk_z,owner,owner_name,created_at,last_upkeep FROM cr_claims");
                 ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    result.add(new Claim(rows.getString("dimension"), rows.getInt("chunk_x"),
                            rows.getInt("chunk_z"), UUID.fromString(rows.getString("owner")),
                            rows.getString("owner_name"), rows.getLong("created_at"), rows.getLong("last_upkeep")));
                }
            }
            return result;
        });
    }

    public CompletableFuture<ClaimResult> createClaim(UUID owner, String ownerName, String dimension,
                                                       int chunkX, int chunkZ, long costCents,
                                                       long openingBalanceCents, int maximumClaims) {
        return supply(connection -> {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                ensureAccount(connection, owner, ownerName, openingBalanceCents);
                try (PreparedStatement existing = connection.prepareStatement(
                        "SELECT owner FROM cr_claims WHERE dimension=? AND chunk_x=? AND chunk_z=?")) {
                    existing.setString(1, dimension);
                    existing.setInt(2, chunkX);
                    existing.setInt(3, chunkZ);
                    try (ResultSet rows = existing.executeQuery()) {
                        if (rows.next()) {
                            connection.rollback();
                            return ClaimResult.failed("already-claimed");
                        }
                    }
                }
                try (PreparedStatement count = connection.prepareStatement(
                        "SELECT COUNT(*) FROM cr_claims WHERE owner=?")) {
                    count.setString(1, owner.toString());
                    try (ResultSet rows = count.executeQuery()) {
                        if (rows.next() && rows.getInt(1) >= maximumClaims) {
                            connection.rollback();
                            return ClaimResult.failed("claim-limit");
                        }
                    }
                }
                long now = System.currentTimeMillis();
                if (costCents > 0) {
                    try (PreparedStatement debit = connection.prepareStatement(
                            "UPDATE cr_accounts SET balance_cents=balance_cents-?,updated_at=? WHERE uuid=? AND balance_cents>=?")) {
                        debit.setLong(1, costCents);
                        debit.setLong(2, now);
                        debit.setString(3, owner.toString());
                        debit.setLong(4, costCents);
                        if (debit.executeUpdate() == 0) {
                            connection.rollback();
                            return ClaimResult.failed("insufficient-funds");
                        }
                    }
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO cr_claims(dimension,chunk_x,chunk_z,owner,owner_name,created_at,last_upkeep) VALUES(?,?,?,?,?,?,?)")) {
                    insert.setString(1, dimension);
                    insert.setInt(2, chunkX);
                    insert.setInt(3, chunkZ);
                    insert.setString(4, owner.toString());
                    insert.setString(5, ownerName);
                    insert.setLong(6, now);
                    insert.setLong(7, now);
                    insert.executeUpdate();
                }
                if (costCents > 0) {
                    insertTransaction(connection, "CLAIM_PURCHASE", owner, null, costCents,
                            "chunk claim", owner, now);
                }
                long wallet = readBalance(connection, owner);
                connection.commit();
                return ClaimResult.success(new Claim(dimension, chunkX, chunkZ, owner,
                        ownerName, now, now), wallet);
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                if (failure instanceof SQLException sql && sql.getMessage() != null
                        && sql.getMessage().toLowerCase(Locale.ROOT).contains("unique")) {
                    return ClaimResult.failed("already-claimed");
                }
                throw failure;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        });
    }

    public CompletableFuture<ClaimResult> removeClaim(UUID owner, String dimension, int chunkX, int chunkZ) {
        return supply(connection -> inTransaction(connection, ignored -> {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT dimension,chunk_x,chunk_z,owner,owner_name,created_at,last_upkeep FROM cr_claims WHERE dimension=? AND chunk_x=? AND chunk_z=?")) {
                query.setString(1, dimension);
                query.setInt(2, chunkX);
                query.setInt(3, chunkZ);
                Claim claim;
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) return ClaimResult.failed("not-claimed");
                    claim = new Claim(rows.getString("dimension"), rows.getInt("chunk_x"),
                            rows.getInt("chunk_z"), UUID.fromString(rows.getString("owner")),
                            rows.getString("owner_name"), rows.getLong("created_at"), rows.getLong("last_upkeep"));
                }
                if (!claim.owner().equals(owner)) return ClaimResult.failed("not-owner");
                try (PreparedStatement delete = connection.prepareStatement("DELETE FROM cr_claims WHERE id = (SELECT id FROM cr_claims "
                            + "WHERE dimension=? AND chunk_x=? AND chunk_z=? AND owner=?)")) {
                    delete.setString(1, dimension);
                    delete.setInt(2, chunkX);
                    delete.setInt(3, chunkZ);
                    delete.setString(4, owner.toString());
                    if (delete.executeUpdate() != 1) return ClaimResult.failed("not-owner");
                }
                return ClaimResult.removed(claim);
            }
        }));
    }

    private static void ensureAccount(Connection connection, UUID uuid, String name, long initialCents)
            throws SQLException {
        long now = System.currentTimeMillis();
        long openingCents = Math.max(0, initialCents);
        int inserted;
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT OR IGNORE INTO cr_accounts(uuid,name,balance_cents,created_at,updated_at) VALUES(?,?,?,?,?)")) {
            insert.setString(1, uuid.toString());
            insert.setString(2, name == null || name.isBlank() ? uuid.toString() : name);
            insert.setLong(3, openingCents);
            insert.setLong(4, now);
            insert.setLong(5, now);
            inserted = insert.executeUpdate();
        }
        if (inserted > 0 && openingCents > 0) {
            insertTransaction(connection, "STARTING_BALANCE", null, uuid, openingCents,
                    "account opening balance", null, now);
        }
        try (PreparedStatement rename = connection.prepareStatement(
                "UPDATE cr_accounts SET name = ?, updated_at = ? WHERE uuid = ?")) {
            rename.setString(1, name == null || name.isBlank() ? uuid.toString() : name);
            rename.setLong(2, now);
            rename.setString(3, uuid.toString());
            rename.executeUpdate();
        }
    }

    private static <T> T inTransaction(Connection connection, SqlWork<T> work) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            T result = work.apply(connection);
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
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

    public record AdminAdjustment(boolean success, String failure, long balanceCents) {
        public static AdminAdjustment failed(String reason) {
            return new AdminAdjustment(false, reason, 0);
        }
    }

    public record MarketQuote(String itemId, long basePriceCents, long priceCents, long tradedUnits) { }

    public record MarketTrade(boolean success, String failure, String itemId, int quantity,
                              long unitPriceCents, long totalCents, long walletCents, long nextPriceCents) {
        public static MarketTrade failed(String reason) {
            return new MarketTrade(false, reason, null, 0, 0, 0, 0, 0);
        }
        public static MarketTrade success(String itemId, int quantity, long unitPrice, long total,
                                          long wallet, long nextPrice) {
            return new MarketTrade(true, null, itemId, quantity, unitPrice, total, wallet, nextPrice);
        }
    }

    public record SeasonRank(UUID uuid, String name, long points) { }

    public record QuestState(int progress, int actionsRequired, boolean claimed, long dayKey) {
        public boolean readyToClaim() { return progress >= actionsRequired && !claimed; }
    }

    public record QuestClaim(boolean success, String failure, long rewardCents, long balanceCents) {
        static QuestClaim failed(String reason) { return new QuestClaim(false, reason, 0, 0); }
    }

    public record JobState(String activeJob, int level, long xp, long earnedTodayCents, long dayKey) { }

    public record JobAward(boolean success, String failure, long amountCents, long walletCents,
                           int level, long xp, boolean levelUp, long earnedTodayCents) {
        static JobAward success(long amount, long wallet, int level, long xp,
                                boolean levelUp, long earned) {
            return new JobAward(true, null, amount, wallet, level, xp, levelUp, earned);
        }
        static JobAward failed(String reason) {
            return new JobAward(false, reason, 0, 0, 0, 0, false, 0);
        }
    }

    public record Claim(String dimension, int chunkX, int chunkZ, UUID owner,
                        String ownerName, long createdAt, long lastUpkeep) { }

    public record ClaimResult(boolean success, String failure, Claim claim, long walletCents) {
        static ClaimResult success(Claim claim, long wallet) { return new ClaimResult(true, null, claim, wallet); }
        static ClaimResult removed(Claim claim) { return new ClaimResult(true, null, claim, 0); }
        public static ClaimResult failed(String reason) { return new ClaimResult(false, reason, null, 0); }
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
