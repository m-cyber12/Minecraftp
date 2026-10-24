package io.craftrealm.core.repo;

import io.craftrealm.api.model.AuditEntry;
import io.craftrealm.api.model.PlayerNote;
import io.craftrealm.api.model.Punishment;
import io.craftrealm.api.model.PunishmentType;
import io.craftrealm.api.model.Report;
import io.craftrealm.api.model.ReportStatus;
import io.craftrealm.api.model.Warning;
import io.craftrealm.core.db.Database;
import io.craftrealm.core.db.Jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Warnings, punishments, notes, reports and the audit trail. */
public final class StaffRepository {

    private final Database database;

    public StaffRepository(Database database) {
        this.database = database;
    }

    // --------------------------------------------------------------- warnings

    public CompletableFuture<Warning> addWarning(UUID target, UUID staff, String reason) {
        return database.supply(connection -> {
            long now = System.currentTimeMillis();
            long id = Jdbc.insert(connection,
                    "INSERT INTO cr_warnings (target, staff, reason, created_at, active) VALUES (?,?,?,?,1)",
                    target, staff, reason, now);
            return new Warning(id, target, staff, reason, now, true);
        });
    }

    public CompletableFuture<List<Warning>> warnings(UUID target, boolean activeOnly) {
        String sql = activeOnly
                ? "SELECT * FROM cr_warnings WHERE target = ? AND active = 1 ORDER BY id DESC"
                : "SELECT * FROM cr_warnings WHERE target = ? ORDER BY id DESC LIMIT 200";
        return database.supply(connection -> Jdbc.list(connection, sql, StaffRepository::mapWarning, target));
    }

    public CompletableFuture<Long> activeWarningCount(UUID target) {
        return database.supply(connection -> Jdbc.scalar(connection,
                "SELECT COUNT(*) FROM cr_warnings WHERE target = ? AND active = 1", target));
    }

    public CompletableFuture<Void> clearWarnings(UUID target) {
        return database.run(connection -> Jdbc.update(connection,
                "UPDATE cr_warnings SET active = 0 WHERE target = ?", target));
    }

    // ------------------------------------------------------------ punishments

    public CompletableFuture<Punishment> addPunishment(UUID target, PunishmentType type, UUID staff,
                                                      String reason, long expiresAt) {
        return database.supply(connection -> {
            long now = System.currentTimeMillis();
            long id = Jdbc.insert(connection,
                    "INSERT INTO cr_punishments (target, type, staff, reason, created_at, expires_at, active) "
                            + "VALUES (?,?,?,?,?,?,1)",
                    target, type, staff, reason, now, expiresAt);
            return new Punishment(id, target, type, staff, reason, now, expiresAt, true);
        });
    }

    public CompletableFuture<List<Punishment>> punishments(UUID target) {
        return database.supply(connection -> Jdbc.list(connection,
                "SELECT * FROM cr_punishments WHERE target = ? ORDER BY id DESC LIMIT 100",
                StaffRepository::mapPunishment, target));
    }

    /** Latest still-active punishment of a type, ignoring expired rows. */
    public CompletableFuture<Optional<Punishment>> activePunishment(UUID target, PunishmentType type, long now) {
        return database.supply(connection -> Jdbc.one(connection,
                "SELECT * FROM cr_punishments WHERE target = ? AND type = ? AND active = 1 "
                        + "AND (expires_at = 0 OR expires_at > ?) ORDER BY id DESC LIMIT 1",
                StaffRepository::mapPunishment, target, type, now));
    }

    public CompletableFuture<Boolean> liftPunishment(UUID target, PunishmentType type) {
        return database.supply(connection -> Jdbc.update(connection,
                "UPDATE cr_punishments SET active = 0 WHERE target = ? AND type = ? AND active = 1",
                target, type) > 0);
    }

    public CompletableFuture<List<Punishment>> expiredPunishments(long now) {
        return database.supply(connection -> Jdbc.list(connection,
                "SELECT * FROM cr_punishments WHERE active = 1 AND expires_at > 0 AND expires_at <= ?",
                StaffRepository::mapPunishment, now));
    }

    // ------------------------------------------------------------------ notes

    public CompletableFuture<PlayerNote> addNote(UUID target, UUID staff, String text) {
        return database.supply(connection -> {
            long now = System.currentTimeMillis();
            long id = Jdbc.insert(connection,
                    "INSERT INTO cr_player_notes (target, staff, text, created_at) VALUES (?,?,?,?)",
                    target, staff, text, now);
            return new PlayerNote(id, target, staff, text, now);
        });
    }

    public CompletableFuture<List<PlayerNote>> notes(UUID target) {
        return database.supply(connection -> Jdbc.list(connection,
                "SELECT * FROM cr_player_notes WHERE target = ? ORDER BY id DESC LIMIT 100",
                row -> new PlayerNote(row.getLong("id"), UUID.fromString(row.getString("target")),
                        UUID.fromString(row.getString("staff")), row.getString("text"),
                        row.getLong("created_at")),
                target));
    }

