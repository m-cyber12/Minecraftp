package io.craftrealm.events.gui;

import io.craftrealm.api.model.Quest;
import io.craftrealm.api.service.EconomyService;
import io.craftrealm.api.service.QuestService;
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
 * The quest board: two rows of daily quests, two rows of weekly quests.
 *
 * <p>A completed quest's icon turns into a lime dye — the universal "collect me"
 * affordance — and clicking it pays out immediately.</p>
 */
public final class QuestGui extends Gui {

    private final QuestService quests;
    private final EconomyService economy;
    private final Messages messages;
    private List<Quest> entries = List.of();

    public QuestGui(QuestService quests, EconomyService economy, Messages messages, GuiTheme theme,
                    Player player) {
        super(player, theme, 6, messages.get("quests.gui.title"));
        this.quests = quests;
        this.economy = economy;
        this.messages = messages;
    }

    @Override
    public void open() {
        quests.ensure(viewer.getUniqueId()).thenCompose(ignored -> quests.quests(viewer.getUniqueId()))
                .thenAccept(list -> Scheduler.sync(() -> {
                    entries = list;
                    super.open();
                }));
    }

    @Override
    public void render() {
        int dailySlot = 10;
        int weeklySlot = 28;
        for (Quest quest : entries) {
            boolean daily = quest.scope().name().equals("DAILY");
            int slot = daily ? dailySlot++ : weeklySlot++;
            if (slot >= (daily ? 17 : 35)) {
                continue;
            }
            set(slot, ItemBuilder.of(icon(quest))
                    .name(messages.raw("quests.gui.entry.name", "title", quest.title()))
                    .lore(lore(quest))
                    .glint(quest.isComplete())
                    .build(), click -> {
                if (!quest.isComplete()) {
                    messages.send(viewer, "quests.not-complete");
                    theme().play(viewer, GuiTheme.Click.DENY);
                    return;
                }
                quests.claim(viewer.getUniqueId(), quest.id()).thenAccept(claimed ->
                        Scheduler.sync(() -> {
                            messages.send(viewer, claimed ? "quests.claimed" : "quests.claim-failed",
                                    "title", quest.title());
                            theme().play(viewer, claimed ? GuiTheme.Click.SUCCESS : GuiTheme.Click.DENY);
                            open();
                        }));
            });
        }

        set(4, ItemBuilder.of(Material.WRITABLE_BOOK)
                .name(messages.raw("quests.gui.header"))
                .lore(messages.bundle().lines("quests.gui.header-lore"))
                .build());

        backButton(() -> viewer.closeInventory());
        closeButton();
        fillEmpty();
    }

    private List<Component> lore(Quest quest) {
        List<Component> lines = new ArrayList<>(messages.bundle().lines("quests.gui.entry.lore",
                "progress", String.format(java.util.Locale.ROOT, "%.0f/%.0f", quest.progress(), quest.target()),
                "bar", bar(quest.percent()),
                "reward", economy.format(quest.reward()),
                "expires", TimeUtil.formatDuration(Math.max(0, quest.expiresAt() - System.currentTimeMillis()))));
        return lines;
    }

    /** Blocky progress bar using the same blocks as the health page. */
    static String bar(double ratio) {
        int filled = (int) Math.round(Math.max(0, Math.min(1, ratio)) * 10);
        return "\u25A0".repeat(filled) + "\u25A1".repeat(10 - filled);
    }

    private static Material icon(Quest quest) {
        if (quest.isComplete()) {
            return Material.LIME_DYE;
        }
        return quest.scope().name().equals("DAILY") ? Material.PAPER : Material.MAP;
    }
}
