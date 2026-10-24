package io.craftrealm.economy.gui;

import io.craftrealm.api.model.TransactionType;
import io.craftrealm.core.ui.Gui;
import io.craftrealm.core.ui.GuiTheme;
import io.craftrealm.core.ui.ItemBuilder;
import io.craftrealm.core.util.Money;
import io.craftrealm.core.util.Scheduler;
import io.craftrealm.economy.EconomyContext;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * {@code /sell} — a chest you fill with items, valued live against the market.
 *
 * <p>Safety rules:</p>
 * <ul>
 *   <li>The menu is the only CraftRealm GUI that allows item movement.</li>
 *   <li>Closing without confirming returns everything.</li>
 *   <li>Confirming clears the slots <em>before</em> the asynchronous payout, so the
 *       items cannot be taken twice even if the payout fails; a failed payout is
 *       logged and the player is told to contact staff.</li>
 * </ul>
 */
public final class SellGui extends Gui {

    private static final int INPUT_SLOTS = 45;
    private static final int INFO_SLOT = 45;
    private static final int CONFIRM_SLOT = 49;
    private static final int CLOSE_SLOT = 53;

    private final EconomyContext context;
    private boolean completed;

    public SellGui(EconomyContext context, Player player) {
        super(player, context.theme, 6, context.messages.get("sell.title"));
        this.context = context;
        onClose(this::returnLeftovers);
    }

    @Override
    public boolean allowsItemMovement() {
        return true;
    }

    @Override
    public void render() {
        // Only the action row is redrawn; the input area belongs to the player.
        for (int slot = INPUT_SLOTS; slot < size(); slot++) {
            set(slot, theme.filler());
        }
        set(CLOSE_SLOT, ItemBuilder.of(Material.BARRIER)
                .name(context.messages.raw("gui.close"))
                .lore(context.messages.raw("sell.close-lore"))
                .build(), click -> {
            theme.play(viewer, GuiTheme.Click.CLOSE);
            viewer.closeInventory();
        });
        updateInfo();
    }

    @Override
    public void handleClick(io.craftrealm.core.ui.GuiClick click) {
        if (click.slot() >= 0 && click.slot() < INPUT_SLOTS) {
            // The item is still moving when this event fires; re-price next tick.
            Scheduler.syncLater(this::updateInfo, 1);
            return;
        }
        super.handleClick(click);
    }

    /** Recomputes the offer for whatever is currently in the input slots. */
    private void updateInfo() {
        Map<Material, Integer> counts = collect();
        double total = value(counts);
        int items = counts.values().stream().mapToInt(Integer::intValue).sum();

        List<String> lore = new ArrayList<>(context.messages.bundle().lines("sell.info.lore",
                "items", String.valueOf(items), "total", context.money(total)));
        if (!counts.isEmpty()) {
            lore.add("");
            int shown = 0;
            for (Map.Entry<Material, Integer> entry : counts.entrySet()) {
                if (shown++ >= 6) {
                    lore.add(context.messages.raw("sell.info.more", "count", String.valueOf(counts.size() - 6)));
                    break;
                }
                lore.add(context.messages.raw("sell.info.line",
                        "item", ShopGui.pretty(entry.getKey()),
                        "quantity", String.valueOf(entry.getValue()),
                        "value", context.money(context.market.cachedPrice(entry.getKey()) * entry.getValue())));
            }
        }
        set(INFO_SLOT, ItemBuilder.of(Material.GOLD_INGOT)
                .name(context.messages.raw("sell.info.name", "total", context.money(total)))
                .lore(lore)
                .build());

        boolean payable = total > 0;
        set(CONFIRM_SLOT, ItemBuilder.of(payable ? Material.LIME_DYE : Material.GRAY_DYE)
                .name(context.messages.raw(payable ? "sell.confirm.name" : "sell.confirm-empty.name"))
                .lore(context.messages.bundle().lines("sell.confirm.lore", "total", context.money(total)))
                .build(), click -> {
            if (payable) {
                confirm();
            } else {
                theme.play(viewer, GuiTheme.Click.DENY);
            }
        });
    }

    private void confirm() {
        Map<Material, Integer> counts = collect();
        double total = value(counts);
        if (total <= 0) {
            theme.play(viewer, GuiTheme.Click.DENY);
            return;
        }
        // Take the items first: after this point they exist only in the local map.
        clearInput();
        completed = true;
        updateInfo();

        context.economy.deposit(viewer.getUniqueId(), total, TransactionType.SHOP_SELL, "sell menu")
                .thenAccept(result -> Scheduler.sync(() -> {
                    if (!result.success()) {
                        context.messages.send(viewer, result.messageKey());
                        theme.play(viewer, GuiTheme.Click.DENY);
                        return;
                    }
                    counts.forEach((material, quantity) -> context.market.recordSell(material, quantity));
                    context.messages.send(viewer, "sell.sold",
                            "total", context.money(total),
                            "balance", context.money(result.balanceAfter()));
                    theme.play(viewer, GuiTheme.Click.PURCHASE);
                    completed = false;
                }));
    }

    /** Items still in the menu; counted per material for pricing. */
    private Map<Material, Integer> collect() {
        Map<Material, Integer> counts = new EnumMap<>(Material.class);
        for (int slot = 0; slot < INPUT_SLOTS; slot++) {
            ItemStack item = getInventory().getItem(slot);
            if (item == null || item.getType().isAir()) {
                continue;
            }
            counts.merge(item.getType(), item.getAmount(), Integer::sum);
        }
        return counts;
    }

    private double value(Map<Material, Integer> counts) {
        double total = 0.0;
        for (Map.Entry<Material, Integer> entry : counts.entrySet()) {
            double unit = context.market.cachedPrice(entry.getKey());
            total += unit * entry.getValue();
        }
        return Money.round(total);
    }

    private void clearInput() {
        for (int slot = 0; slot < INPUT_SLOTS; slot++) {
            getInventory().setItem(slot, null);
        }
    }

    /** Returns anything left behind (close without confirming, or a crash). */
    private void returnLeftovers() {
        if (completed) {
            return;
        }
        for (int slot = 0; slot < INPUT_SLOTS; slot++) {
            ItemStack item = getInventory().getItem(slot);
            if (item == null || item.getType().isAir()) {
                continue;
            }
            getInventory().setItem(slot, null);
            Map<Integer, ItemStack> leftover = viewer.getInventory().addItem(item);
            leftover.values().forEach(overflow ->
                    viewer.getWorld().dropItemNaturally(viewer.getLocation(), overflow));
        }
    }
}
