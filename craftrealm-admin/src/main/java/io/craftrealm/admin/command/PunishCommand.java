package io.craftrealm.admin.command;

import io.craftrealm.admin.AdminServices;
import io.craftrealm.api.model.PunishmentType;
import io.craftrealm.core.command.BaseCommand;
import io.craftrealm.core.text.Messages;
import io.craftrealm.core.util.Scheduler;
import io.craftrealm.core.util.TimeUtil;
import org.bukkit.entity.Player;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;

/**
 * {@code /punish <player> <warn|mute|kick|ban|tempban|freeze|unfreeze> [duration] <reason>}
 *
 * <p>One command for every moderation action keeps the permission surface small and
 * the audit trail uniform. Durations accept the usual shorthands ({@code 30m},
 * {@code 2d}, {@code 1w}) and a bare {@code ban} is permanent.</p>
 */
public final class PunishCommand extends BaseCommand {

    private final AdminServices services;

    public PunishCommand(Messages messages, AdminServices services) {
        super(messages);
        this.services = services;
    }

    @Override
    public String name() {
        return "punish";
    }

    @Override
    public String permission() {
        return "craftrealm.staff.punish";
    }

    @Override
    public String usage() {
        return "/punish <player> <action> [duration] <reason>";
    }

    @Override
    public String description() {
        return "Apply a warning, mute, kick or ban";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length < 3) {
            messages.send(sender, "command.usage", "usage", usage());
            return;
        }
        String targetName = args[0];
        java.util.UUID target = uuidOf(targetName);
        if (target == null) {
            messages.send(sender, "admin.player.not-found", "player", targetName);
            return;
        }

        PunishmentType type;
        try {
            type = PunishmentType.valueOf(args[1].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            messages.send(sender, "command.usage", "usage", usage());
            return;
        }

        long duration = 0L;
        int reasonStart = 2;
        if (type == PunishmentType.MUTE || type == PunishmentType.TEMPBAN) {
            long parsed = TimeUtil.parseDuration(args[2]);
            if (parsed > 0) {
                duration = parsed;
                reasonStart = 3;
            }
        }
        if (reasonStart >= args.length) {
            messages.send(sender, "command.usage", "usage", usage());
            return;
        }
        String reason = String.join(" ", java.util.Arrays.copyOfRange(args, reasonStart, args.length));
        java.util.UUID staff = sender instanceof Player player ? player.getUniqueId() : null;
        long punishmentDuration = duration;

        if (type == PunishmentType.WARN) {
            services.punishments.warn(staff, target, reason).thenAccept(escalated ->
                    Scheduler.sync(() -> messages.send(sender, "punish.warned",
                            "player", targetName,
                            "action", escalated == null ? "none" : escalated.name())));
            return;
        }
        if (type == PunishmentType.FREEZE) {
            services.punishments.setFrozen(staff, target, true);
            messages.send(sender, "admin.player.froze", "player", targetName);
            return;
        }
        services.punishments.punish(staff, target, type, reason, punishmentDuration).thenAccept(punishment ->
                Scheduler.sync(() -> messages.send(sender, "punish.applied",
                        "player", targetName,
                        "type", type.name(),
                        "duration", punishmentDuration <= 0 ? "permanent" : TimeUtil.formatDuration(punishmentDuration))));
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return onlinePlayers(args[0]);
        }
        if (args.length == 2) {
            return filter(List.of("WARN", "MUTE", "KICK", "BAN", "TEMPBAN", "FREEZE"), args[1]);
        }
        if (args.length == 3) {
            return filter(List.of("10m", "1h", "1d", "7d"), args[2]);
        }
        return List.of();
    }
}
