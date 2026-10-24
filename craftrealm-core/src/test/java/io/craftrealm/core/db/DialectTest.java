package io.craftrealm.core.db;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL generation is the one place where a typo compiles and then fails on a live
 * server, so the two dialects are pinned down here.
 */
class DialectTest {

    private static final String[] COLUMNS = {"material", "price", "total_sold"};
    private static final String[] KEYS = {"material"};

    @Test
    void sqliteUsesOnConflict() {
        String sql = Dialect.SQLITE.upsert("cr_market", COLUMNS, KEYS);
        assertEquals("INSERT INTO cr_market (material, price, total_sold) VALUES (?,?,?) "
                + "ON CONFLICT (material) DO UPDATE SET price = excluded.price, "
                + "total_sold = excluded.total_sold", sql);
    }

    @Test
    void mysqlUsesOnDuplicateKey() {
        String sql = Dialect.MYSQL.upsert("cr_market", COLUMNS, KEYS);
        assertEquals("INSERT INTO cr_market (material, price, total_sold) VALUES (?,?,?) "
                + "ON DUPLICATE KEY UPDATE price = VALUES(price), "
                + "total_sold = VALUES(total_sold)", sql);
    }

    @Test
    void keyOnlyUpsertDegradesToPlainInsert() {
        String sql = Dialect.SQLITE.upsert("cr_meta", new String[]{"k"}, new String[]{"k"});
        assertEquals("INSERT INTO cr_meta (k) VALUES (?)", sql);
        assertTrue(Dialect.MYSQL.upsert("cr_meta", new String[]{"k"}, new String[]{"k"})
                .endsWith("VALUES (?)"));
    }

    @Test
    void placeholderCountMatchesColumnCount() {
        for (Dialect dialect : Dialect.values()) {
            String sql = dialect.upsert("cr_market", COLUMNS, KEYS);
            String values = sql.substring(sql.indexOf("VALUES (") + 8, sql.indexOf(")", sql.indexOf("VALUES (")));
            assertEquals(COLUMNS.length, values.chars().filter(c -> c == '?').count());
        }
    }

    @Test
    void primaryKeysAndBlobsDifferPerEngine() {
        assertTrue(Dialect.SQLITE.primaryKey().contains("AUTOINCREMENT"));
        assertTrue(Dialect.MYSQL.primaryKey().contains("AUTO_INCREMENT"));
        assertEquals("BLOB", Dialect.SQLITE.blob());
        assertEquals("MEDIUMBLOB", Dialect.MYSQL.blob());
        assertTrue(Dialect.MYSQL.serverBased());
        assertTrue(!Dialect.SQLITE.serverBased());
    }
}
