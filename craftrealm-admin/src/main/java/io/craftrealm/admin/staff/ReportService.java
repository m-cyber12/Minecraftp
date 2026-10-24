package io.craftrealm.admin.staff;

import io.craftrealm.api.model.Report;
import io.craftrealm.api.model.ReportStatus;
import io.craftrealm.api.model.StaffAction;
import io.craftrealm.core.config.Settings;
import io.craftrealm.core.repo.StaffRepository;
import io.craftrealm.core.text.Messages;
import io.craftrealm.core.util.RateLimiter;
import io.craftrealm.core.util.Scheduler;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The report queue.
 *
 * <p>Players submit with {@code /report <player> <reason>}; staff review from the
 * Control Room or the web dashboard. Each player has a cooldown so the queue cannot
 * be flooded, and every resolution is audited.</p>
 */
public final class ReportService {

    private final StaffRepository repository;
    private final Messages messages;
    private final AuditService audit;
    private final RateLimiter cooldown;
    private final Consumer<Report> newReportHook;

    public ReportService(StaffRepository repository, Settings.AdminSettings settings, Messages messages,
                         AuditService audit, Consumer<Report> newReportHook) {
        this.repository = repository;
        this.messages = messages;
        this.audit = audit;
        this.cooldown = new RateLimiter(1.0 / Math.max(1, settings.reportCooldownSeconds()),
                Math.max(1, settings.reportCooldownSeconds()));
        this.newReportHook = newReportHook == null ? report -> {
        } : newReportHook;
    }

    /**
     * Files a report.
     *
     * @return the report id, or {@code -1} when the cooldown is still active
     */
    public CompletableFuture<Long> submit(Player reporter, Player target, String reason) {
        if (target != null && target.getUniqueId().equals(reporter.getUniqueId())) {
            messages.send(reporter, "report.self");
            return CompletableFuture.completedFuture(-1L);
        }
        if (!cooldown.tryAcquire(reporter.getUniqueId().toString())) {
            messages.send(reporter, "report.cooldown");
            return CompletableFuture.completedFuture(-1L);
        }
        String targetName = target == null ? "?" : target.getName();
        UUID targetId = target == null ? new UUID(0, 0) : target.getUniqueId();
        return repository.addReport(reporter.getUniqueId(), reporter.getName(), targetId, targetName,
                reason).thenApply(report -> {
            newReportHook.accept(report);
            Scheduler.sync(() -> messages.send(reporter, "report.submitted", "player", targetName));
            return report.id();
        });
    }

    public CompletableFuture<List<Report>> queue(ReportStatus status) {
        return repository.reports(status, 100);
    }

    public CompletableFuture<Long> openCount() {
        return repository.openReportCount();
    }

    public CompletableFuture<Boolean> resolve(UUID staff, long reportId, ReportStatus status, String resolution) {
        return repository.resolveReport(reportId, staff, status, resolution).thenApply(done -> {
            if (done) {
                audit.record(staff, StaffAction.REPORT_RESOLVE, String.valueOf(reportId),
                        status.name() + ": " + resolution);
            }
            return done;
        });
    }

    /** Adds an internal note to a report without changing its status. */
    public CompletableFuture<Boolean> annotate(UUID staff, long reportId, String note) {
        return repository.report(reportId).thenCompose(optional -> {
            if (optional.isEmpty()) {
                return CompletableFuture.completedFuture(false);
            }
            String resolution = (optional.get().resolution() == null ? "" : optional.get().resolution() + " | ")
                    + note;
            return repository.resolveReport(reportId, staff, optional.get().status(), resolution);
        });
    }
}
