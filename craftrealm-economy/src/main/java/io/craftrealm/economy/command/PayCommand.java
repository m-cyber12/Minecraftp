package io.craftrealm.economy.command;

import io.craftrealm.api.model.TransactionType;
import io.craftrealm.core.command.BaseCommand;
import io.craftrealm.core.text.Messages;
import io.craftrealm.core.util.Scheduler;
import io.craftrealm.economy.EconomyContext;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/** {@code /pay <player> <amount>} — transfers Crowns, online or offline. */
public final class PayCommand extends BaseCommand {

    private final EconomyContext context;

    public PayCommand(Messages messages, EconomyContext context) {
        super(messages);
        this.context = context;
    }

    @Override
    public String name() {
        return "pay";
    }

    @Override
    public String permission() {
        return "craftrealm.economy.pay";
    }

    @Override
    public String usage() {
        return "/pay <player> <amount>";
    }

    @Override
    public String description() {
        return "Send Crowns to another player";
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
        if (args.length < 2) {
            messages.send(sender, "command.usage", "usage", usage());
            return;
        }
        Double amount = amount(sender, args[1]);
        if (amount == null) {
            return;
        }
        if (amount < context.settings.economy().minTransfer()) {
            messages.send(sender, "economy.pay.too-small", "min", context.money(context.settings.economy().minTransfer()));
            return;
        }
        UUID target = uuidOf(args[0]);
        if (target == null) {
            messages.send(sender, "command.unknown-player", "player", args[0]);
            return;
        }
        if (target.equals(player.getUniqueId())) {
            messages.send(sender, "economy.pay.self");
            return;
        }
        String targetName = args[0];

        context.economy.transfer(player.getUniqueId(), target, amount, TransactionType.PAY,
                "pay to " + targetName).thenAccept(result -> Scheduler.sync(() -> {
            if (!result.success()) {
                messages.send(sender, result.messageKey(), "amount", context.money(amount));
                theme().play(player, io.craftrealm.core.ui.GuiTheme.Click.DENY);
                return;
            }
            messages.send(sender, "economy.pay.sent", "amount", context.money(amount), "player", targetName,
                    "balance", context.money(result.balanceAfter()));
            Player receiver = Bukkit.getPlayer(target);
            if (receiver != null) {
                messages.send(receiver, "economy.pay.received", "amount", context.money(amount),
                        "player", player.getName(),
                        "balance", context.money(context.economy.cachedBalance(receiver.getUniqueId())));
                theme().play(receiver, io.craftrealm.core.ui.GuiTheme.Click.PURCHASE);
            }
            theme().play(player, io.craftrealm.core.ui.GuiTheme.Click.SUCCESS);
        }));
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return onlinePlayers(args[0]);
        }
        if (args.length == 2) {
            return filter(List.of("10", "100", "1000", "10000"), args[1]);
        }
        return List.of();
    }

    private io.craftrealm.core.ui.GuiTheme theme() {
        return context.theme;
    }
}
