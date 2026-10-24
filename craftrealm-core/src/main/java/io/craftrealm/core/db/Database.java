package io.craftrealm.core.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * The single gateway to storage.
 *
 * <p>Owns a HikariCP pool and the I/O executor. Every public method either
 * returns a {@link CompletableFuture} (normal gameplay) or a blocking variant that
 * is only legal during startup/shutdown. Calling a {@code *Blocking} method from
 * the tick thread during play would freeze the server, so they are marked
 * {@code @Deprecated} in intent and used exclusively by {@code onEnable}.</p>
 */
public final class Database implements AutoCloseable {

    private final HikariDataSource dataSource;
    private final Dialect dialect;
    private final AsyncExecutor executor;
    private final Logger logger;

    private Database(HikariDataSource dataSource, Dialect dialect, AsyncExecutor executor, Logger logger) {
        this.dataSource = dataSource;
        this.dialect = dialect;
        this.executor = executor;
        this.logger = logger;
    }

    /**
     * Opens the pool and verifies the connection. Called on the main thread during
     * {@code onEnable}; fails fast so a misconfigured database never leaves a
     * half-initialised plugin running.
     */
    public static Database open(DatabaseSettings settings, Logger logger) throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setPoolName("CraftRealm");
        config.setMaximumPoolSize(settings.type() == DatabaseSettings.Type.SQLITE ? 1 : Math.max(2, settings.poolSize()));
        config.setMinimumIdle(settings.type() == DatabaseSettings.Type.SQLITE ? 1 : settings.minimumIdle());
        config.setConnectionTimeout(settings.connectionTimeout());
        config.setValidationTimeout(Math.min(3_000L, settings.connectionTimeout()));
        // Keep the connection usable across server reloads.
        config.setMaxLifetime(1_800_000L);
        config.setLeakDetectionThreshold(0L);

        if (settings.type() == DatabaseSettings.Type.SQLITE) {
            config.setDriverClassName("org.sqlite.JDBC");
            config.setJdbcUrl("jdbc:sqlite:" + settings.file());
            // WAL + busy timeout: the standard recipe for a plugin-side SQLite file.
            config.addDataSourceProperty("journal_mode", "WAL");
            config.addDataSourceProperty("synchronous", "NORMAL");
            config.addDataSourceProperty("busy_timeout", "5000");
            config.addDataSourceProperty("foreign_keys", "true");
        } else {
            config.setDriverClassName("com.mysql.cj.jdbc.Driver");
            String extra = settings.properties() == null || settings.properties().isBlank()
                    ? "" : "?" + settings.properties();
            config.setJdbcUrl("jdbc:mysql://" + settings.host() + ":" + settings.port()
                    + "/" + settings.database() + extra);
            config.setUsername(settings.username());
            config.setPassword(settings.password());
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            config.addDataSourceProperty("useServerPrepStmts", "true");
        }

        HikariDataSource dataSource = new HikariDataSource(config);
        // Fail fast: an unreachable database should abort enable, not log later.
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.isValid(3)) {
                throw new SQLException("database connection is not valid");
            }
        } catch (SQLException exception) {
            dataSource.close();
            throw exception;
        }

        Dialect dialect = settings.type() == DatabaseSettings.Type.SQLITE ? Dialect.SQLITE : Dialect.MYSQL;
        AsyncExecutor executor = new AsyncExecutor("CraftRealm-IO", Math.max(1, settings.threads()));
        logger.info("Storage ready (" + dialect.name().toLowerCase() + ")");
        return new Database(dataSource, dialect, executor, logger);
    }

    /** Runs work on the I/O pool with a pooled connection. */
    public <T> CompletableFuture<T> supply(SqlFunction<T> work) {
        return executor.supply(() -> {
            try (Connection connection = dataSource.getConnection()) {
                return work.apply(connection);
            } catch (SQLException exception) {
                throw new StorageException("query failed: " + exception.getMessage(), exception);
            }
        });
    }

    /** Runs work on the I/O pool, discarding the result. */
    public CompletableFuture<Void> run(SqlConsumer work) {
        return supply(connection -> {
            work.accept(connection);
            return null;
        });
    }

    /**
     * Runs work inside a transaction. Rollback happens on any exception, so a
     * partially applied ledger write can never survive.
     */
    public <T> CompletableFuture<T> inTransaction(SqlFunction<T> work) {
        return executor.supply(() -> transactionBlocking(work));
    }

    /** Transaction variant for callers that already know they are on the I/O pool. */
    public <T> T transactionBlocking(SqlFunction<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (Throwable throwable) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    logger.warning("Rollback failed: " + rollbackFailure.getMessage());
                }
                if (throwable instanceof SQLException sqlException) {
                    throw new StorageException("transaction failed: " + sqlException.getMessage(), sqlException);
                }
                if (throwable instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new StorageException("transaction failed", throwable);
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException exception) {
            throw new StorageException("could not obtain a connection: " + exception.getMessage(), exception);
        }
    }

    /** Blocking read — startup only. */
    public <T> T supplyBlocking(SqlFunction<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            return work.apply(connection);
        } catch (SQLException exception) {
            throw new StorageException("query failed: " + exception.getMessage(), exception);
        }
    }

    public Dialect dialect() {
        return dialect;
    }

    public AsyncExecutor executor() {
        return executor;
    }

    public Logger logger() {
        return logger;
    }

    /** Drains pending work, then closes the pool. */
    @Override
    public void close() {
        executor.shutdown(10);
        dataSource.close();
    }
}
