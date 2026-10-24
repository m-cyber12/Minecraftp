package io.craftrealm.admin;

import io.craftrealm.admin.punish.PunishmentService;
import io.craftrealm.admin.staff.AuditService;
import io.craftrealm.admin.staff.ReportService;
import io.craftrealm.api.model.AuditEntry;
import io.craftrealm.api.model.PlayerNote;
import io.craftrealm.api.model.PlayerView;
import io.craftrealm.api.model.Punishment;
import io.craftrealm.api.model.PunishmentType;
import io.craftrealm.api.model.Report;
import io.craftrealm.api.model.ReportStatus;
import io.craftrealm.api.model.Warning;
import io.craftrealm.api.service.AdminService;
import io.craftrealm.core.repo.PlayerRepository;
import io.craftrealm.core.repo.StaffRepository;
import io.craftrealm.core.util.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The {@link AdminService} implementation.
 *
 * <p>Views are snapshotted on the tick thread (Bukkit exposes ping, world and game
 * mode only there) and combined with asynchronous storage reads, so the Control
 * Room and the web dashboard never see a half-built player.</p>
 */
public final class AdminServiceImpl implements AdminService {

    private final AdminContext context;
    private final PlayerRepository players;
    private final StaffRepository staff;
    private final PunishmentService punishments;
    private final ReportService reports;
    private final AuditService audit;

    public AdminServiceImpl(AdminContext context, PlayerRepository players, StaffRepository staff,
                            PunishmentService punishments, ReportService reports, AuditService audit) {
        this.context = context;
        this.players = players;
        this.staff = staff;
        this.punishments = punishments;
        this.reports = reports;
        this.audit = audit;
    }

    // ------------------------------------------------------------------- views

    @Override
    public CompletableFuture<PlayerView> view(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        String name = online != null ? online.getName() : io.craftrealm.core.CraftRealmCore.nameOf(uuid);
        boolean isOnline = online != null;
        int ping = online != null ? online.getPing() : 0;
        String world = online != null ? online.getWorld().getName() : "-";
        String mode = online != null ? online.getGameMode().name() : "-";
        double balance = context.economy.cachedBalance(uuid);
        long shards = context.shards.cached(uuid);
        boolean frozen = punishments.isFrozen(uuid);
        boolean muted = punishments.isMuted(uuid);

        return players.find(uuid).thenApply(row -> new PlayerView(uuid, name, isOnline, balance, shards, ping,
                world, mode, row.map(io.craftrealm.core.repo.PlayerRow::lastSeen).orElse(0L),
                frozen || row.map(io.craftrealm.core.repo.PlayerRow::frozen).orElse(false),
                muted));
    }

    @Override
    public CompletableFuture<List<PlayerView>> players() {
        List<PlayerView> views = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            UUID uuid = online.getUniqueId();
            views.add(new PlayerView(uuid, online.getName(), true,
                    context.economy.cachedBalance(uuid), context.shards.cached(uuid),
                    online.getPing(), online.getWorld().getName(), online.getGameMode().name(),
                    System.currentTimeMillis(), punishments.isFrozen(uuid), punishments.isMuted(uuid)));
        }
        CompletableFuture<List<PlayerView>> future = new CompletableFuture<>();
        players.all().thenAccept(rows -> {
            for (var row : rows) {
                if (views.stream().anyMatch(view -> view.uuid().equals(row.uuid()))) {
                    continue;
                }
                views.add(new PlayerView(row.uuid(), row.name(), false,
                        context.economy.cachedBalance(row.uuid()), context.shards.cached(row.uuid()),
                        0, "-", "-", row.lastSeen(), row.frozen(), row.mutedUntil() > System.currentTimeMillis()));
            }
            future.complete(views);
        }).exceptionally(throwable -> {
            future.complete(views);
            return null;
        });
        return future;
    }

    @Override
    public CompletableFuture<List<String>> ips(UUID uuid) {
        return players.ips(uuid);
    }

    // -------------------------------------------------------------- moderation

    @Override
    public CompletableFuture<List<Warning>> warnings(UUID uuid) {
        return staff.warnings(uuid, true);
    }

    @Override
    public CompletableFuture<PunishmentType> warn(UUID staffMember, UUID target, String reason) {
        return punishments.warn(staffMember, target, reason);
    }

    @Override
    public CompletableFuture<Punishment> punish(UUID staffMember, UUID target, PunishmentType type,
                                                String reason, long durationMillis) {
        return punishments.punish(staffMember, target, type, reason, durationMillis);
    }

    @Override
    public CompletableFuture<Void> lift(UUID staffMember, UUID target, PunishmentType type, String reason) {
        return punishments.lift(staffMember, target, type, reason).thenApply(done -> null);
    }

    @Override
    public CompletableFuture<List<Punishment>> punishments(UUID target) {
        return punishments.history(target);
    }

    @Override
    public CompletableFuture<Boolean> setFrozen(UUID staffMember, UUID target, boolean frozen) {
        return punishments.setFrozen(staffMember, target, frozen);
    }

    @Override
    public CompletableFuture<Boolean> frozen(UUID uuid) {
        return CompletableFuture.completedFuture(punishments.isFrozen(uuid));
    }

    // ------------------------------------------------------------------- notes

    @Override
    public CompletableFuture<List<PlayerNote>> notes(UUID target) {
        return staff.notes(target);
    }

    @Override
    public CompletableFuture<Void> addNote(UUID staffMember, UUID target, String text) {
        return staff.addNote(target, staffMember, text).thenCompose(note ->
                audit.record(staffMember, "NOTE_ADD", target.toString(), text));
    }

    // ----------------------------------------------------------------- reports

    @Override
    public CompletableFuture<List<Report>> reports(ReportStatus status) {
        return reports.queue(status);
    }

    @Override
    public CompletableFuture<Long> report(UUID reporter, UUID target, String reason) {
        Player reporterPlayer = Bukkit.getPlayer(reporter);
        Player targetPlayer = Bukkit.getPlayer(target);
        if (reporterPlayer == null) {
            return staff.addReport(reporter, io.craftrealm.core.CraftRealmCore.nameOf(reporter), target,
                            io.craftrealm.core.CraftRealmCore.nameOf(target), reason)
                    .thenApply(Report::id);
        }
        return reports.submit(reporterPlayer, targetPlayer, reason);
    }

    @Override
    public CompletableFuture<Boolean> resolveReport(UUID staffMember, long reportId, ReportStatus status,
                                                    String resolution) {
        return reports.resolve(staffMember, reportId, status, resolution);
    }

    // ------------------------------------------------------------------- audit

    @Override
    public CompletableFuture<List<AuditEntry>> audit(int limit) {
        return audit.recent(limit);
    }

    @Override
    public CompletableFuture<Void> audit(UUID staffMember, String action, String target, String detail) {
        return audit.record(staffMember, action, target, detail);
    }

    /** Console/no-player variant used by the web dashboard. */
    public CompletableFuture<Void> auditSystem(String action, String target, String detail) {
        return audit.record((UUID) null, action, target, detail);
    }

    /** Must be called from the main thread; used by menus after a punishment. */
    public void kickNow(UUID target, String messageKey, Object... placeholders) {
        Scheduler.sync(() -> {
            Player player = Bukkit.getPlayer(target);
            if (player != null) {
                player.kick(context.messages.prefixed(messageKey, placeholders));
            }
        });
    }
}
