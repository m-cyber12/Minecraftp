package io.craftrealm.admin.command;

import io.craftrealm.admin.AdminServices;
import io.craftrealm.api.model.PunishmentType;
import io.craftrealm.core.command.BaseCommand;
import io.craftrealm.core.text.Messages;
import io.craftrealm.core.util.Scheduler;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/** {@code /unban <player>} — lifts both permanent bans and tempbans. */
public final class UnbanCommand extends BaseCommand {

    private final AdminServices services;

    public UnbanCommand(Messages messages, AdminServices services) {
        super(messages);
        this.services = services;
    }

    @Override
    public String name() {
        return "unban";
    }

    @Override
    public List<String> aliases() {
        return List.of("pardon");
    }

    @Override
    public String permission() {
        return "craftrealm.staff.unban";
    }

    @Override
    public String usage() {
        return "/unban <player>";
    }

    @Override
    public String description() {
        return "Lift a ban";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length < 1) {
            messages.send(sender, "command.usage", "usage", usage());
            return;
        }
        UUID target = uuidOf(args[0]);
        if (target == null) {
            messages.send(sender, "admin.player.not-found", "player", args[0]);
            return;
        }
        UUID staff = sender instanceof Player player ? player.getUniqueId() : null;
        services.punishments.lift(staff, target, PunishmentType.BAN, "unban by command")
                .thenCombine(services.punishments.lift(staff, target, PunishmentType.TEMPBAN, "unban by command"),
                        Boolean::logicalOr)
                .thenAccept(lifted -> Scheduler.sync(() ->
                        messages.send(sender, lifted ? "punish.unbanned" : "punish.not-banned",
                                "player", args[0])));
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        return args.length == 1 ? onlinePlayers(args[0]) : List.of();
    }
}
