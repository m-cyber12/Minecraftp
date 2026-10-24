package io.craftrealm.admin.command;

import io.craftrealm.admin.AdminServices;
import io.craftrealm.core.command.BaseCommand;
import io.craftrealm.core.text.Messages;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * {@code /staffchat <message>} posts once; {@code /staffchat} alone toggles the mode
 * so every chat line goes to staff.
 */
public final class StaffChatCommand extends BaseCommand {

    private final AdminServices services;

    public StaffChatCommand(Messages messages, AdminServices services) {
        super(messages);
        this.services = services;
    }

    @Override
    public String name() {
        return "staffchat";
    }

    @Override
    public List<String> aliases() {
        return List.of("sc");
    }

    @Override
    public String permission() {
        return "craftrealm.staff.chat";
    }

    @Override
    public String description() {
        return "Send a message to staff only";
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
            boolean enabled = services.staffTools.toggleStaffChat(player.getUniqueId());
            messages.send(player, enabled ? "staff.chat-on" : "staff.chat-off");
            return;
        }
        services.staffTools.broadcastStaffChat(player, String.join(" ", args));
    }
}
