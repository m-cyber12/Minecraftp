package io.craftrealm.admin.command;

import io.craftrealm.admin.AdminServices;
import io.craftrealm.admin.gui.ControlRoomGui;
import io.craftrealm.admin.gui.PlayerDetailGui;
import io.craftrealm.admin.gui.PlayersGui;
import io.craftrealm.admin.gui.PunishmentsGui;
import io.craftrealm.core.command.BaseCommand;
import io.craftrealm.core.text.Messages;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * {@code /admin} — opens the Control Room.
 *
 * <p>Keeping the panel behind one short command matters: staff should never need a
 * wiki to moderate. Sub-pages are reachable from the menu itself.</p>
 */
public final class AdminCommand extends BaseCommand {

    private final AdminServices services;

    public AdminCommand(Messages messages, AdminServices services) {
        super(messages);
        this.services = services;
    }

    @Override
    public String name() {
        return "admin";
    }

    @Override
    public List<String> aliases() {
        return List.of("controlroom", "staff");
    }

    @Override
    public String permission() {
        return "craftrealm.admin";
    }

    @Override
    public String usage() {
        return "/admin [player]";
    }

    @Override
    public String description() {
        return "Open the staff Control Room";
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
        if (args.length == 0) {
            new ControlRoomGui(services, player).open();
            return;
        }
        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "players" -> new PlayersGui(services, player, 0).open();
            case "reports" -> new PunishmentsGui(services, player).open();
            default -> {
                java.util.UUID target = uuidOf(args[0]);
                if (target == null) {
                    messages.send(sender, "admin.player.not-found", "player", args[0]);
                    return;
                }
                new PlayerDetailGui(services, player, target, args[0]).open();
            }
        }
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> options = new java.util.ArrayList<>(List.of("players", "reports"));
            options.addAll(onlinePlayers(args[0]));
            return filter(options, args[0]);
        }
        return List.of();
    }
}
