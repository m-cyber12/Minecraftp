package io.craftrealm.events.command;

import io.craftrealm.api.model.ChestShop;
import io.craftrealm.api.service.EconomyService;
import io.craftrealm.core.command.BaseCommand;
import io.craftrealm.core.text.Messages;
import io.craftrealm.core.util.Scheduler;
import io.craftrealm.events.shops.ChestShopService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /chestshop list} and {@code /chestshop remove <id>}.
 *
 * <p>Creating a shop happens with a sign — two lines of text on a block is faster
 * than any menu, and it puts the price literally in front of the buyer.</p>
 */
public final class ChestShopCommand extends BaseCommand {

    private final ChestShopService shops;
    private final EconomyService economy;

    public ChestShopCommand(Messages messages, ChestShopService shops, EconomyService economy) {
        super(messages);
        this.shops = shops;
        this.economy = economy;
    }

    @Override
    public String name() {
        return "chestshop";
    }

    @Override
    public List<String> aliases() {
        return List.of("myshops");
    }

    @Override
    public String permission() {
        return "craftrealm.shop";
    }

    @Override
    public String usage() {
        return "/chestshop [list|remove <id>]";
    }

    @Override
    public String description() {
        return "Manage your chest shops";
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
        if (args.length > 0 && args[0].equalsIgnoreCase("remove")) {
            remove(player, args);
            return;
        }
        list(player);
    }

    private void remove(Player player, String[] args) {
        long id;
        try {
            id = Long.parseLong(args[1]);
        } catch (RuntimeException exception) {
            messages.send(player, "command.usage", "usage", usage());
            return;
        }
        shops.byOwner(player.getUniqueId()).thenCompose(list -> {
            for (ChestShop shop : list) {
                if (shop.id() == id) {
                    return shops.remove(player.getUniqueId(), shop);
                }
            }
            return CompletableFuture.completedFuture(null);
        }).thenAccept(leftover -> Scheduler.sync(() -> {
            if (leftover == null) {
                messages.send(player, "shops.not-found");
                return;
            }
            returnItems(player, leftover);
            messages.send(player, "shops.removed");
        }));
    }

    private void list(Player player) {
        shops.byOwner(player.getUniqueId()).thenAccept(list -> Scheduler.sync(() -> {
            messages.send(player, "shops.list-header", "count", String.valueOf(list.size()));
            for (ChestShop shop : list) {
                messages.send(player, "shops.list-entry",
                        "id", String.valueOf(shop.id()),
                        "world", shop.world(),
                        "x", String.valueOf(shop.x()),
                        "z", String.valueOf(shop.z()),
                        "price", economy.format(shop.price()),
                        "stock", String.valueOf(shop.stock()));
            }
        }));
    }

    private static void returnItems(Player player, ItemStack item) {
        var leftover = player.getInventory().addItem(item);
        leftover.values().forEach(drop -> player.getWorld().dropItemNaturally(player.getLocation(), drop));
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        return args.length == 1 ? filter(List.of("list", "remove"), args[0]) : List.of();
    }
}
