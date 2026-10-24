package io.craftrealm.events.command;

import io.craftrealm.api.service.EconomyService;
import io.craftrealm.api.service.QuestService;
import io.craftrealm.core.command.BaseCommand;
import io.craftrealm.core.text.Messages;
import io.craftrealm.core.ui.GuiTheme;
import io.craftrealm.events.gui.QuestGui;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/** {@code /quests} — opens the daily and weekly quest board. */
public final class QuestsCommand extends BaseCommand {

    private final QuestService quests;
    private final EconomyService economy;
    private final GuiTheme theme;

    public QuestsCommand(Messages messages, QuestService quests, EconomyService economy, GuiTheme theme) {
        super(messages);
        this.quests = quests;
        this.economy = economy;
        this.theme = theme;
    }

    @Override
    public String name() {
        return "quests";
    }

    @Override
    public List<String> aliases() {
        return List.of("quest");
    }

    @Override
    public String permission() {
        return "craftrealm.quests";
    }

    @Override
    public String description() {
        return "Open the quest board";
    }

    @Override
    public boolean playerOnly() {
        return true;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player != null) {
            new QuestGui(quests, economy, messages, theme, player).open();
        }
    }
}
