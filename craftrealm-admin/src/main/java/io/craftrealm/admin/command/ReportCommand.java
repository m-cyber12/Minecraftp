package io.craftrealm.admin.command;

import io.craftrealm.admin.AdminServices;
import io.craftrealm.core.command.BaseCommand;
import io.craftrealm.core.text.Messages;
import io.craftrealm.core.util.Scheduler;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * {@code /report <player> <reason>} — the player-facing half of the report queue.
 *
 * <p>Reports are never lost: they are written before the reporter sees any
 * confirmation, and staff see them in the Control Room and the web panel.</p>
 */
public final class ReportCommand extends BaseCommand {

    private final AdminServices services;

    public ReportCommand(Messages messages, AdminServices services) {
        super(messages);
        this.services = services;
    }

    @Override
    public String name() {
        return "report";
    }

    @Override
    public String description() {
        return "Report a player to staff";
    }

    @Override
    public String usage() {
        return "/report <player> <reason>";
    }

    @Override
    public boolean playerOnly() {
        return true;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        Player reporter = requirePlayer(sender);
        if (reporter == null) {
            return;
        }
        if (args.length < 2) {
            messages.send(sender, "command.usage", "usage", usage());
            return;
        }
        Player target = player(args[0]);
        if (target == null) {
            messages.send(sender, "admin.player.not-found", "player", args[0]);
            return;
        }
        String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        services.reports.submit(reporter, target, reason).thenAccept(id -> {
            if (id > 0) {
                Scheduler.sync(() -> services.context.messages.send(reporter, "report.submitted",
                        "player", target.getName(), "id", String.valueOf(id)));
            }
        });
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        return args.length == 1 ? onlinePlayers(args[0]) : List.of();
    }
}
