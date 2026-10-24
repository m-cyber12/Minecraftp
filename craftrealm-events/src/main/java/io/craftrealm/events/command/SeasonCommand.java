package io.craftrealm.events.command;

import io.craftrealm.api.service.EconomyService;
import io.craftrealm.api.service.SeasonService;
import io.craftrealm.core.command.BaseCommand;
import io.craftrealm.core.text.Messages;
import io.craftrealm.core.ui.GuiTheme;
import io.craftrealm.events.gui.SeasonGui;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/** {@code /season} — the running season's leaderboard. */
public final class SeasonCommand extends BaseCommand {

    private final SeasonService seasons;
    private final EconomyService economy;
    private final GuiTheme theme;

    public SeasonCommand(Messages messages, SeasonService seasons, EconomyService economy, GuiTheme theme) {
        super(messages);
        this.seasons = seasons;
        this.economy = economy;
        this.theme = theme;
    }

    @Override
    public String name() {
        return "season";
    }

    @Override
    public List<String> aliases() {
        return List.of("seasons", "leaderboard");
    }

    @Override
    public String permission() {
        return "craftrealm.seasons";
    }

    @Override
    public String description() {
        return "Open the season leaderboard";
    }

    @Override
    public boolean playerOnly() {
        return true;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player != null) {
            new SeasonGui(seasons, economy, messages, theme, player).open();
        }
    }
}
