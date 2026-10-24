package io.craftrealm.economy.command;

import io.craftrealm.core.command.BaseCommand;
import io.craftrealm.core.text.Messages;
import io.craftrealm.economy.EconomyContext;
import io.craftrealm.economy.gui.BankGui;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/** {@code /bank} — savings, interest and loans. */
public final class BankCommand extends BaseCommand {

    private final EconomyContext context;

    public BankCommand(Messages messages, EconomyContext context) {
        super(messages);
        this.context = context;
    }

    @Override
    public String name() {
        return "bank";
    }

    @Override
    public String permission() {
        return "craftrealm.economy.bank";
    }

    @Override
    public String usage() {
        return "/bank";
    }

    @Override
    public String description() {
        return "Open your savings account and loans";
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
        new BankGui(context, player).open();
    }
}
