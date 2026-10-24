package io.craftrealm.economy.gui;

import io.craftrealm.api.model.MarketQuote;
import io.craftrealm.api.model.PriceTrend;
import io.craftrealm.api.model.TransactionType;
import io.craftrealm.core.ui.Gui;
import io.craftrealm.core.ui.GuiTheme;
import io.craftrealm.core.ui.ItemBuilder;
import io.craftrealm.core.ui.Pagination;
import io.craftrealm.core.util.Money;
import io.craftrealm.core.util.Scheduler;
import io.craftrealm.economy.EconomyContext;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * The server market.
 *
 * <p>Two screens: a category board made of material-shaped icons, and the item
 * grid. Every item shows its live buy price and a trend arrow computed from the
 * supply/demand engine, so players can see the consequences of their own trading
 * without opening a wiki.</p>
 *
 * <p>Purchases read the cached price on the tick thread (instant), then do the
 * money movement asynchronously and hand over the items back on the main thread.</p>
 */
public final class ShopGui extends Gui {

    private static final int ITEMS_PER_PAGE = 45;

    private final EconomyContext context;
    private final String category;
    private final int page;

    /** Category overview constructor. */
    public ShopGui(EconomyContext context, Player player) {
        this(context, player, null, 0);
    }

    public ShopGui(EconomyContext context, Player player, String category, int page) {
        super(player, context.theme, 6, context.messages.get("shop.title"));
        this.context = context;
        this.category = category;
        this.page = page;
    }

    @Override
    public void render() {
        if (category == null) {
            renderCategories();
        } else {
            renderItems();
        }
    }

    // ------------------------------------------------------------- categories

    private void renderCategories() {
        int slot = 10;
        for (Map.Entry<String, List<Material>> entry : context.settings.catalogue().categories().entrySet()) {
            Material icon = entry.getValue().isEmpty() ? Material.CHEST : entry.getValue().get(0);
            set(slot++, ItemBuilder.of(icon)
                    .name(context.messages.raw("shop.category.name", "category", entry.getKey()))
                    .lore(context.messages.bundle().lines("shop.category.lore",
                            "count", String.valueOf(entry.getValue().size())))
                    .build(), click -> {
                theme.play(viewer, GuiTheme.Click.PAGE);
                new ShopGui(context, viewer, entry.getKey(), 0).open();
            });
            if (slot == 17) {
                break;
            }
        }
        set(49, ItemBuilder.of(Material.EMERALD)
                .name(context.messages.raw("shop.sell-button.name"))
                .lore(context.messages.bundle().lines("shop.sell-button.lore"))
                .build(), click -> {
            theme.play(viewer, GuiTheme.Click.PAGE);
            new SellGui(context, viewer).open();
        });
        closeButton();
        fillEmpty();
    }

    // ------------------------------------------------------------------- items

    private void renderItems() {
        List<Material> materials = context.settings.catalogue().categories()
                .getOrDefault(category, List.of());
        int pages = Pagination.pages(materials.size(), ITEMS_PER_PAGE);
        int current = Pagination.clamp(page, pages);
        List<Material> shown = Pagination.slice(materials, current, ITEMS_PER_PAGE);

        for (int index = 0; index < shown.size(); index++) {
            Material material = shown.get(index);
            double buy = context.market.cachedBuyPrice(material);
            double sell = context.market.cachedPrice(material);
            PriceTrend trend = context.market.cachedTrend(material);

            set(index, ItemBuilder.of(material)
                    .name(context.messages.raw("shop.item.name", "item", pretty(material)))
                    .lore(context.messages.bundle().lines("shop.item.lore",
                            "buy", context.money(buy),
                            "sell", context.money(sell),
                            "trend", trend.arrow(),
                            "stock", "∞"))
                    .build(), click -> buy(material, click.shiftClick() ? 8 : click.rightClick() ? 16 : 1));
        }

        set(45, ItemBuilder.of(GuiTheme.Icon.BACK.material())
                .name(context.messages.raw("gui.back"))
                .build(), click -> {
            theme.play(viewer, GuiTheme.Click.PAGE);
            new ShopGui(context, viewer).open();
        });
        if (current > 0) {
            set(48, ItemBuilder.of(GuiTheme.Icon.PREVIOUS.material())
                    .name(context.messages.raw("gui.previous"))
                    .build(), click -> {
                theme.play(viewer, GuiTheme.Click.PAGE);
                new ShopGui(context, viewer, category, current - 1).open();
            });
        }
        set(49, ItemBuilder.of(Material.PAPER)
                .name(context.messages.raw("gui.page", "page", String.valueOf(current + 1),
                        "pages", String.valueOf(pages)))
                .build());
        if (current + 1 < pages) {
            set(50, ItemBuilder.of(GuiTheme.Icon.NEXT.material())
                    .name(context.messages.raw("gui.next"))
                    .build(), click -> {
                theme.play(viewer, GuiTheme.Click.PAGE);
                new ShopGui(context, viewer, category, current + 1).open();
            });
        }
        set(53, ItemBuilder.of(Material.BARRIER)
                .name(context.messages.raw("gui.close"))
                .build(), click -> {
            theme.play(viewer, GuiTheme.Click.CLOSE);
            viewer.closeInventory();
        });
        fillEmpty();
    }

    // ------------------------------------------------------------------- buying

    private void buy(Material material, int quantity) {
        if (quantity <= 0) {
            return;
        }
        double unit = context.market.cachedBuyPrice(material);
        if (unit <= 0) {
            theme.play(viewer, GuiTheme.Click.DENY);
            return;
        }
        double total = Money.round(unit * quantity);
        if (context.economy.cachedBalance(viewer.getUniqueId()) < total) {
            context.messages.send(viewer, "economy.result.insufficient_funds");
            theme.play(viewer, GuiTheme.Click.DENY);
            return;
        }
        context.economy.withdraw(viewer.getUniqueId(), total, TransactionType.SHOP_BUY,
                "shop: " + material.name() + " x" + quantity).thenAccept(result -> Scheduler.sync(() -> {
            if (!result.success()) {
                context.messages.send(viewer, result.messageKey());
                theme.play(viewer, GuiTheme.Click.DENY);
                refresh();
                return;
            }
            ItemStack stack = new ItemStack(material, quantity);
            Map<Integer, ItemStack> leftover = viewer.getInventory().addItem(stack);
            leftover.values().forEach(overflow ->
                    viewer.getWorld().dropItemNaturally(viewer.getLocation(), overflow));
            context.market.recordBuy(material, quantity);
            context.messages.actionBar(viewer, "shop.bought",
                    "quantity", String.valueOf(quantity),
                    "item", pretty(material),
                    "price", context.money(total),
                    "balance", context.money(result.balanceAfter()));
            theme.play(viewer, GuiTheme.Click.PURCHASE);
            refresh();
        }));
    }

    /** {@code DIAMOND_ORE} → {@code Diamond Ore}. */
    static String pretty(Material material) {
        String[] parts = material.name().toLowerCase(java.util.Locale.ROOT).split("_");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.toString();
    }

    /** Exposed so the sell menu can reuse the same quote formatting. */
    static List<String> quoteLore(EconomyContext context, MarketQuote quote) {
        return context.messages.bundle().lines("shop.quote.lore",
                "base", context.money(quote.basePrice()),
                "price", context.money(quote.price()));
    }
}
