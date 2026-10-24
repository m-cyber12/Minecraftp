package io.craftrealm.economy.command;

import io.craftrealm.core.command.BaseCommand;
import io.craftrealm.core.text.Messages;
import io.craftrealm.economy.EconomyContext;
import io.craftrealm.economy.gui.JobGui;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/** {@code /jobs} — the five careers and their progress. */
public final class JobCommand extends BaseCommand {

    private final EconomyContext context;

    public JobCommand(Messages messages, EconomyContext context) {
        super(messages);
        this.context = context;
    }

    @Override
    public String name() {
        return "jobs";
    }

    @Override
    public List<String> aliases() {
        return List.of("job", "careers");
    }

    @Override
    public String permission() {
        return "craftrealm.economy.jobs";
    }

    @Override
    public String usage() {
        return "/jobs";
    }

    @Override
    public String description() {
        return "Your jobs, levels and daily earnings";
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
        new JobGui(context, player).open();
    }
}
