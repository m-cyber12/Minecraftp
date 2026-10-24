package io.craftrealm.core.repo;

import io.craftrealm.core.db.Database;
import io.craftrealm.core.db.Jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Presence, moderation flags (freeze/mute), IP history and login streaks. */
public final class PlayerRepository {

    private final Database database;

    public PlayerRepository(Database database) {
        this.database = database;
    }

    /** Creates the row on first join and refreshes name/last-seen afterwards. */
    public CompletableFuture<PlayerRow> touch(UUID uuid, String name) {
        return database.supply(connection -> {
            long now = System.currentTimeMillis();
            int inserted = Jdbc.update(connection,
                    "INSERT INTO cr_players (uuid, name, first_seen, last_seen) "
                            + "SELECT ?, ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM cr_players WHERE uuid = ?)",
                    uuid, name, now, now, uuid);
            if (inserted == 0) {
                Jdbc.update(connection, "UPDATE cr_players SET name = ?, last_seen = ? WHERE uuid = ?",
                        name, now, uuid);
            }
            return Jdbc.one(connection, "SELECT * FROM cr_players WHERE uuid = ?", PlayerRepository::map, uuid)
                    .orElseGet(() -> new PlayerRow(uuid, name, now, now, 0L, false, 0L, null, 0, 0L));
        });
    }

    public CompletableFuture<Optional<PlayerRow>> find(UUID uuid) {
        return database.supply(connection -> Jdbc.one(connection,
                "SELECT * FROM cr_players WHERE uuid = ?", PlayerRepository::map, uuid));
    }

    public CompletableFuture<String> name(UUID uuid) {
        return database.supply(connection -> Jdbc.one(connection,
                "SELECT name FROM cr_players WHERE uuid = ?", row -> row.getString("name"), uuid)
                .orElse(null));
    }

    public CompletableFuture<List<PlayerRow>> all() {
        return database.supply(connection -> Jdbc.list(connection,
                "SELECT * FROM cr_players ORDER BY last_seen DESC", PlayerRepository::map));
    }

    public CompletableFuture<Void> addPlaytime(UUID uuid, long millis) {
        return database.run(connection -> Jdbc.update(connection,
                "UPDATE cr_players SET playtime = playtime + ? WHERE uuid = ?", millis, uuid));
    }

    public CompletableFuture<Boolean> setFrozen(UUID uuid, boolean frozen) {
        return database.supply(connection -> Jdbc.update(connection,
                "UPDATE cr_players SET frozen = ? WHERE uuid = ?", frozen, uuid) > 0);
    }

    public CompletableFuture<Boolean> isFrozen(UUID uuid) {
        return database.supply(connection -> Jdbc.scalar(connection,
                "SELECT frozen FROM cr_players WHERE uuid = ?", uuid) != 0);
    }

    public CompletableFuture<Boolean> setMutedUntil(UUID uuid, long until) {
        return database.supply(connection -> Jdbc.update(connection,
                "UPDATE cr_players SET muted_until = ? WHERE uuid = ?", until, uuid) > 0);
    }

    public CompletableFuture<Long> mutedUntil(UUID uuid) {
        return database.supply(connection -> Jdbc.scalar(connection,
                "SELECT muted_until FROM cr_players WHERE uuid = ?", uuid));
    }

    public CompletableFuture<Void> recordIp(UUID uuid, String ip) {
        return database.run(connection -> {
            long now = System.currentTimeMillis();
            Jdbc.update(connection, "UPDATE cr_players SET last_ip = ? WHERE uuid = ?", ip, uuid);
            long existing = Jdbc.scalar(connection,
                    "SELECT COUNT(*) FROM cr_ips WHERE uuid = ? AND ip = ?", uuid, ip);
            if (existing == 0) {
                Jdbc.update(connection, "INSERT INTO cr_ips (uuid, ip, seen_at) VALUES (?, ?, ?)",
                        uuid, ip, now);
            }
        });
    }

    public CompletableFuture<List<String>> ips(UUID uuid) {
        return database.supply(connection -> Jdbc.list(connection,
                "SELECT ip FROM cr_ips WHERE uuid = ? ORDER BY seen_at DESC LIMIT 20",
                row -> row.getString(1), uuid));
    }

    /** Accounts that ever used this IP — a cheap alt-detection signal. */
    public CompletableFuture<List<UUID>> accountsForIp(String ip) {
        return database.supply(connection -> Jdbc.list(connection,
                "SELECT uuid FROM cr_ips WHERE ip = ? ORDER BY seen_at DESC LIMIT 20",
                row -> UUID.fromString(row.getString(1)), ip));
    }

    /** Streak bookkeeping: returns the row after the update. */
    public CompletableFuture<PlayerRow> updateStreak(UUID uuid, int streak, long day) {
        return database.supply(connection -> {
            Jdbc.update(connection, "UPDATE cr_players SET streak = ?, last_streak_day = ? WHERE uuid = ?",
                    streak, day, uuid);
            return Jdbc.one(connection, "SELECT * FROM cr_players WHERE uuid = ?",
                    PlayerRepository::map, uuid).orElse(null);
        });
    }

    private static PlayerRow map(ResultSet row) throws SQLException {
        return new PlayerRow(
                UUID.fromString(row.getString("uuid")),
                row.getString("name"),
                row.getLong("first_seen"),
                row.getLong("last_seen"),
                row.getLong("playtime"),
                row.getInt("frozen") != 0,
                row.getLong("muted_until"),
                row.getString("last_ip"),
                row.getInt("streak"),
                row.getLong("last_streak_day"));
    }
}
