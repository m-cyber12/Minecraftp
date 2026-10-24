package io.craftrealm.admin.punish;

import io.craftrealm.api.model.Punishment;
import io.craftrealm.api.model.PunishmentType;
import io.craftrealm.api.model.StaffAction;
import io.craftrealm.api.model.Warning;
import io.craftrealm.core.config.Settings;
import io.craftrealm.core.repo.PlayerRepository;
import io.craftrealm.core.repo.StaffRepository;
import io.craftrealm.core.text.Messages;
import io.craftrealm.core.util.Scheduler;
import io.craftrealm.core.util.TimeUtil;
import io.craftrealm.admin.staff.AuditService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Warnings, mutes, kicks, bans, tempbans and freezes.
 *
 * <p><b>Bans are database backed.</b> Rather than touching Bukkit's ban list (which
 * is profile shaped and awkward to query asynchronously), an active {@code BAN}
 * row is checked at pre-login and the connection is refused with the stored
 * reason and expiry. That survives restarts, works across a proxy network that
 * shares the MySQL database, and keeps the whole moderation history in one place.</p>
 *
 * <p>Warnings follow a configurable escalation ladder: the Nth warning triggers the
 * rule configured for N, so staff never have to remember the policy.</p>
 */
public final class PunishmentService {

    /** Sentinel meaning "muted until the end of time". */
    public static final long PERMANENT = Long.MAX_VALUE;

    private final StaffRepository staff;
    private final PlayerRepository players;
    private final Settings.AdminSettings adminSettings;
    private final Settings.StaffSettings staffSettings;
    private final Messages messages;
    private final AuditService audit;

    private final Map<UUID, Long> mutedUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> frozen = new ConcurrentHashMap<>();

