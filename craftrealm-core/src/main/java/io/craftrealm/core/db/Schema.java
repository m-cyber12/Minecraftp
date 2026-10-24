package io.craftrealm.core.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The CraftRealm schema.
 *
 * <p>Applied on every enable and fully idempotent: {@code CREATE TABLE IF NOT
 * EXISTS} for new tables, and a {@code schema_version} row in {@code cr_meta}
 * gates any future {@code ALTER TABLE} migration. Index creation is best-effort
 * because MySQL has no {@code CREATE INDEX IF NOT EXISTS}.</p>
 *
 * <p>Column type notes:</p>
 * <ul>
 *   <li>{@code {pk}} → auto-increment primary key ({@code INTEGER … } on SQLite,
 *       {@code BIGINT AUTO_INCREMENT} on MySQL).</li>
 *   <li>{@code {blob}} → {@code BLOB} / {@code MEDIUMBLOB}, used for serialised items.</li>
 *   <li>Money is {@code DECIMAL(20,2)}, timestamps are epoch millis in
 *       {@code BIGINT}, booleans are {@code TINYINT 0/1}.</li>
 * </ul>
 */
public final class Schema {

    /** Bump when a migration block is added below. */
    public static final int VERSION = 1;

    private Schema() {
    }

    private static final List<String> TABLES = List.of(
            """
            CREATE TABLE IF NOT EXISTS cr_meta (
                k VARCHAR(64) NOT NULL PRIMARY KEY,
                v VARCHAR(512)
            )""",
            """
            CREATE TABLE IF NOT EXISTS cr_accounts (
                uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                name VARCHAR(32),
                balance DECIMAL(20,2) NOT NULL DEFAULT 0,
                shards BIGINT NOT NULL DEFAULT 0,
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL
            )""",
            """
            CREATE TABLE IF NOT EXISTS cr_transactions (
                id {pk},
                type VARCHAR(32) NOT NULL,
                from_uuid VARCHAR(36),
                to_uuid VARCHAR(36),
                amount DECIMAL(20,2) NOT NULL,
                reason VARCHAR(255),
                actor VARCHAR(36),
                op_id VARCHAR(96),
                server VARCHAR(64),
                created_at BIGINT NOT NULL,
                reverted TINYINT NOT NULL DEFAULT 0,
                rollback_of BIGINT NOT NULL DEFAULT 0,
                UNIQUE (op_id)
            )""",
            """
            CREATE TABLE IF NOT EXISTS cr_bank_accounts (
                uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                balance DECIMAL(20,2) NOT NULL DEFAULT 0,
                last_interest BIGINT NOT NULL DEFAULT 0,
                created_at BIGINT NOT NULL
            )""",
            """
            CREATE TABLE IF NOT EXISTS cr_loans (
                id {pk},
                owner VARCHAR(36) NOT NULL,
                principal DECIMAL(20,2) NOT NULL,
                outstanding DECIMAL(20,2) NOT NULL,
                daily_rate DOUBLE NOT NULL,
                due_at BIGINT NOT NULL,
                created_at BIGINT NOT NULL,
                status VARCHAR(16) NOT NULL
            )""",
            """
            CREATE TABLE IF NOT EXISTS cr_market (
                material VARCHAR(64) NOT NULL PRIMARY KEY,
                base_price DOUBLE NOT NULL,
                price DOUBLE NOT NULL,
                total_sold BIGINT NOT NULL DEFAULT 0,
                total_bought BIGINT NOT NULL DEFAULT 0,
                pressure DOUBLE NOT NULL DEFAULT 0,
                updated_at BIGINT NOT NULL
            )""",
            """
            CREATE TABLE IF NOT EXISTS cr_market_history (
                id {pk},
                material VARCHAR(64) NOT NULL,
                price DOUBLE NOT NULL,
                volume BIGINT NOT NULL DEFAULT 0,
                recorded_at BIGINT NOT NULL
            )""",
            """
            CREATE TABLE IF NOT EXISTS cr_economy_snapshots (
                id {pk},
                total DECIMAL(20,2) NOT NULL,
                accounts INT NOT NULL,
                recorded_at BIGINT NOT NULL
            )""",
            """
            CREATE TABLE IF NOT EXISTS cr_auctions (
                id {pk},
                seller VARCHAR(36) NOT NULL,
                seller_name VARCHAR(32),
                item {blob} NOT NULL,
                starting_bid DECIMAL(20,2) NOT NULL,
                buy_now DECIMAL(20,2) NOT NULL DEFAULT 0,
                top_bidder VARCHAR(36),
                top_bid DECIMAL(20,2) NOT NULL DEFAULT 0,
                created_at BIGINT NOT NULL,
                ends_at BIGINT NOT NULL,
                status VARCHAR(16) NOT NULL,
                listing_fee DECIMAL(20,2) NOT NULL DEFAULT 0
            )""",
            """
            CREATE TABLE IF NOT EXISTS cr_auction_bids (
                id {pk},
                auction_id BIGINT NOT NULL,
                bidder VARCHAR(36) NOT NULL,
                amount DECIMAL(20,2) NOT NULL,
                created_at BIGINT NOT NULL
            )""",
            """
            CREATE TABLE IF NOT EXISTS cr_mailbox (
                id {pk},
                owner VARCHAR(36) NOT NULL,
                kind VARCHAR(16) NOT NULL,
                item {blob},
                amount DECIMAL(20,2) NOT NULL DEFAULT 0,
                note VARCHAR(255),
                claimed TINYINT NOT NULL DEFAULT 0,
                created_at BIGINT NOT NULL
            )""",
            """
            CREATE TABLE IF NOT EXISTS cr_jobs (
                uuid VARCHAR(36) NOT NULL,
                job VARCHAR(16) NOT NULL,
                level INT NOT NULL DEFAULT 1,
                xp BIGINT NOT NULL DEFAULT 0,
                earned_today DOUBLE NOT NULL DEFAULT 0,
                earn_day BIGINT NOT NULL DEFAULT 0,
                PRIMARY KEY (uuid, job)
            )""",
            """
            CREATE TABLE IF NOT EXISTS cr_players (
                uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                name VARCHAR(32),
                first_seen BIGINT NOT NULL DEFAULT 0,
                last_seen BIGINT NOT NULL DEFAULT 0,
                playtime BIGINT NOT NULL DEFAULT 0,
                frozen TINYINT NOT NULL DEFAULT 0,
                muted_until BIGINT NOT NULL DEFAULT 0,
                last_ip VARCHAR(64),
                streak INT NOT NULL DEFAULT 0,
                last_streak_day BIGINT NOT NULL DEFAULT 0
            )""",
            """
            CREATE TABLE IF NOT EXISTS cr_player_notes (
                id {pk},
                target VARCHAR(36) NOT NULL,
                staff VARCHAR(36) NOT NULL,
                text VARCHAR(1024) NOT NULL,
                created_at BIGINT NOT NULL
            )""",
            """
            CREATE TABLE IF NOT EXISTS cr_ips (
                id {pk},
                uuid VARCHAR(36) NOT NULL,
                ip VARCHAR(64) NOT NULL,
                seen_at BIGINT NOT NULL
            )""",
            """
            CREATE TABLE IF NOT EXISTS cr_warnings (
                id {pk},
                target VARCHAR(36) NOT NULL,
                staff VARCHAR(36) NOT NULL,
                reason VARCHAR(255) NOT NULL,
                created_at BIGINT NOT NULL,
                active TINYINT NOT NULL DEFAULT 1
            )""",
            """
            CREATE TABLE IF NOT EXISTS cr_punishments (
                id {pk},
                target VARCHAR(36) NOT NULL,
                type VARCHAR(16) NOT NULL,
                staff VARCHAR(36) NOT NULL,
                reason VARCHAR(255) NOT NULL,
                created_at BIGINT NOT NULL,
                expires_at BIGINT NOT NULL DEFAULT 0,
                active TINYINT NOT NULL DEFAULT 1
            )""",
            """
            CREATE TABLE IF NOT EXISTS cr_reports (
                id {pk},
                reporter VARCHAR(36) NOT NULL,
                reporter_name VARCHAR(32),
                target VARCHAR(36) NOT NULL,
                target_name VARCHAR(32),
                reason VARCHAR(512) NOT NULL,
                status VARCHAR(16) NOT NULL,
                created_at BIGINT NOT NULL,
                handled_by VARCHAR(36),
                handled_at BIGINT NOT NULL DEFAULT 0,
                resolution VARCHAR(512)
            )""",
            """
            CREATE TABLE IF NOT EXISTS cr_audit (
                id {pk},
                staff VARCHAR(36),
                staff_name VARCHAR(32),
                action VARCHAR(48) NOT NULL,
                target VARCHAR(64),
                detail VARCHAR(512),
                created_at BIGINT NOT NULL
            )""",
            """
            CREATE TABLE IF NOT EXISTS cr_bounties (
                id {pk},
                target VARCHAR(36) NOT NULL,
                target_name VARCHAR(32),
                placer VARCHAR(36) NOT NULL,
                amount DECIMAL(20,2) NOT NULL,
                created_at BIGINT NOT NULL,
                status VARCHAR(16) NOT NULL
            )""",
            """
            CREATE TABLE IF NOT EXISTS cr_claims (
                id {pk},
                owner VARCHAR(36) NOT NULL,
                world VARCHAR(64) NOT NULL,
                min_x INT NOT NULL,
                min_z INT NOT NULL,
                max_x INT NOT NULL,
                max_z INT NOT NULL,
                created_at BIGINT NOT NULL,
                paid_until BIGINT NOT NULL,
                last_tax_run BIGINT NOT NULL DEFAULT 0
            )""",
            """
            CREATE TABLE IF NOT EXISTS cr_chest_shops (
                id {pk},
                owner VARCHAR(36) NOT NULL,
                world VARCHAR(64) NOT NULL,
                x INT NOT NULL,
                y INT NOT NULL,
                z INT NOT NULL,
                item {blob} NOT NULL,
                amount INT NOT NULL DEFAULT 1,
                price DECIMAL(20,2) NOT NULL,
                stock INT NOT NULL DEFAULT -1
            )""",
            """
            CREATE TABLE IF NOT EXISTS cr_treasures (
                id {pk},
                world VARCHAR(64) NOT NULL,
                x DOUBLE NOT NULL,
                y DOUBLE NOT NULL,
                z DOUBLE NOT NULL,
                clue VARCHAR(255) NOT NULL,
                shards INT NOT NULL DEFAULT 1,
                found_by VARCHAR(36),
                found_at BIGINT NOT NULL DEFAULT 0
            )""",
            """
            CREATE TABLE IF NOT EXISTS cr_quests (
                id {pk},
                owner VARCHAR(36) NOT NULL,
                template VARCHAR(48) NOT NULL,
                kind VARCHAR(32) NOT NULL,
                title VARCHAR(128) NOT NULL,
                description VARCHAR(255),
                target DOUBLE NOT NULL,
                progress DOUBLE NOT NULL DEFAULT 0,
                reward_type VARCHAR(32) NOT NULL,
                reward DOUBLE NOT NULL,
                scope VARCHAR(8) NOT NULL,
                created_at BIGINT NOT NULL,
                expires_at BIGINT NOT NULL,
                status VARCHAR(16) NOT NULL
            )""",
            """
            CREATE TABLE IF NOT EXISTS cr_seasons (
                number INT NOT NULL PRIMARY KEY,
                started_at BIGINT NOT NULL,
                ends_at BIGINT NOT NULL,
                winner VARCHAR(36),
                winner_name VARCHAR(32),
                winner_score BIGINT NOT NULL DEFAULT 0
            )""",
            """
            CREATE TABLE IF NOT EXISTS cr_season_scores (
                season INT NOT NULL,
                uuid VARCHAR(36) NOT NULL,
                name VARCHAR(32),
                score BIGINT NOT NULL DEFAULT 0,
                PRIMARY KEY (season, uuid)
            )""",
            """
            CREATE TABLE IF NOT EXISTS cr_cosmetics (
                id {pk},
                owner VARCHAR(36) NOT NULL,
                cosmetic VARCHAR(64) NOT NULL,
                equipped TINYINT NOT NULL DEFAULT 0,
                unlocked_at BIGINT NOT NULL,
                UNIQUE (owner, cosmetic)
            )""",
            """
            CREATE TABLE IF NOT EXISTS cr_banknotes (
                id VARCHAR(64) NOT NULL PRIMARY KEY,
                issuer VARCHAR(36) NOT NULL,
                amount DECIMAL(20,2) NOT NULL,
                created_at BIGINT NOT NULL,
                redeemed_by VARCHAR(36),
                redeemed_at BIGINT NOT NULL DEFAULT 0
            )"""
    );

