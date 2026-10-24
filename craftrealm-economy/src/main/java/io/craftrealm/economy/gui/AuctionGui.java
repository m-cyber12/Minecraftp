package io.craftrealm.economy.gui;

import io.craftrealm.api.model.AuctionListing;
import io.craftrealm.api.model.EconomyResult;
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
import net.kyori.adventure.text.Component;

/**
 * Auction browser.
 *
 * <p>Click bids the minimum increment (the server raises the stakes for you),
 * shift-click uses buy-now. Both paths escrow money, so the GUI never has to
 * trust the client.</p>
 */
public final class AuctionGui extends Gui {

    private static final int PER_PAGE = 45;

    private final EconomyContext context;
    private final int page;
    private List<AuctionListing> listings = List.of();
    private long total;
    private long unclaimed;

    public AuctionGui(EconomyContext context, Player player, int page) {
        super(player, context.theme, 6, context.messages.get("auction.title"));
        this.context = context;
        this.page = page;
    }

    @Override
    public void open() {
        reload(() -> super.open());
    }

    private void reload(Runnable afterwards) {
        var listingsFuture = context.auctions.active(page * PER_PAGE, PER_PAGE);
        var mailFuture = context.auctions.unclaimed(viewer.getUniqueId());
        java.util.concurrent.CompletableFuture.allOf(listingsFuture, mailFuture).thenRun(() ->
                Scheduler.sync(() -> {
                    listings = listingsFuture.join();
                    unclaimed = mailFuture.join();
                    if (afterwards != null) {
                        afterwards.run();
                    } else {
                        refresh();
                    }
                }));
    }

    @Override
    public void render() {
        for (int index = 0; index < listings.size(); index++) {
            AuctionListing listing = listings.get(index);
            ItemStack icon = iconFor(listing);
            // Mirrors AuctionFeeCalculator#minimumBid using the live settings.
            double increment = context.settings.auction().minBidIncrementPct() / 100.0;
            double minimum = listing.topBid() > 0
                    ? io.craftrealm.core.util.Money.round(listing.topBid() * (1.0 + increment))
                    : listing.startingBid();

            List<Component> lore = context.messages.bundle().lines("auction.listing.lore",
                    "seller", listing.sellerName() == null ? "?" : listing.sellerName(),
                    "bid", context.money(listing.currentPrice()),
                    "minimum", context.money(minimum),
                    "buy", listing.buyNow() > 0 ? context.money(listing.buyNow()) : "-",
                    "time", TimeUtil.formatDuration(listing.remainingMillis()));
            set(index, ItemBuilder.of(icon)
                    .name(context.messages.raw("auction.listing.name",
                            "item", ShopGui.pretty(icon.getType())))
                    .lore(lore)
                    .build(), click -> {
                if (click.shiftClick() && listing.buyNow() > 0) {
                    buyNow(listing);
                } else {
                    bid(listing, minimum);
                }
            });
        }

        set(45, ItemBuilder.of(GuiTheme.Icon.MAILBOX.material())
                .name(context.messages.raw("auction.mailbox.name"))
                .lore(context.messages.bundle().lines("auction.mailbox.lore",
                        "count", String.valueOf(unclaimed)))
                .glint(unclaimed > 0)
                .build(), click -> {
            theme.play(viewer, GuiTheme.Click.PAGE);
            new MailboxGui(context, viewer).open();
        });

        set(49, ItemBuilder.of(Material.GOLDEN_HORSE_ARMOR)
                .name(context.messages.raw("auction.sell.name"))
                .lore(context.messages.bundle().lines("auction.sell.lore"))
                .build(), click -> {
            ItemStack hand = viewer.getInventory().getItemInMainHand();
            if (hand == null || hand.getType().isAir()) {
                context.messages.send(viewer, "auction.sell.empty-hand");
                theme.play(viewer, GuiTheme.Click.DENY);
                return;
            }
            new ListingGui(context, viewer, hand.clone()).open();
        });

        if (page > 0) {
            set(48, ItemBuilder.of(GuiTheme.Icon.PREVIOUS.material())
                    .name(context.messages.raw("gui.previous")).build(),
                    click -> new AuctionGui(context, viewer, page - 1).open());
        }
        if (listings.size() == PER_PAGE) {
            set(50, ItemBuilder.of(GuiTheme.Icon.NEXT.material())
                    .name(context.messages.raw("gui.next")).build(),
                    click -> new AuctionGui(context, viewer, page + 1).open());
        }
        closeButton();
        fillEmpty();
    }

    /** Defensive item lookup: a corrupt blob shows a chest instead of crashing the menu. */
    private ItemStack iconFor(AuctionListing listing) {
        if (listing.item() == null || listing.item().length == 0) {
            return new ItemStack(Material.CHEST);
        }
        try {
            return ItemStack.deserializeBytes(listing.item());
        } catch (RuntimeException exception) {
            return new ItemStack(Material.CHEST);
        }
    }

    private void bid(AuctionListing listing, double amount) {
        context.auctions.bid(viewer.getUniqueId(), listing.id(), amount).thenAccept(result ->
                Scheduler.sync(() -> {
                    handle(result, "auction.bid-placed", amount);
                    reload(null);
                }));
    }

    private void buyNow(AuctionListing listing) {
        context.auctions.buyNow(viewer.getUniqueId(), listing.id()).thenAccept(result ->
                Scheduler.sync(() -> {
                    handle(result, "auction.bought", listing.buyNow());
                    reload(null);
                }));
    }

    private void handle(EconomyResult result, String successKey, double amount) {
        if (result.success()) {
            context.messages.send(viewer, successKey, "amount", context.money(amount));
            theme.play(viewer, GuiTheme.Click.PURCHASE);
        } else {
            context.messages.send(viewer, result.messageKey());
            theme.play(viewer, GuiTheme.Click.DENY);
        }
    }
}
