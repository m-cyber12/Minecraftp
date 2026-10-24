package io.craftrealm.events.gui;

import io.craftrealm.api.model.BalanceEntry;
import io.craftrealm.api.model.SeasonInfo;
import io.craftrealm.api.service.EconomyService;
import io.craftrealm.api.service.SeasonService;
import io.craftrealm.core.text.Messages;
import io.craftrealm.core.ui.Gui;
import io.craftrealm.core.ui.GuiTheme;
import io.craftrealm.core.ui.ItemBuilder;
import io.craftrealm.core.util.Scheduler;
import io.craftrealm.core.util.TimeUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * The season board: days remaining, the top ten and the player's own rank.
 *
 * <p>Medals are sunflowers, gold ingots and copper — the theme's metals rather than
 * generic trophy emojis.</p>
 */
public final class SeasonGui extends Gui {

    private final SeasonService seasons;
    private final EconomyService economy;
    private final Messages messages;

    private SeasonInfo season;
    private List<BalanceEntry> board = List.of();
    private int rank;

    public SeasonGui(SeasonService seasons, EconomyService economy, Messages messages, GuiTheme theme,
                     Player player) {
        super(player, theme, 6, messages.get("seasons.gui.title"));
        this.seasons = seasons;
        this.economy = economy;
        this.messages = messages;
    }

    @Override
    public void open() {
        var current = seasons.current();
        var leaderboard = seasons.leaderboard(10);
        var own = seasons.rank(viewer.getUniqueId());
        java.util.concurrent.CompletableFuture.allOf(current, leaderboard, own).thenRun(() ->
                Scheduler.sync(() -> {
                    season = current.join();
                    board = leaderboard.join();
                    rank = own.join();
                    super.open();
                }));
    }

    @Override
    public void render() {
        set(4, ItemBuilder.of(Material.SUNFLOWER)
                .name(messages.raw("seasons.gui.header.name", "number", String.valueOf(season.number())))
                .lore(messages.bundle().lines("seasons.gui.header.lore",
                        "days", String.valueOf(TimeUtil.daysUntil(season.endsAt())),
                        "left", TimeUtil.formatDuration(season.remainingMillis()),
                        "rank", String.valueOf(rank)))
                .build());

        int slot = 10;
        int position = 1;
        for (BalanceEntry entry : board) {
            if (slot >= 35) {
                break;
            }
            set(slot++, ItemBuilder.of(medal(position))
                    .name(messages.raw("seasons.gui.entry.name",
                            "rank", String.valueOf(position),
                            "player", entry.name()))
                    .lore(lore(entry, position))
                    .glint(entry.uuid().equals(viewer.getUniqueId()))
                    .build());
            position++;
        }

        if (board.isEmpty()) {
            set(22, ItemBuilder.of(Material.CLOCK)
                    .name(messages.raw("seasons.gui.empty"))
                    .build());
        }

        backButton(() -> viewer.closeInventory());
        closeButton();
        fillEmpty();
    }

    private List<Component> lore(BalanceEntry entry, int position) {
        List<Component> lines = new ArrayList<>(messages.bundle().lines("seasons.gui.entry.lore",
                "score", economy.format(entry.balance()),
                "title", String.valueOf(seasons.titleForRank(position))));
        return lines;
    }

    private static Material medal(int position) {
        return switch (position) {
            case 1 -> Material.GOLD_BLOCK;
            case 2 -> Material.IRON_BLOCK;
            case 3 -> Material.COPPER_BLOCK;
            default -> Material.GOLD_NUGGET;
        };
    }
}
