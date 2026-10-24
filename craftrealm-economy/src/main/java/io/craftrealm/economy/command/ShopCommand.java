package io.craftrealm.economy.command;

import io.craftrealm.core.command.BaseCommand;
import io.craftrealm.core.text.Messages;
import io.craftrealm.economy.EconomyContext;
import io.craftrealm.economy.gui.ShopGui;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** {@code /shop [category]} — the server market. */
public final class ShopCommand extends BaseCommand {

    private final EconomyContext context;

    public ShopCommand(Messages messages, EconomyContext context) {
        super(messages);
        this.context = context;
    }

    @Override
    public String name() {
        return "shop";
    }

    @Override
    public String permission() {
        return "craftrealm.economy.shop";
    }

    @Override
    public String usage() {
        return "/shop [category]";
    }

    @Override
    public String description() {
        return "Buy blocks and items from the market";
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
        String category = args.length > 0 ? args[0] : null;
        if (category != null && !context.settings.catalogue().categories().containsKey(category)) {
            messages.send(sender, "shop.unknown-category", "category", category);
            return;
        }
        new ShopGui(context, player, category, 0).open();
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        return args.length == 1
                ? filter(new ArrayList<>(context.settings.catalogue().categories().keySet()), args[0])
                : List.of();
    }
}
