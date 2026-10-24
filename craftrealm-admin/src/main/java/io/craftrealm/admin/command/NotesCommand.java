package io.craftrealm.admin.command;

import io.craftrealm.admin.AdminServices;
import io.craftrealm.core.command.BaseCommand;
import io.craftrealm.core.text.Messages;
import io.craftrealm.core.util.Scheduler;
import io.craftrealm.core.util.TimeUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * {@code /notes <player>} lists staff notes; {@code /notes <player> <text…>} adds one.
 *
 * <p>Notes are the institutional memory of a server: "refunded 5k after the shop
 * bug" is worth more to the next moderator than any log line.</p>
 */
public final class NotesCommand extends BaseCommand {

    private final AdminServices services;

    public NotesCommand(Messages messages, AdminServices services) {
        super(messages);
        this.services = services;
    }

    @Override
    public String name() {
        return "notes";
    }

    @Override
    public String permission() {
        return "craftrealm.staff.notes";
    }

    @Override
    public String usage() {
        return "/notes <player> [text]";
    }

    @Override
    public String description() {
        return "Read or write staff notes about a player";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length < 1) {
            messages.send(sender, "command.usage", "usage", usage());
            return;
        }
        UUID target = uuidOf(args[0]);
        if (target == null) {
            messages.send(sender, "admin.player.not-found", "player", args[0]);
            return;
        }

        if (args.length > 1) {
            String text = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
            UUID staff = sender instanceof Player player ? player.getUniqueId() : null;
            services.staffRepository.addNote(target, staff, text).thenAccept(note ->
                    Scheduler.sync(() -> messages.send(sender, "admin.notes.added", "player", args[0])));
            return;
        }

        services.staffRepository.notes(target).thenAccept(notes -> Scheduler.sync(() -> {
            messages.send(sender, "admin.notes.header", "player", args[0],
                    "count", String.valueOf(notes.size()));
            notes.stream().limit(15).forEach(note -> messages.send(sender, "admin.notes.entry",
                    "text", note.text(),
                    "when", TimeUtil.relative(note.createdAt())));
        }));
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        return args.length == 1 ? onlinePlayers(args[0]) : List.of();
    }
}
