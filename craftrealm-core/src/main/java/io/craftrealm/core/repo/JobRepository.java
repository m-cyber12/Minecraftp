package io.craftrealm.core.repo;

import io.craftrealm.api.model.JobProgress;
import io.craftrealm.api.model.JobType;
import io.craftrealm.core.db.Database;
import io.craftrealm.core.db.Jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Job levels, XP and the per-day income cap. */
public final class JobRepository {

    /** Progress plus the daily cap bookkeeping that never leaves the database. */
    public record JobRow(JobProgress progress, double earnedToday, long earnDay) {
    }

    private final Database database;

    public JobRepository(Database database) {
        this.database = database;
    }

    public CompletableFuture<List<JobRow>> load(UUID uuid) {
        return database.supply(connection -> Jdbc.list(connection,
                "SELECT * FROM cr_jobs WHERE uuid = ?", JobRepository::map, uuid));
    }

    public CompletableFuture<Void> save(UUID uuid, JobProgress progress, double earnedToday, long earnDay) {
        return database.run(connection -> {
            String[] columns = {"uuid", "job", "level", "xp", "earned_today", "earn_day"};
            Jdbc.update(connection, database.dialect().upsert("cr_jobs", columns, new String[]{"uuid", "job"}),
                    uuid, progress.job().name(), progress.level(), progress.xp(), earnedToday, earnDay);
        });
    }

    public CompletableFuture<List<JobProgress>> top(JobType job, int limit) {
        return database.supply(connection -> Jdbc.list(connection,
                "SELECT * FROM cr_jobs WHERE job = ? ORDER BY level DESC, xp DESC LIMIT ?",
                row -> map(row).progress(), job.name(), Math.max(1, limit)));
    }

    public CompletableFuture<Long> totalXp(UUID uuid) {
        return database.supply(connection -> Jdbc.scalar(connection,
                "SELECT COALESCE(SUM(xp), 0) FROM cr_jobs WHERE uuid = ?", uuid));
    }

    private static JobRow map(ResultSet row) throws SQLException {
        JobProgress progress = new JobProgress(
                UUID.fromString(row.getString("uuid")),
                JobType.valueOf(row.getString("job")),
                row.getInt("level"),
                row.getLong("xp"));
        return new JobRow(progress, row.getDouble("earned_today"), row.getLong("earn_day"));
    }
}
