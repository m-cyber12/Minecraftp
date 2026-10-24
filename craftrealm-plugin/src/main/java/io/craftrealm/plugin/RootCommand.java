package io.craftrealm.plugin;

import io.craftrealm.core.command.BaseCommand;
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
            // The rebuild touches listeners, commands and the web panel, so it has to
            // happen on the main thread. Reading config.yml is a few kilobytes; the
            // expensive parts (database, market warm-up) are not repeated.
            case "reload" -> {
                try {
                    plugin.reloadEverything();
                    messages.send(sender, "admin.reloaded");
                } catch (RuntimeException exception) {
                    getLogger().log(java.util.logging.Level.SEVERE, "Reload failed", exception);
                    messages.send(sender, "admin.reload-failed",
                            "error", String.valueOf(exception.getMessage()));
                }
            }
            case "version" -> messages.send(sender, "admin.version",
                    "version", plugin.getPluginMeta().getVersion());
            case "panel" -> messages.send(sender, "admin.panel",
                    "state", plugin.webPanel() != null && plugin.webPanel().running()
                            ? "running" : "stopped",
                    "port", String.valueOf(plugin.settings().web().port()));
            case "save" -> {
                // push the hot price table to storage; the reply is written
                // immediately because the write itself is asynchronous.
                int count = plugin.marketService().trackedMaterials().size();
                plugin.marketService().flush();
                messages.send(sender, "admin.saved", "count", String.valueOf(count));
            }
            default -> messages.send(sender, "command.usage", "usage", usage());
        }
    }

    /** Plugin logger, so a failed reload leaves a stack trace in the console. */
    private java.util.logging.Logger getLogger() {
        return plugin.getLogger();
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        return args.length == 1 ? filter(List.of("reload", "version", "panel", "save"), args[0])
                : List.of();
    }
}
