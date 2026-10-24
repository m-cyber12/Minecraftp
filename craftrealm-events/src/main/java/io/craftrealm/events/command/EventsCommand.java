package io.craftrealm.events.command;

import io.craftrealm.api.service.EventService;
import io.craftrealm.core.command.BaseCommand;
import io.craftrealm.core.text.Messages;
import io.craftrealm.core.ui.GuiTheme;
import io.craftrealm.core.util.Scheduler;
import io.craftrealm.events.gui.EventsGui;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * {@code /events} — the board; {@code /events stop <id>} for staff.
 *
 * <p>Starting an event is deliberately only possible from the menu or the web
 * panel, so an accidental tab completion cannot launch a Blood Moon mid-build.</p>
 */
public final class EventsCommand extends BaseCommand {

    private final EventService events;
    private final GuiTheme theme;

    public EventsCommand(Messages messages, EventService events, GuiTheme theme) {
        super(messages);
        this.events = events;
        this.theme = theme;
    }

    @Override
    public String name() {
        return "events";
    }

    @Override
    public List<String> aliases() {
        return List.of("event");
    }

    @Override
    public String permission() {
        return "craftrealm.events";
    }

    @Override
    public String usage() {
        return "/events [stop <id>]";
    }

    @Override
    public String description() {
        return "Show the running world events";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[0].equalsIgnoreCase("stop")) {
            if (!sender.hasPermission("craftrealm.events.admin")) {
                messages.send(sender, "command.no-permission");
                return;
            }
            events.stop(args[1]).thenAccept(done -> Scheduler.sync(() ->
                    messages.send(sender, done ? "events.stopped" : "events.failed", "event", args[1])));
            return;
        }
        Player player = requirePlayer(sender);
        if (player != null) {
            new EventsGui(events, messages, theme, player).open();
        }
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return filter(List.of("stop"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("stop")) {
            return filter(events.registered(), args[1]);
        }
        return List.of();
    }
}
