package io.craftrealm.admin.command;

import io.craftrealm.admin.AdminServices;
import io.craftrealm.core.command.BaseCommand;
import io.craftrealm.core.text.Messages;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/** {@code /vanish} — hidden from players, still visible to staff with the see permission. */
public final class VanishCommand extends BaseCommand {

    private final AdminServices services;

    public VanishCommand(Messages messages, AdminServices services) {
        super(messages);
        this.services = services;
    }

    @Override
    public String name() {
        return "vanish";
    }

    @Override
    public List<String> aliases() {
        return List.of("v");
    }

    @Override
    public String permission() {
        return "craftrealm.staff.vanish";
    }

    @Override
    public String description() {
        return "Toggle vanish";
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
        services.staffTools.toggleVanish(player);
    }
}