    public PunishmentService(StaffRepository staff, PlayerRepository players, Settings.AdminSettings adminSettings,
                             Settings.StaffSettings staffSettings, Messages messages, AuditService audit) {
        this.staff = staff;
        this.players = players;
        this.adminSettings = adminSettings;
        this.staffSettings = staffSettings;
        this.messages = messages;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ caches

    /** Populates the moderation caches for a joining player. */
    public void load(UUID uuid) {
        players.find(uuid).thenAccept(row -> row.ifPresent(value -> {
            frozen.put(uuid, value.frozen());
            if (value.mutedUntil() > System.currentTimeMillis()) {
                mutedUntil.put(uuid, value.mutedUntil());
            }
        }));
    }

    public void unload(UUID uuid) {
        mutedUntil.remove(uuid);
        frozen.remove(uuid);
    }

    public boolean isFrozen(UUID uuid) {
        return frozen.getOrDefault(uuid, false);
    }

    public boolean isMuted(UUID uuid) {
        Long until = mutedUntil.get(uuid);
        if (until == null) {
            return false;
        }
        if (until > System.currentTimeMillis()) {
            return true;
        }
        mutedUntil.remove(uuid);
        return false;
    }

    /** Blocking check used from the asynchronous pre-login event. */
    public boolean isBanned(UUID uuid) {
        return staff.activePunishment(uuid, PunishmentType.BAN, System.currentTimeMillis())
                .thenCombine(staff.activePunishment(uuid, PunishmentType.TEMPBAN, System.currentTimeMillis()),
                        (permanent, temporary) -> permanent.isPresent() || temporary.isPresent())
                .exceptionally(throwable -> false)
                .join();
    }

    public CompletableFuture<List<Punishment>> history(UUID uuid) {
        return staff.punishments(uuid);
    }

    public CompletableFuture<List<Warning>> warnings(UUID uuid) {
        return staff.warnings(uuid, true);
    }

    // -------------------------------------------------------------- punishments

    /**
     * Records a warning and applies the matching escalation rung.
     *
     * @return the action that was escalated to, or {@code null} if this warning was
     *         below every configured rung
     */
    public CompletableFuture<PunishmentType> warn(UUID staffMember, UUID target, String reason) {
        return staff.addWarning(target, staffMember, reason).thenCompose(warning ->
                staff.activeWarningCount(target).thenCompose(count -> {
                    audit.record(staffMember, StaffAction.WARN, target.toString(),
                            "warning #" + count + ": " + reason);
                    notifyOnline(target, "punish.warned", "reason", reason, "count", String.valueOf(count));
                    PunishmentType escalation = escalationFor(count.intValue());
                    if (escalation == null) {
                        return CompletableFuture.completedFuture(null);
                    }
                    long duration = escalationDuration(count.intValue());
                    return punish(staffMember, target, escalation,
                            "Auto-escalation after " + count + " warnings: " + reason, duration)
                            .thenApply(ignored -> escalation);
                }));
    }

    /** Applies and records a punishment. */
    public CompletableFuture<Punishment> punish(UUID staffMember, UUID target, PunishmentType type,
                                                String reason, long durationMillis) {
        long expiresAt = durationMillis <= 0 ? 0L : System.currentTimeMillis() + durationMillis;
        return staff.addPunishment(target, type, staffMember, reason, expiresAt).thenApply(punishment -> {
            switch (type) {
                case MUTE -> {
                    long until = expiresAt == 0 ? PERMANENT : expiresAt;
                    mutedUntil.put(target, until);
                    players.setMutedUntil(target, until);
                }
                case FREEZE -> {
                    frozen.put(target, true);
                    players.setFrozen(target, true);
                }
                case BAN, TEMPBAN -> offlineSafe(target, "punish.banned-kick", reason,
                        expiresAt == 0 ? "never" : TimeUtil.formatDate(expiresAt));
                case KICK -> offlineSafe(target, "punish.kicked", reason, "");
                case WARN -> notifyOnline(target, "punish.warned", "reason", reason, "count", "1");
            }
            audit.record(staffMember, type.name(), target.toString(), reason);
            Scheduler.sync(() -> {
                Player online = Bukkit.getPlayer(target);
                if (online != null && (type == PunishmentType.BAN || type == PunishmentType.TEMPBAN
                        || type == PunishmentType.KICK)) {
                    online.kick(messages.prefixed("punish.kicked", "reason", reason));
                }
            });
            notifyOnline(target, "punish.applied", "type", type.name(), "reason", reason);
            return punishment;
        });
    }

    /** Lifts the newest active punishment of a type. */
    public CompletableFuture<Boolean> lift(UUID staffMember, UUID target, PunishmentType type, String reason) {
        return staff.liftPunishment(target, type).thenApply(lifted -> {
            if (!lifted) {
                return false;
            }
            switch (type) {
                case MUTE -> {
                    mutedUntil.remove(target);
                    players.setMutedUntil(target, 0L);
                }
                case FREEZE -> {
                    frozen.put(target, false);
                    players.setFrozen(target, false);
                }
                default -> {
                }
            }
            audit.record(staffMember, "UN" + type.name(), target.toString(), reason);
            notifyOnline(target, "punish.lifted", "type", type.name());
            return true;
        });
    }

    public CompletableFuture<Boolean> setFrozen(UUID staffMember, UUID target, boolean value) {
        frozen.put(target, value);
        return players.setFrozen(target, value).thenApply(done -> {
            audit.record(staffMember, value ? StaffAction.FREEZE : StaffAction.UNFREEZE,
                    target.toString(), "manual toggle");
            notifyOnline(target, value ? "punish.frozen" : "punish.unfrozen");
            return done;
        });
    }

    /**
     * Escalation lookup: exactly the rung configured for this warning count.
     * Returns the heaviest matching rule, so a config with both 2 and 3 works.
     */
    public PunishmentType escalationFor(int warningCount) {
        Settings.EscalationRule match = null;
        for (Settings.EscalationRule rule : staffSettings.escalation()) {
            if (rule.warnings() == warningCount) {
                match = rule;
            }
        }
        return match == null ? null : match.action();
    }

    public long escalationDuration(int warningCount) {
        for (Settings.EscalationRule rule : staffSettings.escalation()) {
            if (rule.warnings() == warningCount) {
                return rule.durationMillis();
            }
        }
        return 0L;
    }

    public String defaultBanReason() {
        return adminSettings.defaultBanReason();
    }

    // ----------------------------------------------------------------- helpers

    private void notifyOnline(UUID uuid, String key, Object... placeholders) {
        Scheduler.sync(() -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                messages.send(player, key, placeholders);
            }
        });
    }

    private void offlineSafe(UUID uuid, String key, String reason, String expiry) {
        Scheduler.sync(() -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                messages.send(player, key, "reason", reason, "expires", expiry);
            }
        });
    }
}
