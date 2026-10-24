package io.craftrealm.admin.command;

import io.craftrealm.admin.AdminServices;
import io.craftrealm.admin.gui.LogsGui;
import io.craftrealm.core.command.BaseCommand;
import io.craftrealm.core.text.Messages;
import io.craftrealm.core.util.Scheduler;
import io.craftrealm.core.util.TimeUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/** {@code /audit [limit]} — chat view of the audit trail, or the paged menu. */
public final class AuditCommand extends BaseCommand {

    private final AdminServices services;

    public AuditCommand(Messages messages, AdminServices services) {
        super(messages);
        this.services = services;
    }

    @Override
    public String name() {
        return "audit";
    }

    @Override
    public String permission() {
        return "craftrealm.staff.audit";
    }

    @Override
    public String usage() {
        return "/audit [limit]";
    }

    @Override
    public String description() {
        return "Read the staff audit trail";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length == 0 && sender instanceof Player player) {
            new LogsGui(services, player, 0).open();
            return;
        }
        int limit = 20;
        if (args.length > 0) {
            try {
                limit = Math.max(1, Math.min(200, Integer.parseInt(args[0])));
            } catch (NumberFormatException exception) {
                messages.send(sender, "command.invalid-number", "input", args[0]);
                return;
            }
        }
        int finalLimit = limit;
        services.audit.recent(finalLimit).thenAccept(entries -> Scheduler.sync(() -> {
            messages.send(sender, "admin.logs.header", "count", String.valueOf(entries.size()));
            entries.forEach(entry -> messages.send(sender, "admin.logs.chat-entry",
                    "when", TimeUtil.relative(entry.createdAt()),
                    "staff", entry.staffName(),
                    "action", entry.action(),
                    "target", entry.target() == null ? "-" : entry.target(),
                    "detail", entry.detail() == null ? "-" : entry.detail()));
        }));
    }
}
