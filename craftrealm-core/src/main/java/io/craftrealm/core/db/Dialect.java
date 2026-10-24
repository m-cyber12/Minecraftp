package io.craftrealm.core.db;

/**
 * The two supported SQL dialects.
 *
 * <p>Everything engine specific lives here: auto-increment syntax, upsert
 * clauses and blob types. Repositories never contain {@code if (mysql)} checks.</p>
 */
public enum Dialect {

    SQLITE("INTEGER PRIMARY KEY AUTOINCREMENT", "BLOB", false),
    MYSQL("BIGINT AUTO_INCREMENT PRIMARY KEY", "MEDIUMBLOB", true);

    private final String primaryKey;
    private final String blob;
    private final boolean server;

    Dialect(String primaryKey, String blob, boolean server) {
        this.primaryKey = primaryKey;
        this.blob = blob;
        this.server = server;
    }

    /** Full column definition for an auto-increment id column. */
    public String primaryKey() {
        return primaryKey;
    }

    /** Binary column type suitable for serialised items. */
    public String blob() {
        return blob;
    }

    /** True for shared, multi-node databases. */
    public boolean serverBased() {
        return server;
    }

    /**
     * Builds an upsert for a single row.
     *
     * @param table      target table
     * @param columns    every inserted column, in order
     * @param keyColumns columns forming the conflict target
     * @return SQL with one {@code ?} per column
     */
    public String upsert(String table, String[] columns, String[] keyColumns) {
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(table).append(" (")
                .append(String.join(", ", columns)).append(") VALUES (")
                .append("?,".repeat(columns.length));
        sql.setLength(sql.length() - 1);
        sql.append(')');
        boolean updatable = false;
        for (String column : columns) {
            if (!contains(keyColumns, column)) {
                updatable = true;
                break;
            }
        }
        if (!updatable) {
            // Nothing but key columns: a plain INSERT is the correct upsert.
            return sql.toString();
        }
        if (this == SQLITE) {
            sql.append(" ON CONFLICT (").append(String.join(", ", keyColumns)).append(") DO UPDATE SET ");
            for (String column : columns) {
                if (!contains(keyColumns, column)) {
                    sql.append(column).append(" = excluded.").append(column).append(", ");
                }
            }
        } else {
            sql.append(" ON DUPLICATE KEY UPDATE ");
            for (String column : columns) {
                if (!contains(keyColumns, column)) {
                    sql.append(column).append(" = VALUES(").append(column).append("), ");
                }
            }
        }
        sql.setLength(sql.length() - 2);
        return sql.toString();
    }

    private static boolean contains(String[] values, String needle) {
        for (String value : values) {
            if (value.equalsIgnoreCase(needle)) {
                return true;
            }
        }
        return false;
    }
}