    private static final List<String> INDEXES = List.of(
            "CREATE INDEX idx_tx_created ON cr_transactions (created_at)",
            "CREATE INDEX idx_tx_from ON cr_transactions (from_uuid)",
            "CREATE INDEX idx_tx_to ON cr_transactions (to_uuid)",
            "CREATE INDEX idx_tx_op ON cr_transactions (op_id)",
            "CREATE INDEX idx_auction_status ON cr_auctions (status, ends_at)",
            "CREATE INDEX idx_mailbox_owner ON cr_mailbox (owner, claimed)",
            "CREATE INDEX idx_punish_target ON cr_punishments (target, active)",
            "CREATE INDEX idx_warn_target ON cr_warnings (target, active)",
            "CREATE INDEX idx_report_status ON cr_reports (status, created_at)",
            "CREATE INDEX idx_audit_created ON cr_audit (created_at)",
            "CREATE INDEX idx_ips_uuid ON cr_ips (uuid)",
            "CREATE INDEX idx_claims_owner ON cr_claims (owner)",
            "CREATE INDEX idx_claims_world ON cr_claims (world)",
            "CREATE INDEX idx_chestshop_chunk ON cr_chest_shops (world, x, z)",
            "CREATE INDEX idx_quest_owner ON cr_quests (owner, status)",
            "CREATE INDEX idx_season_score ON cr_season_scores (season, score)",
            "CREATE INDEX idx_market_history ON cr_market_history (material, recorded_at)"
    );

