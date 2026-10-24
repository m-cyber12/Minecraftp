package io.craftrealm.core.repo;

import io.craftrealm.api.model.BalanceEntry;
import io.craftrealm.api.model.Note;
import io.craftrealm.api.model.Quest;
import io.craftrealm.api.model.QuestScope;
import io.craftrealm.api.model.QuestStatus;
import io.craftrealm.api.model.SeasonInfo;
import io.craftrealm.api.model.TransactionType;
import io.craftrealm.core.db.Database;
import io.craftrealm.core.db.Jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Quests, seasons, cosmetics and physical banknotes. */
public final class ProgressionRepository {

    /** One owned cosmetic row. */
    public record OwnedCosmetic(String cosmetic, boolean equipped, long unlockedAt) {
    }

    private final Database database;

    public ProgressionRepository(Database database) {
        this.database = database;
    }

    // ----------------------------------------------------------------- quests

    public CompletableFuture<Quest> createQuest(Quest quest) {
        return database.supply(connection -> {
            long id = Jdbc.insert(connection,
                    "INSERT INTO cr_quests (owner, template, kind, title, description, target, progress,"
                            + " reward_type, reward, scope, created_at, expires_at, status)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    quest.owner(), quest.templateId(), quest.kind(), quest.title(), quest.description(),
                    quest.target(), quest.progress(), quest.rewardType(), quest.reward(), quest.scope(),
                    quest.createdAt(), quest.expiresAt(), quest.status());
            return new Quest(id, quest.owner(), quest.templateId(), quest.kind(), quest.title(),
                    quest.description(), quest.target(), quest.progress(), quest.rewardType(),
                    quest.reward(), quest.scope(), quest.createdAt(), quest.expiresAt(), quest.status());
        });
    }

    public CompletableFuture<List<Quest>> quests(UUID owner) {
        return database.supply(connection -> Jdbc.list(connection,
                "SELECT * FROM cr_quests WHERE owner = ? ORDER BY id DESC LIMIT 64",
                ProgressionRepository::mapQuest, owner));
    }

    public CompletableFuture<List<Quest>> activeQuests(UUID owner) {
        return database.supply(connection -> Jdbc.list(connection,
                "SELECT * FROM cr_quests WHERE owner = ? AND status = ? ORDER BY id DESC",
                ProgressionRepository::mapQuest, owner, QuestStatus.ACTIVE));
    }

    /** Adds progress in SQL so two listeners cannot overwrite each other. */
    public CompletableFuture<Void> addProgress(long questId, double amount) {
        return database.run(connection -> Jdbc.update(connection,
                "UPDATE cr_quests SET progress = progress + ? WHERE id = ? AND status = ?",
                amount, questId, QuestStatus.ACTIVE));
    }

    public CompletableFuture<Boolean> setQuestStatus(long questId, QuestStatus from, QuestStatus to) {
        return database.supply(connection -> Jdbc.update(connection,
                "UPDATE cr_quests SET status = ? WHERE id = ? AND status = ?", to, questId, from) > 0);
    }

    public CompletableFuture<Integer> expireQuests(long now) {
        return database.supply(connection -> Jdbc.update(connection,
                "UPDATE cr_quests SET status = ? WHERE status = ? AND expires_at <= ?",
                QuestStatus.EXPIRED, QuestStatus.ACTIVE, now));
    }

    public CompletableFuture<Optional<Quest>> quest(long id) {
        return database.supply(connection -> Jdbc.one(connection,
                "SELECT * FROM cr_quests WHERE id = ?", ProgressionRepository::mapQuest, id));
    }

    // ---------------------------------------------------------------- seasons

    public CompletableFuture<Optional<SeasonInfo>> latestSeason() {
        return database.supply(connection -> Jdbc.one(connection,
                "SELECT * FROM cr_seasons ORDER BY number DESC LIMIT 1", ProgressionRepository::mapSeason));
    }

    public CompletableFuture<SeasonInfo> createSeason(int number, long startedAt, long endsAt) {
        return database.supply(connection -> {
            Jdbc.update(connection,
                    "INSERT INTO cr_seasons (number, started_at, ends_at, winner, winner_name, winner_score) "
                            + "VALUES (?,?,?,NULL,NULL,0)", number, startedAt, endsAt);
            return new SeasonInfo(number, startedAt, endsAt, null, null, 0L);
        });
    }

    public CompletableFuture<Void> closeSeason(int number, UUID winner, String winnerName, long score) {
        return database.run(connection -> Jdbc.update(connection,
                "UPDATE cr_seasons SET winner = ?, winner_name = ?, winner_score = ? WHERE number = ?",
                winner, winnerName, score, number));
    }

    public CompletableFuture<List<SeasonInfo>> seasonHistory(int limit) {
        return database.supply(connection -> Jdbc.list(connection,
                "SELECT * FROM cr_seasons ORDER BY number DESC LIMIT ?",
                ProgressionRepository::mapSeason, Math.max(1, limit)));
    }

    public CompletableFuture<Void> addScore(int season, UUID uuid, String name, long delta) {
        return database.run(connection -> {
            // Insert the row once with score 0, then always increment — inserting the
            // delta directly as well would double-count the very first point.
            Jdbc.update(connection,
                    "INSERT INTO cr_season_scores (season, uuid, name, score) "
                            + "SELECT ?, ?, ?, 0 WHERE NOT EXISTS "
                            + "(SELECT 1 FROM cr_season_scores WHERE season = ? AND uuid = ?)",
                    season, uuid, name, season, uuid);
            Jdbc.update(connection,
                    "UPDATE cr_season_scores SET score = score + ?, name = ? WHERE season = ? AND uuid = ?",
                    delta, name, season, uuid);
        });
    }

