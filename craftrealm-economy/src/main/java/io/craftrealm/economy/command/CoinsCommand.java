package io.craftrealm.economy.command;

import io.craftrealm.core.command.BaseCommand;
import io.craftrealm.core.text.Messages;
import io.craftrealm.economy.EconomyContext;
import io.craftrealm.economy.items.CurrencyService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * {@code /coins} — take Crowns out of the bank as a physical coin or a signed
 * note, or cash in what you are holding.
 */
public final class CoinsCommand extends BaseCommand {

    private final EconomyContext context;
    private final CurrencyService currency;

    public CoinsCommand(Messages messages, EconomyContext context, CurrencyService currency) {
        super(messages);
        this.context = context;
        this.currency = currency;
    }

    @Override
    public String name() {
        return "coins";
    }

    @Override
    public List<String> aliases() {
        return List.of("cash", "note");
    }

    @Override
    public String permission() {
        return "craftrealm.economy.coins";
    }

    @Override
    public String usage() {
        return "/coins <coin|note|redeem> [amount]";
    }

    @Override
    public String description() {
        return "Turn Crowns into physical coins and banknotes";
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
            messages.send(sender, "command.usage", "usage", usage());
            return;
        }
        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "coin" -> {
                Double amount = args.length > 1 ? amount(sender, args[1]) : null;
                if (amount != null) {
                    currency.mintCoin(player, amount);
                }
            }
            case "note" -> {
                Double amount = args.length > 1 ? amount(sender, args[1]) : null;
                if (amount != null) {
                    currency.mintNote(player, amount);
                }
            }
            case "redeem" -> currency.redeemHand(player);
            default -> messages.send(sender, "command.usage", "usage", usage());
        }
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return filter(List.of("coin", "note", "redeem"), args[0]);
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("redeem")) {
            return filter(List.of("100", "1000", "10000"), args[1]);
        }
        return List.of();
    }
}