    /**
     * Creates every table and index. Safe to call on every startup.
     *
     * @return the schema version now in place
     */
    public static int apply(Database database) {
        Logger logger = database.logger();
        Dialect dialect = database.dialect();
        return database.supplyBlocking(connection -> {
            try (Statement statement = connection.createStatement()) {
                for (String ddl : TABLES) {
                    statement.execute(ddl.replace("{pk}", dialect.primaryKey()).replace("{blob}", dialect.blob()));
                }
            }
            for (String index : INDEXES) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(index);
                } catch (SQLException duplicate) {
                    // Already present — expected on every start after the first.
                    logger.log(Level.FINE, "Index already present: {0}", index);
                }
            }
            int current = readVersion(connection);
            if (current < VERSION) {
                migrate(connection, current, logger);
                writeVersion(connection, VERSION);
            }
            return VERSION;
        });
    }

    /**
     * Future migrations go here, guarded by the previous version so they run once.
     * The switch falls through intentionally (each step applies in order).
     */
    private static void migrate(Connection connection, int from, Logger logger) throws SQLException {
        if (from < 1) {
            logger.info("Initialising CraftRealm schema v" + VERSION);
        }
        // Example for the future:
        // if (from < 2) { Jdbc.update(connection, "ALTER TABLE cr_accounts ADD COLUMN locked TINYINT NOT NULL DEFAULT 0"); }
    }

    private static int readVersion(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT v FROM cr_meta WHERE k = 'schema_version'");
             var resultSet = statement.executeQuery()) {
            if (resultSet.next()) {
                try {
                    return Integer.parseInt(resultSet.getString(1));
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
            return 0;
        }
    }

    private static void writeVersion(Connection connection, int version) throws SQLException {
        int updated = Jdbc.update(connection,
                "UPDATE cr_meta SET v = ? WHERE k = 'schema_version'", String.valueOf(version));
        if (updated == 0) {
            Jdbc.update(connection, "INSERT INTO cr_meta (k, v) VALUES ('schema_version', ?)",
                    String.valueOf(version));
        }
    }
}