    // ---------------------------------------------------------------- reports

    public CompletableFuture<Report> addReport(UUID reporter, String reporterName, UUID target,
                                               String targetName, String reason) {
        return database.supply(connection -> {
            long now = System.currentTimeMillis();
            long id = Jdbc.insert(connection,
                    "INSERT INTO cr_reports (reporter, reporter_name, target, target_name, reason, status,"
                            + " created_at, handled_by, handled_at, resolution)"
                            + " VALUES (?,?,?,?,?,?,?,NULL,0,NULL)",
                    reporter, reporterName, target, targetName, reason, ReportStatus.OPEN, now);
            return new Report(id, reporter, reporterName, target, targetName, reason, ReportStatus.OPEN,
                    now, null, 0L, null);
        });
    }

    public CompletableFuture<List<Report>> reports(ReportStatus status, int limit) {
        if (status == null) {
            return database.supply(connection -> Jdbc.list(connection,
                    "SELECT * FROM cr_reports ORDER BY id DESC LIMIT ?",
                    StaffRepository::mapReport, Math.max(1, limit)));
        }
        return database.supply(connection -> Jdbc.list(connection,
                "SELECT * FROM cr_reports WHERE status = ? ORDER BY id DESC LIMIT ?",
                StaffRepository::mapReport, status, Math.max(1, limit)));
    }

    public CompletableFuture<Optional<Report>> report(long id) {
        return database.supply(connection -> Jdbc.one(connection,
                "SELECT * FROM cr_reports WHERE id = ?", StaffRepository::mapReport, id));
    }

    public CompletableFuture<Boolean> resolveReport(long id, UUID staff, ReportStatus status, String resolution) {
        return database.supply(connection -> Jdbc.update(connection,
                "UPDATE cr_reports SET status = ?, handled_by = ?, handled_at = ?, resolution = ? WHERE id = ?",
                status, staff, System.currentTimeMillis(), resolution, id) > 0);
    }

    public CompletableFuture<Long> openReportCount() {
        return database.supply(connection -> Jdbc.scalar(connection,
                "SELECT COUNT(*) FROM cr_reports WHERE status = ? OR status = ?",
                ReportStatus.OPEN, ReportStatus.IN_REVIEW));
    }

    // ------------------------------------------------------------------ audit

    public CompletableFuture<Void> audit(UUID staff, String staffName, String action, String target, String detail) {
        return database.run(connection -> Jdbc.update(connection,
                "INSERT INTO cr_audit (staff, staff_name, action, target, detail, created_at) "
                        + "VALUES (?,?,?,?,?,?)",
                staff, staffName, action, target, detail, System.currentTimeMillis()));
    }

    public CompletableFuture<List<AuditEntry>> audit(int limit) {
        return database.supply(connection -> Jdbc.list(connection,
                "SELECT * FROM cr_audit ORDER BY id DESC LIMIT ?", StaffRepository::mapAudit,
                Math.max(1, limit)));
    }

    public CompletableFuture<List<AuditEntry>> auditBy(UUID staff, int limit) {
        return database.supply(connection -> Jdbc.list(connection,
                "SELECT * FROM cr_audit WHERE staff = ? ORDER BY id DESC LIMIT ?",
                StaffRepository::mapAudit, staff, Math.max(1, limit)));
    }

    // ------------------------------------------------------------------ mappers

    private static Warning mapWarning(ResultSet row) throws SQLException {
        return new Warning(row.getLong("id"),
                UUID.fromString(row.getString("target")),
                UUID.fromString(row.getString("staff")),
                row.getString("reason"),
                row.getLong("created_at"),
                row.getInt("active") != 0);
    }

    private static Punishment mapPunishment(ResultSet row) throws SQLException {
        return new Punishment(row.getLong("id"),
                UUID.fromString(row.getString("target")),
                PunishmentType.valueOf(row.getString("type")),
                UUID.fromString(row.getString("staff")),
                row.getString("reason"),
                row.getLong("created_at"),
                row.getLong("expires_at"),
                row.getInt("active") != 0);
    }

    private static Report mapReport(ResultSet row) throws SQLException {
        return new Report(row.getLong("id"),
                UUID.fromString(row.getString("reporter")),
                row.getString("reporter_name"),
                UUID.fromString(row.getString("target")),
                row.getString("target_name"),
                row.getString("reason"),
                ReportStatus.valueOf(row.getString("status")),
                row.getLong("created_at"),
                Jdbc.uuid(row, "handled_by"),
                row.getLong("handled_at"),
                row.getString("resolution"));
    }

    private static AuditEntry mapAudit(ResultSet row) throws SQLException {
        return new AuditEntry(row.getLong("id"),
                Jdbc.uuid(row, "staff"),
                row.getString("staff_name"),
                row.getString("action"),
                row.getString("target"),
                row.getString("detail"),
                row.getLong("created_at"));
    }
}
