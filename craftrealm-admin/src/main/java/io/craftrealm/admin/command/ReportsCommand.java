package io.craftrealm.admin.command;

import io.craftrealm.admin.AdminServices;
import io.craftrealm.admin.gui.PunishmentsGui;
import io.craftrealm.core.command.BaseCommand;
import io.craftrealm.core.text.Messages;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/** {@code /reports} — the staff report queue as a menu. */
public final class ReportsCommand extends BaseCommand {

    private final AdminServices services;

    public ReportsCommand(Messages messages, AdminServices services) {
        super(messages);
        this.services = services;
    }

    @Override
    public String name() {
        return "reports";
    }

    @Override
    public String permission() {
        return "craftrealm.staff.reports";
    }

    @Override
    public String description() {
        return "Open the report queue";
    }

    @Override
    public boolean playerOnly() {
        return true;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        new PunishmentsGui(services, player).open();
    }
}
