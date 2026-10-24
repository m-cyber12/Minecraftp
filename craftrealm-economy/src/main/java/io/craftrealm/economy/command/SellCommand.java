package io.craftrealm.economy.command;

import io.craftrealm.core.command.BaseCommand;
import io.craftrealm.core.text.Messages;
import io.craftrealm.economy.EconomyContext;
import io.craftrealm.economy.gui.SellGui;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/** {@code /sell} — drop items in a chest and convert them to Crowns. */
public final class SellCommand extends BaseCommand {

    private final EconomyContext context;

    public SellCommand(Messages messages, EconomyContext context) {
        super(messages);
        this.context = context;
    }

    @Override
    public String name() {
        return "sell";
    }

    @Override
    public List<String> aliases() {
        return List.of("sellall");
    }

    @Override
    public String permission() {
        return "craftrealm.economy.sell";
    }

    @Override
    public String usage() {
        return "/sell";
    }

    @Override
    public String description() {
        return "Sell items to the market";
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
        new SellGui(context, player).open();
    }
}
