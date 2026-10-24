package io.craftrealm.admin.command;

import io.craftrealm.admin.AdminServices;
import io.craftrealm.api.model.PunishmentType;
import io.craftrealm.core.command.BaseCommand;
import io.craftrealm.core.text.Messages;
import io.craftrealm.core.util.Scheduler;
import io.craftrealm.core.util.TimeUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * The short forms staff actually type: {@code /warn}, {@code /mute}, {@code /kick},
 * {@code /ban}.
 *
 * <p>They all share one implementation so the audit trail, the escalation logic and
 * the messages can never drift apart between labels.</p>
 */
public final class ShortcutPunishCommand extends BaseCommand {

    private final AdminServices services;
    private final String label;
    private final PunishmentType type;
    private final boolean takesDuration;

    public ShortcutPunishCommand(Messages messages, AdminServices services, String label,
                                 PunishmentType type, boolean takesDuration) {
        super(messages);
        this.services = services;
        this.label = label;
        this.type = type;
        this.takesDuration = takesDuration;
    }

    @Override
    public String name() {
        return label;
    }

    @Override
    public String permission() {
        return "craftrealm.staff." + (type == PunishmentType.WARN ? "warn" : "punish");
    }

    @Override
    public String usage() {
        return takesDuration
                ? "/" + label + " <player> [duration] <reason>"
                : "/" + label + " <player> <reason>";
    }

    @Override
    public String description() {
        return "Apply a " + label + " to a player";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        int minimum = takesDuration ? 2 : 2;
        if (args.length < minimum) {
            messages.send(sender, "command.usage", "usage", usage());
            return;
        }
        String targetName = args[0];
        UUID target = uuidOf(targetName);
        if (target == null) {
            messages.send(sender, "admin.player.not-found", "player", targetName);
            return;
        }

        long duration = 0L;
        int reasonStart = 1;
        if (takesDuration) {
            long parsed = TimeUtil.parseDuration(args[1]);
            if (parsed > 0) {
                duration = parsed;
                reasonStart = 2;
            }
        }
        if (reasonStart >= args.length) {
            messages.send(sender, "command.usage", "usage", usage());
            return;
        }
        String reason = String.join(" ", java.util.Arrays.copyOfRange(args, reasonStart, args.length));
        UUID staff = sender instanceof Player player ? player.getUniqueId() : null;
        long punishmentDuration = duration;

        if (type == PunishmentType.WARN) {
            services.punishments.warn(staff, target, reason).thenAccept(escalated ->
                    Scheduler.sync(() -> messages.send(sender, "punish.warned",
                            "player", targetName,
                            "action", escalated == null ? "none" : escalated.name())));
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
        if (args.length == 2 && takesDuration) {
            return filter(List.of("10m", "1h", "1d", "7d"), args[1]);
        }
        return List.of();
    }
}