    public CompletableFuture<List<BalanceEntry>> leaderboard(int season, int limit) {
        return database.supply(connection -> Jdbc.list(connection,
                "SELECT uuid, name, score FROM cr_season_scores WHERE season = ? ORDER BY score DESC LIMIT ?",
                row -> new BalanceEntry(UUID.fromString(row.getString("uuid")), row.getString("name"),
                        row.getDouble("score")),
                season, Math.max(1, limit)));
    }

    public CompletableFuture<Integer> rank(int season, UUID uuid) {
        return database.supply(connection -> {
            double score = Jdbc.scalarDouble(connection,
                    "SELECT score FROM cr_season_scores WHERE season = ? AND uuid = ?", season, uuid);
            if (score <= 0) {
                return 0;
            }
            long better = Jdbc.scalar(connection,
                    "SELECT COUNT(*) FROM cr_season_scores WHERE season = ? AND score > ?", season, score);
            return (int) better + 1;
        });
    }

    // -------------------------------------------------------------- cosmetics

    public CompletableFuture<List<OwnedCosmetic>> cosmetics(UUID owner) {
        return database.supply(connection -> Jdbc.list(connection,
                "SELECT cosmetic, equipped, unlocked_at FROM cr_cosmetics WHERE owner = ?",
                row -> new OwnedCosmetic(row.getString("cosmetic"), row.getInt("equipped") != 0,
                        row.getLong("unlocked_at")),
                owner));
    }

    public CompletableFuture<Boolean> grantCosmetic(UUID owner, String cosmetic) {
        return database.supply(connection -> {
            int inserted = Jdbc.update(connection,
                    "INSERT INTO cr_cosmetics (owner, cosmetic, equipped, unlocked_at) "
                            + "SELECT ?, ?, 0, ? WHERE NOT EXISTS "
                            + "(SELECT 1 FROM cr_cosmetics WHERE owner = ? AND cosmetic = ?)",
                    owner, cosmetic, System.currentTimeMillis(), owner, cosmetic);
            return inserted > 0;
        });
    }

    /** Equips one cosmetic and clears the previous one of the same family. */
    public CompletableFuture<Boolean> equip(UUID owner, String cosmetic, String familyPrefix) {
        return database.inTransaction(connection -> {
            Jdbc.update(connection, "UPDATE cr_cosmetics SET equipped = 0 WHERE owner = ? AND cosmetic LIKE ?",
                    owner, familyPrefix + ".%");
            return Jdbc.update(connection,
                    "UPDATE cr_cosmetics SET equipped = 1 WHERE owner = ? AND cosmetic = ?",
                    owner, cosmetic) > 0;
        });
    }

    public CompletableFuture<Void> unequipFamily(UUID owner, String familyPrefix) {
        return database.run(connection -> Jdbc.update(connection,
                "UPDATE cr_cosmetics SET equipped = 0 WHERE owner = ? AND cosmetic LIKE ?",
                owner, familyPrefix + ".%"));
    }

    // -------------------------------------------------------------- banknotes

    public CompletableFuture<Note> issueNote(Note note) {
        return database.supply(connection -> {
            Jdbc.update(connection,
                    "INSERT INTO cr_banknotes (id, issuer, amount, created_at, redeemed_by, redeemed_at) "
                            + "VALUES (?,?,?,?,NULL,0)",
                    note.id(), note.issuer(), note.amount(), note.createdAt());
            return note;
        });
    }

    public CompletableFuture<Optional<Note>> note(String id) {
        return database.supply(connection -> Jdbc.one(connection,
                "SELECT * FROM cr_banknotes WHERE id = ?",
                row -> new Note(row.getString("id"), UUID.fromString(row.getString("issuer")),
                        row.getDouble("amount"), row.getLong("created_at")),
                id));
    }

    /**
     * Marks a note redeemed. Returns {@code false} when it was already redeemed —
     * the caller must not credit money in that case.
     */
    public CompletableFuture<Boolean> redeemNote(String id, UUID player) {
        return database.supply(connection -> Jdbc.update(connection,
                "UPDATE cr_banknotes SET redeemed_by = ?, redeemed_at = ? "
                        + "WHERE id = ? AND redeemed_by IS NULL",
                player, System.currentTimeMillis(), id) > 0);
    }

    /** Rolls back a redemption whose credit failed, so the note can be retried. */
    public CompletableFuture<Void> unredeemNote(String id) {
        return database.run(connection -> Jdbc.update(connection,
                "UPDATE cr_banknotes SET redeemed_by = NULL, redeemed_at = 0 WHERE id = ?", id));
    }

    public CompletableFuture<Long> outstandingNotes() {
        return database.supply(connection -> Jdbc.scalar(connection,
                "SELECT COUNT(*) FROM cr_banknotes WHERE redeemed_by IS NULL"));
    }

    // ----------------------------------------------------------------- mappers

    private static Quest mapQuest(ResultSet row) throws SQLException {
        return new Quest(row.getLong("id"),
                UUID.fromString(row.getString("owner")),
                row.getString("template"),
                row.getString("kind"),
                row.getString("title"),
                row.getString("description"),
                row.getDouble("target"),
                row.getDouble("progress"),
                TransactionType.valueOf(row.getString("reward_type")),
                row.getDouble("reward"),
                QuestScope.valueOf(row.getString("scope")),
                row.getLong("created_at"),
                row.getLong("expires_at"),
                QuestStatus.valueOf(row.getString("status")));
    }

    private static SeasonInfo mapSeason(ResultSet row) throws SQLException {
        return new SeasonInfo(row.getInt("number"),
                row.getLong("started_at"),
                row.getLong("ends_at"),
                Jdbc.uuid(row, "winner"),
                row.getString("winner_name"),
                row.getLong("winner_score"));
    }
}
