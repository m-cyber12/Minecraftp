package io.craftrealm.core.command;

import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * One sub-command. Registered with a {@link CommandRouter} under its own label
 * ({@code /bal}) and/or as {@code /craftrealm <name>}.
 */
public interface SubCommand {

    /** Primary label, without a slash. */
    String name();

    /** Extra labels that invoke the same command. */
    default List<String> aliases() {
        return List.of();
    }

    /** Permission node, or {@code null} when everyone may use it. */
    default String permission() {
        return null;
    }

    /** Usage line appended to {@code /craftrealm help}. */
    default String usage() {
        return "/" + name();
    }

    /** Short description for the help page. */
    default String description() {
        return "";
    }

    default boolean playerOnly() {
        return false;
    }

    void execute(CommandSender sender, String[] args);

    default List<String> complete(CommandSender sender, String[] args) {
        return List.of();
    }
}
