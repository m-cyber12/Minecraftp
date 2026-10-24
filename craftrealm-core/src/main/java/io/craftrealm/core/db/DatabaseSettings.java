package io.craftrealm.core.db;

/**
 * Immutable storage configuration, built from {@code config.yml} by the plugin
 * module.
 *
 * @param type              backend engine
 * @param file              SQLite file path relative to the plugin data folder
 * @param host              MySQL host
 * @param port              MySQL port
 * @param database          MySQL schema name
 * @param username          MySQL user
 * @param password          MySQL password
 * @param properties        extra JDBC query parameters
 * @param poolSize          maximum connections
 * @param minimumIdle       connections kept warm
 * @param connectionTimeout how long to wait for a connection before failing
 * @param threads           size of the I/O pool
 * @param serverId          identifier written into the ledger for multi-node servers
 */
public record DatabaseSettings(Type type, String file, String host, int port, String database,
                               String username, String password, String properties,
                               int poolSize, int minimumIdle, long connectionTimeout,
                               int threads, String serverId) {

    public enum Type { SQLITE, MYSQL }

    public static DatabaseSettings sqlite(String file, int threads) {
        return new DatabaseSettings(Type.SQLITE, file, "", 0, "", "", "", "",
                1, 1, 5_000L, threads, "local");
    }

    public static DatabaseSettings mysql(String host, int port, String database, String username,
                                         String password, String properties, int poolSize, int threads) {
        return new DatabaseSettings(Type.MYSQL, "", host, port, database, username, password, properties,
                poolSize, Math.min(2, poolSize), 5_000L, threads, "local");
    }
}
