package io.craftrealm.economy.command;

import io.craftrealm.core.command.BaseCommand;
import io.craftrealm.core.text.Messages;
import io.craftrealm.core.util.Scheduler;
import io.craftrealm.economy.EconomyContext;
import org.bukkit.command.CommandSender;

import java.util.List;

/** {@code /baltop} — the ten richest accounts in the realm. */
public final class BaltopCommand extends BaseCommand {

    private final EconomyContext context;

    public BaltopCommand(Messages messages, EconomyContext context) {
        super(messages);
        this.context = context;
    }

    @Override
    public String name() {
        return "baltop";
    }

    @Override
    public List<String> aliases() {
        return List.of("balancetop", "moneytop");
    }

    @Override
    public String permission() {
        return "craftrealm.economy.baltop";
    }

    @Override
    public String usage() {
        return "/baltop";
    }

    @Override
    public String description() {
        return "Richest players";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        context.economy.top(10).thenAccept(entries -> Scheduler.sync(() -> {
            messages.send(sender, "economy.baltop.header");
            if (entries.isEmpty()) {
                messages.sendRaw(sender, "economy.baltop.empty");
                return;
            }
            int rank = 1;
            for (var entry : entries) {
                String name = entry.name() == null ? entry.uuid().toString().substring(0, 8) : entry.name();
                messages.sendRaw(sender, "economy.baltop.entry",
                        "rank", String.valueOf(rank++),
                        "player", name,
                        "amount", context.money(entry.balance()));
            }
        }));
    }
}
