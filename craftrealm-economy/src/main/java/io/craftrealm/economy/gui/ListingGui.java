package io.craftrealm.economy.gui;

import io.craftrealm.core.ui.Gui;
import io.craftrealm.core.ui.ItemBuilder;
import io.craftrealm.core.ui.GuiTheme;
import io.craftrealm.core.util.Money;
import io.craftrealm.core.util.Scheduler;
import io.craftrealm.economy.EconomyContext;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Create an auction listing from the item in your hand.
 *
 * <p>The item is removed from the hand only after the database row exists — if the
 * listing fails (fee unaffordable, too many listings) it stays with the player.</p>
 */
public final class ListingGui extends Gui {

    private final EconomyContext context;
    private final ItemStack item;
    private double startingPrice;
    private double buyNow;

    public ListingGui(EconomyContext context, Player player, ItemStack item) {
        super(player, context.theme, 3, context.messages.get("auction.listing.title"));
        this.context = context;
        this.item = item;
        double suggested = Math.max(context.settings.auction().minPrice(),
                context.market.cachedBuyPrice(item.getType()) * item.getAmount() * 2.0);
        this.startingPrice = Money.round(Math.min(suggested, context.settings.auction().maxPrice()));
        this.buyNow = Money.round(Math.min(startingPrice * 2.0, context.settings.auction().maxPrice()));
    }

    @Override
    public void render() {
        set(13, ItemBuilder.of(item)
                .lore(context.messages.bundle().lines("auction.listing.quote",
                        "start", context.money(startingPrice),
                        "buy", context.money(buyNow),
                        "fee", context.money(context.auctions.listingFee(startingPrice))))
                .build());

        set(10, ItemBuilder.of(Material.RED_DYE)
                .name(context.messages.raw("auction.listing.lower",
                        "amount", context.money(Math.max(1.0, startingPrice * 0.9))))
                .build(), click -> adjust(0.9));

        set(16, ItemBuilder.of(Material.LIME_DYE)
                .name(context.messages.raw("auction.listing.raise",
                        "amount", context.money(startingPrice * 1.1)))
                .build(), click -> adjust(1.1));

        set(22, ItemBuilder.of(Material.GOLD_INGOT)
                .name(context.messages.raw("auction.listing.confirm",
                        "amount", context.money(startingPrice)))
                .lore(context.messages.bundle().lines("auction.listing.confirm-lore",
                        "fee", context.money(context.auctions.listingFee(startingPrice))))
                .build(), click -> confirm());

        set(26, ItemBuilder.of(Material.BARRIER)
                .name(context.messages.raw("gui.cancel"))
                .build(), click -> viewer.closeInventory());
        fillEmpty();
    }

    private void adjust(double factor) {
        startingPrice = Money.round(Math.max(context.settings.auction().minPrice(),
                Math.min(context.settings.auction().maxPrice(), startingPrice * factor)));
        buyNow = Money.round(Math.min(context.settings.auction().maxPrice(), startingPrice * 2.0));
        theme.play(viewer, GuiTheme.Click.BUTTON);
        refresh();
    }

    private void confirm() {
        ItemStack hand = viewer.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir() || !hand.isSimilar(item)) {
            context.messages.send(viewer, "auction.sell.changed-hand");
            theme.play(viewer, GuiTheme.Click.DENY);
            return;
        }
        int amount = hand.getAmount();
        viewer.closeInventory();

        context.auctions.create(viewer.getUniqueId(), item, startingPrice, buyNow,
                context.settings.auction().defaultDuration()).thenAccept(listing ->
                Scheduler.sync(() -> {
                    if (listing == null) {
                        context.messages.send(viewer, "auction.sell.failed");
                        theme.play(viewer, GuiTheme.Click.DENY);
                        return;
                    }
                    // Only now do we take the item, and only if the hand still holds it.
                    ItemStack current = viewer.getInventory().getItemInMainHand();
                    if (current != null && current.isSimilar(item) && current.getAmount() >= amount) {
                        viewer.getInventory().setItemInMainHand(null);
                    } else if (!viewer.getInventory().containsAtLeast(item, amount)) {
                        context.messages.send(viewer, "auction.sell.item-moved");
                    } else {
                        viewer.getInventory().removeItem(item.asQuantity(amount));
                    }
                    context.messages.send(viewer, "auction.sell.listed",
                            "item", ShopGui.pretty(item.getType()),
                            "price", context.money(startingPrice));
                    theme.play(viewer, GuiTheme.Click.CONFIRM);
                }));
    }

    /** Shown by the auction browser. */
    static List<net.kyori.adventure.text.Component> hint(EconomyContext context) {
        return context.messages.bundle().lines("auction.listing.hint");
    }
}
