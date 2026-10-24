package io.craftrealm.plugin;

import io.craftrealm.core.command.BaseCommand;
import io.craftrealm.core.util.Scheduler;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * {@code /craftrealm <reload|version|panel|save>} — the plugin's own maintenance
 * commands.
 *
 * <p>Reload deliberately does not rebuild the database or the services: it re-reads
 * {@code config.yml} and the language files, then fires
 * {@code CraftRealmReloadEvent}. Changing storage settings requires a restart, which
 * is stated in the README rather than silently ignored.</p>
 */
public final class RootCommand extends BaseCommand {

    private final CraftRealmPlugin plugin;

    public RootCommand(CraftRealmPlugin plugin) {
        super(plugin.messages());
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "craftrealm";
    }

    @Override
    public List<String> aliases() {
        return List.of("cr");
    }

    @Override
    public String permission() {
        return "craftrealm.admin";
    }

    @Override
    public String usage() {
        return "/craftrealm <reload|version|panel|save>";
    }

    @Override
    public String description() {
        return "CraftRealm maintenance";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            messages.send(sender, "command.usage", "usage", usage());
            return;
        }
        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "reload" -> Scheduler.async(() -> {
                try {
                    plugin.reloadEverything();
                    Scheduler.sync(() -> messages.send(sender, "admin.reloaded"));
                } catch (RuntimeException exception) {
                    Scheduler.sync(() -> messages.send(sender, "admin.reload-failed",
                            "error", String.valueOf(exception.getMessage())));
                }
            });
            case "version" -> messages.send(sender, "admin.version",
                    "version", plugin.getPluginMeta().getVersion());
            case "panel" -> messages.send(sender, "admin.panel",
                    "state", plugin.webPanel() != null && plugin.webPanel().running()
                            ? "running" : "stopped",
                    "port", String.valueOf(plugin.settings().web().port()));
            case "save" -> plugin.marketService().allQuotes().thenAccept(quotes -> Scheduler.sync(() ->
                    messages.send(sender, "admin.saved", "count", String.valueOf(quotes.size()))));
            default -> messages.send(sender, "command.usage", "usage", usage());
        }
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        return args.length == 1 ? filter(List.of("reload", "version", "panel", "save"), args[0])
                : List.of();
    }
}
