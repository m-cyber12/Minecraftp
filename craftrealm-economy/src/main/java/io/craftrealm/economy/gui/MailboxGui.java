package io.craftrealm.economy.gui;

import io.craftrealm.api.model.CollectedEntry;
import io.craftrealm.api.model.MailboxEntry;
import io.craftrealm.api.model.MailboxKind;
import io.craftrealm.core.ui.Gui;
import io.craftrealm.core.ui.GuiTheme;
import io.craftrealm.core.ui.ItemBuilder;
import io.craftrealm.core.util.Scheduler;
import io.craftrealm.core.util.TimeUtil;
import io.craftrealm.economy.EconomyContext;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Collect auction winnings and refunds.
 *
 * <p>Collection is atomic on the server: the entry is claimed with a conditional
 * update and money is credited under {@code collect:<id>}, so a double click, a
 * reconnect or a retried web call can never pay twice.</p>
 */
public final class MailboxGui extends Gui {

    private final EconomyContext context;
    private List<MailboxEntry> entries = List.of();

    public MailboxGui(EconomyContext context, Player player) {
        super(player, context.theme, 6, context.messages.get("auction.mailbox.title"));
        this.context = context;
    }

    @Override
    public void open() {
        reload(() -> super.open());
    }

    private void reload(Runnable afterwards) {
        context.auctions.mailbox(viewer.getUniqueId()).thenAccept(list -> Scheduler.sync(() -> {
            entries = list;
            if (afterwards != null) {
                afterwards.run();
            } else {
                refresh();
            }
        }));
    }

    @Override
    public void render() {
        for (int index = 0; index < Math.min(45, entries.size()); index++) {
            MailboxEntry entry = entries.get(index);
            boolean money = entry.kind() == MailboxKind.MONEY;
            ItemBuilder builder;
            if (money) {
                builder = ItemBuilder.of(Material.GOLD_INGOT)
                        .name(context.messages.raw("auction.mailbox.money.name",
                                "amount", context.money(entry.amount())));
            } else {
                ItemStack preview = safeItem(entry);
                builder = ItemBuilder.of(preview)
                        .name(context.messages.raw("auction.mailbox.item.name",
                                "item", ShopGui.pretty(preview.getType())));
            }
            builder.lore(context.messages.bundle().lines("auction.mailbox.entry.lore",
                    "note", entry.note() == null ? "" : entry.note(),
                    "time", TimeUtil.relative(entry.createdAt())));
            set(index, builder.build(), click -> collect(entry));
        }
        set(49, ItemBuilder.of(Material.CHEST)
                .name(context.messages.raw("auction.mailbox.info"))
                .build());
        set(45, ItemBuilder.of(GuiTheme.Icon.BACK.material())
                .name(context.messages.raw("gui.back"))
                .build(), click -> new AuctionGui(context, viewer, 0).open());
        closeButton();
        fillEmpty();
    }

    private ItemStack safeItem(MailboxEntry entry) {
        try {
            ItemStack item = entry.item() == null ? null : ItemStack.deserializeBytes(entry.item());
            return item == null ? new ItemStack(Material.CHEST) : item;
        } catch (RuntimeException exception) {
            return new ItemStack(Material.CHEST);
        }
    }

    private void collect(MailboxEntry entry) {
        boolean space = viewer.getInventory().firstEmpty() != -1;
        context.auctions.collect(viewer.getUniqueId(), entry.id(), space).thenAccept(result ->
                Scheduler.sync(() -> {
                    CollectedEntry collected = result;
                    if (!collected.success()) {
                        context.messages.send(viewer, collected.result().messageKey());
                        theme.play(viewer, GuiTheme.Click.DENY);
                        return;
                    }
                    if (collected.kind() == MailboxKind.ITEM && collected.item() != null) {
                        var leftover = viewer.getInventory().addItem(collected.item());
                        leftover.values().forEach(overflow ->
                                viewer.getWorld().dropItemNaturally(viewer.getLocation(), overflow));
                    }
                    context.messages.send(viewer, "auction.mailbox.collected",
                            "what", context.money(collected.amount()));
                    theme.play(viewer, GuiTheme.Click.SUCCESS);
                    reload(null);
                }));
    }
}
