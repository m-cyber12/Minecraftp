package io.craftrealm.events.command;

import io.craftrealm.api.model.Treasure;
import io.craftrealm.core.command.BaseCommand;
import io.craftrealm.core.text.Messages;
import io.craftrealm.core.util.Scheduler;
import io.craftrealm.events.treasure.TreasureService;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * {@code /treasure hint} for players, {@code /treasure hide <shards> <clue>} and
 * {@code /treasure list} for staff.
 */
public final class TreasureCommand extends BaseCommand {

    private final TreasureService treasures;

    public TreasureCommand(Messages messages, TreasureService treasures) {
        super(messages);
        this.treasures = treasures;
    }

    @Override
    public String name() {
        return "treasure";
    }

    @Override
    public List<String> aliases() {
        return List.of("hunt");
    }

    @Override
    public String permission() {
        return "craftrealm.treasure";
    }

    @Override
    public String usage() {
        return "/treasure <hint|hide <shards> <clue>|list>";
    }

    @Override
    public String description() {
        return "Treasure hunts";
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
            case "hint" -> hint(player);
            case "hide" -> hide(player, args);
            case "list" -> list(player);
            default -> messages.send(sender, "command.usage", "usage", usage());
        }
    }

    private void hint(Player player) {
        var nearest = treasures.nearest(player.getLocation());
        if (nearest.isEmpty()) {
            messages.send(player, "treasure.none");
            return;
        }
        Treasure treasure = nearest.get();
        int distance = (int) player.getLocation().distance(
                new Location(player.getWorld(), treasure.x(), treasure.y(), treasure.z()));
        messages.send(player, "treasure.hint",
                "clue", treasure.clue() == null ? "-" : treasure.clue(),
                "distance", String.valueOf(distance));
    }

    private void hide(Player player, String[] args) {
        if (!player.hasPermission("craftrealm.treasure.admin")) {
            messages.send(player, "command.no-permission");
            return;
        }
        int shards = treasures.settings().defaultShards();
        if (args.length > 1) {
            try {
                shards = Math.max(1, Integer.parseInt(args[1]));
            } catch (NumberFormatException exception) {
                messages.send(player, "command.invalid-number", "input", args[1]);
                return;
            }
        }
        String clue = args.length > 2
                ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length))
                : null;
        treasures.hide(player.getLocation(), clue, shards).thenAccept(treasure ->
                Scheduler.sync(() -> messages.send(player, "treasure.hidden",
                        "id", String.valueOf(treasure.id()),
                        "shards", String.valueOf(treasure.shards()))));
    }

    private void list(Player player) {
        treasures.active().thenAccept(list -> Scheduler.sync(() -> {
            messages.send(player, "treasure.list-header", "count", String.valueOf(list.size()));
            for (Treasure treasure : list) {
                messages.send(player, "treasure.list-entry",
                        "id", String.valueOf(treasure.id()),
                        "world", treasure.world(),
                        "x", String.valueOf((int) treasure.x()),
                        "z", String.valueOf((int) treasure.z()),
                        "shards", String.valueOf(treasure.shards()));
            }
        }));
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        return args.length == 1 ? filter(List.of("hint", "hide", "list"), args[0]) : List.of();
    }
}
