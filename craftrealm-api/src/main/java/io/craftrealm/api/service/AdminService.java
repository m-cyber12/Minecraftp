package io.craftrealm.api.service;

import io.craftrealm.api.model.AuditEntry;
import io.craftrealm.api.model.PlayerNote;
import io.craftrealm.api.model.PlayerView;
import io.craftrealm.api.model.Punishment;
import io.craftrealm.api.model.PunishmentType;
import io.craftrealm.api.model.Report;
import io.craftrealm.api.model.ReportStatus;
import io.craftrealm.api.model.Warning;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Staff-facing operations backing the Control Room and the web dashboard.
 * Every mutating call writes an audit entry.
 */
public interface AdminService {

    CompletableFuture<PlayerView> view(UUID uuid);

    CompletableFuture<List<PlayerView>> players();

    CompletableFuture<List<String>> ips(UUID uuid);

    CompletableFuture<List<Warning>> warnings(UUID uuid);

    /** Applies the configured escalation ladder and returns what was applied. */
    CompletableFuture<PunishmentType> warn(UUID staff, UUID target, String reason);

    CompletableFuture<Punishment> punish(UUID staff, UUID target, PunishmentType type,
                                         String reason, long durationMillis);

    CompletableFuture<Void> lift(UUID staff, UUID target, PunishmentType type, String reason);

    CompletableFuture<List<Punishment>> punishments(UUID target);

    CompletableFuture<List<PlayerNote>> notes(UUID target);

    CompletableFuture<Void> addNote(UUID staff, UUID target, String text);

    CompletableFuture<List<Report>> reports(ReportStatus status);

    CompletableFuture<Long> report(UUID reporter, UUID target, String reason);

    CompletableFuture<Boolean> resolveReport(UUID staff, long reportId, ReportStatus status,
                                             String resolution);

    CompletableFuture<List<AuditEntry>> audit(int limit);

    CompletableFuture<Void> audit(UUID staff, String action, String target, String detail);

    /** Freeze / unfreeze a player (blocks movement, damage and commands). */
    CompletableFuture<Boolean> setFrozen(UUID staff, UUID target, boolean frozen);

    CompletableFuture<Boolean> frozen(UUID uuid);
}
