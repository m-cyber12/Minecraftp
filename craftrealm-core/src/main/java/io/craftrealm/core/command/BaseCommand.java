package io.craftrealm.core.command;

import io.craftrealm.core.text.Messages;
import io.craftrealm.core.util.Money;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Convenience base for CraftRealm commands: permission checks, amount parsing and
 * player-name tab completion, so each command stays focused on its own logic.
 */
public abstract class BaseCommand implements SubCommand {

    protected final Messages messages;

    protected BaseCommand(Messages messages) {
        this.messages = messages;
    }

    /** Returns the sender as a player, or reports the player-only error. */
    protected Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        messages.send(sender, "command.player-only");
        return null;
    }

    /** Parses a Crown amount, reporting a clean error instead of a stack trace. */
    protected Double amount(CommandSender sender, String raw) {
        try {
            double value = Money.parse(raw);
            if (!Money.isValidAmount(value)) {
                messages.send(sender, "command.invalid-amount", "input", raw);
                return null;
            }
            return Money.round(value);
        } catch (NumberFormatException exception) {
            messages.send(sender, "command.invalid-amount", "input", raw);
            return null;
        }
    }

    /** Online player names for tab completion, filtered by the typed prefix. */
    protected List<String> onlinePlayers(String prefix) {
        String lowered = prefix == null ? "" : prefix.toLowerCase(java.util.Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase(java.util.Locale.ROOT).startsWith(lowered))
                .sorted()
                .collect(Collectors.toList());
    }

    protected List<String> filter(List<String> options, String prefix) {
        String lowered = prefix == null ? "" : prefix.toLowerCase(java.util.Locale.ROOT);
        return options.stream()
                .filter(option -> option.toLowerCase(java.util.Locale.ROOT).startsWith(lowered))
                .toList();
    }

    /** Resolves an online player by name (case-insensitive). */
    protected Player player(String name) {
        Player exact = Bukkit.getPlayerExact(name);
        if (exact != null) {
            return exact;
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().equalsIgnoreCase(name)) {
                return online;
            }
        }
        return null;
    }

    protected UUID uuidOf(String name) {
        Player online = player(name);
        if (online != null) {
            return online.getUniqueId();
        }
        @SuppressWarnings("deprecation")
        org.bukkit.OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        return offline.hasPlayedBefore() || offline.isOnline() ? offline.getUniqueId() : null;
    }

    protected String nameOf(Player player) {
        return player == null ? "?" : player.getName();
    }
}
