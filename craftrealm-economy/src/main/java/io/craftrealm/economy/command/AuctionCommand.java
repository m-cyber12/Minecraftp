package io.craftrealm.economy.command;

import io.craftrealm.core.command.BaseCommand;
import io.craftrealm.core.text.Messages;
import io.craftrealm.economy.EconomyContext;
import io.craftrealm.economy.gui.AuctionGui;
import io.craftrealm.economy.gui.MailboxGui;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/** {@code /auction [mail]} — the auction house and its mailbox. */
public final class AuctionCommand extends BaseCommand {

    private final EconomyContext context;

    public AuctionCommand(Messages messages, EconomyContext context) {
        super(messages);
        this.context = context;
    }

    @Override
    public String name() {
        return "auction";
    }

    @Override
    public List<String> aliases() {
        return List.of("ah", "auctions");
    }

    @Override
    public String permission() {
        return "craftrealm.economy.auction";
    }

    @Override
    public String usage() {
        return "/auction [mail]";
    }

    @Override
    public String description() {
        return "Browse, bid on and list auctions";
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
        if (args.length > 0 && args[0].equalsIgnoreCase("mail")) {
            new MailboxGui(context, player).open();
            return;
        }
        new AuctionGui(context, player, 0).open();
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        return args.length == 1 ? filter(List.of("mail"), args[0]) : List.of();
    }
}
