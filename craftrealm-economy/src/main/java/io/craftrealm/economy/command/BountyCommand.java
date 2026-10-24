package io.craftrealm.economy.command;

import io.craftrealm.core.command.BaseCommand;
import io.craftrealm.core.text.Messages;
import io.craftrealm.core.util.Scheduler;
import io.craftrealm.economy.EconomyContext;
import io.craftrealm.economy.gui.BountyGui;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/** {@code /bounty [place <player> <amount>]} — the bounty board. */
public final class BountyCommand extends BaseCommand {

    private final EconomyContext context;

    public BountyCommand(Messages messages, EconomyContext context) {
        super(messages);
        this.context = context;
    }

    @Override
    public String name() {
        return "bounty";
    }

    @Override
    public List<String> aliases() {
        return List.of("bounties");
    }

    @Override
    public String permission() {
        return "craftrealm.economy.bounty";
    }

    @Override
    public String usage() {
        return "/bounty [place <player> <amount>]";
    }

    @Override
    public String description() {
        return "Place or view bounties";
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
        if (args.length >= 3 && args[0].equalsIgnoreCase("place")) {
            Double amount = amount(sender, args[2]);
            if (amount == null) {
                return;
            }
            var target = player(args[1]);
            if (target == null) {
                messages.send(sender, "command.unknown-player", "player", args[1]);
                return;
            }
            context.bounties().place(player.getUniqueId(), player.getName(), target.getUniqueId(),
                    target.getName(), amount, (success, message) -> Scheduler.sync(() ->
                            messages.send(sender, message, "player", target.getName(),
                                    "amount", context.money(amount))));
            return;
        }
        new BountyGui(context, player).open();
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return filter(List.of("place"), args[0]);
        }
        if (args.length == 2) {
            return onlinePlayers(args[1]);
        }
        return List.of();
    }
}
