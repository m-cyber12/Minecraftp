package io.craftrealm.events.command;

import io.craftrealm.api.model.CosmeticType;
import io.craftrealm.api.service.CosmeticService;
import io.craftrealm.api.service.ShardService;
import io.craftrealm.core.command.BaseCommand;
import io.craftrealm.core.text.Messages;
import io.craftrealm.core.ui.GuiTheme;
import io.craftrealm.events.gui.CosmeticGui;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/** {@code /cosmetics [prefix|name_color|particle_trail]} — the Shard cosmetics shop. */
public final class CosmeticsCommand extends BaseCommand {

    private final CosmeticService cosmetics;
    private final ShardService shards;
    private final GuiTheme theme;

    public CosmeticsCommand(Messages messages, CosmeticService cosmetics, ShardService shards,
                            GuiTheme theme) {
        super(messages);
        this.cosmetics = cosmetics;
        this.shards = shards;
        this.theme = theme;
    }

    @Override
    public String name() {
        return "cosmetics";
    }

    @Override
    public List<String> aliases() {
        return List.of("cosmetic", "titles");
    }

    @Override
    public String permission() {
        return "craftrealm.cosmetics";
    }

    @Override
    public String usage() {
        return "/cosmetics [prefix|name_color|particle_trail]";
    }

    @Override
    public String description() {
        return "Buy and equip cosmetic titles and trails";
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
        CosmeticType filter = null;
        if (args.length > 0) {
            try {
                filter = CosmeticType.valueOf(args[0].toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                messages.send(sender, "command.usage", "usage", usage());
                return;
            }
        }
        new CosmeticGui(cosmetics, shards, messages, theme, player, filter).open();
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        return args.length == 1 ? filter(List.of("prefix", "name_color", "particle_trail"), args[0])
                : List.of();
    }
}
