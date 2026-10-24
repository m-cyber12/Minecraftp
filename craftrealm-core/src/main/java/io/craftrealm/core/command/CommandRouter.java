package io.craftrealm.core.command;

import io.craftrealm.core.text.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maps Bukkit commands onto {@link SubCommand}s.
 *
 * <p>Two registration styles are supported and can be mixed:</p>
 * <ul>
 *   <li>{@link #register(SubCommand)} — reachable as {@code /craftrealm <name>}
 *       and under every alias it declares (each alias is a separate plugin.yml
 *       command whose executor is this router).</li>
 *   <li>{@link #bind(String, SubCommand)} — forces a specific label, used when the
 *       alias differs from the sub-command name (e.g. {@code /jobs}).</li>
 * </ul>
 */
public final class CommandRouter implements CommandExecutor, TabCompleter {

    private final Messages messages;
    private final Map<String, SubCommand> byLabel = new LinkedHashMap<>();
    private final Map<String, SubCommand> byName = new LinkedHashMap<>();
    private final List<SubCommand> registered = new ArrayList<>();

    public CommandRouter(Messages messages) {
        this.messages = messages;
    }

    public CommandRouter register(SubCommand command) {
        registered.add(command);
        byName.put(command.name().toLowerCase(Locale.ROOT), command);
        byLabel.put(command.name().toLowerCase(Locale.ROOT), command);
        for (String alias : command.aliases()) {
            byLabel.put(alias.toLowerCase(Locale.ROOT), command);
        }
        return this;
    }

    public CommandRouter bind(String label, SubCommand command) {
        byLabel.put(label.toLowerCase(Locale.ROOT), command);
        return this;
    }

    public SubCommand find(String label) {
        return byLabel.get(label == null ? "" : label.toLowerCase(Locale.ROOT));
    }

    public List<SubCommand> commands() {
        return List.copyOf(registered);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        SubCommand target = find(label);
        String[] effectiveArgs = args;

        // `/craftrealm <sub>` resolves through the sub-command name instead.
        if (target == null || label.equalsIgnoreCase("craftrealm") || label.equalsIgnoreCase("cr")) {
            if (args.length == 0) {
                sendHelp(sender);
                return true;
            }
            SubCommand named = byName.get(args[0].toLowerCase(Locale.ROOT));
            if (named == null) {
                messages.send(sender, "command.unknown", "input", args[0]);
                return true;
            }
            target = named;
            effectiveArgs = java.util.Arrays.copyOfRange(args, 1, args.length);
        }

        if (target.permission() != null && !sender.hasPermission(target.permission())) {
            messages.send(sender, "command.no-permission");
            return true;
        }
        if (target.playerOnly() && !(sender instanceof Player)) {
            messages.send(sender, "command.player-only");
            return true;
        }
        try {
            target.execute(sender, effectiveArgs);
        } catch (RuntimeException exception) {
            messages.send(sender, "command.error", "error", exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage());
            io.craftrealm.core.CraftRealmCore.logger()
                    .warning("Command /" + label + " failed: " + exception);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        SubCommand target = find(alias);
        if (target == null || alias.equalsIgnoreCase("craftrealm") || alias.equalsIgnoreCase("cr")) {
            if (args.length <= 1) {
                String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
                return registered.stream()
                        .filter(sub -> sub.permission() == null || sender.hasPermission(sub.permission()))
                        .map(SubCommand::name)
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .sorted()
                        .toList();
            }
            SubCommand named = byName.get(args[0].toLowerCase(Locale.ROOT));
            if (named == null) {
                return List.of();
            }
            return named.complete(sender, java.util.Arrays.copyOfRange(args, 1, args.length));
        }
        return target.complete(sender, args);
    }

    private void sendHelp(CommandSender sender) {
        messages.send(sender, "command.help-header");
        registered.stream()
                .filter(command -> command.permission() == null || sender.hasPermission(command.permission()))
                .sorted(Comparator.comparing(SubCommand::name))
                .forEach(command -> messages.sendRaw(sender, "command.help-entry",
                        "usage", command.usage(), "description", command.description()));
    }
}
