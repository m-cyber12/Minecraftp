package io.craftrealm.events.shops;

import io.craftrealm.api.model.ChestShop;
import io.craftrealm.api.service.EconomyService;
import io.craftrealm.core.text.Messages;
import io.craftrealm.core.ui.Gui;
import io.craftrealm.core.ui.GuiTheme;
import io.craftrealm.core.ui.ItemBuilder;
import io.craftrealm.core.util.Scheduler;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The buyer's view of one chest shop: a preview of the goods plus four quantities.
 *
 * <p>Quantities are capped by the shop's stock and by the buyer's balance, and the
 * preview is a *copy* of the goods — clicking it can never take the real stock,
 * which lives in the database.</p>
 */
public final class ShopBrowseGui extends Gui {

    private final ChestShopService shops;
    private final EconomyService economy;
    private final Messages messages;
    private final ChestShop shop;

    public ShopBrowseGui(ChestShopService shops, EconomyService economy, Messages messages,
                         GuiTheme theme, Player viewer, ChestShop shop) {
        super(viewer, theme, 3, messages.get("shops.gui.title"));
        this.shops = shops;
        this.economy = economy;
        this.messages = messages;
        this.shop = shop;
    }

    @Override
    public void render() {
        ItemStack preview = shops.itemOf(shop);
        if (preview == null) {
            set(13, ItemBuilder.of(Material.BARRIER).name(messages.raw("shops.gui.broken")).build());
            closeButton();
            fillEmpty();
            return;
        }
        preview = preview.clone();
        preview.setAmount(Math.max(1, Math.min(preview.getMaxStackSize(), shop.stock())));

        List<Component> lore = new ArrayList<>(messages.bundle().lines("shops.gui.preview.lore",
                "price", economy.format(shop.price()),
                "stock", String.valueOf(shop.stock()),
                "seller", io.craftrealm.core.CraftRealmCore.nameOf(shop.owner())));
        set(4, ItemBuilder.of(preview)
                .name(messages.raw("shops.gui.preview.name", "item", preview.getType().name()))
                .lore(lore)
                .build());

        buyButton(10, 1);
        buyButton(12, 8);
        buyButton(14, 32);
        buyButton(16, 64);

        closeButton();
        fillEmpty();
    }

    private void buyButton(int slot, int quantity) {
        int available = Math.min(quantity, shop.stock());
        double cost = economy == null ? 0 : shop.price() * available;
        set(slot, ItemBuilder.of(Material.GOLD_NUGGET)
                .name(messages.raw("shops.gui.buy.name", "amount", String.valueOf(available)))
                .lore(messages.bundle().lines("shops.gui.buy.lore",
                        "cost", economy.format(cost),
                        "stock", String.valueOf(shop.stock())))
                .build(), click -> {
            if (available <= 0) {
                messages.send(viewer, "shops.out-of-stock");
                theme().play(viewer, GuiTheme.Click.DENY);
                return;
            }
            viewer.closeInventory();
            shops.buy(viewer.getUniqueId(), shop, available).thenAccept(purchase ->
                    Scheduler.sync(() -> {
                        if (!purchase.success() || purchase.item() == null) {
                            messages.send(viewer, purchase.messageKey());
                            theme().play(viewer, GuiTheme.Click.DENY);
                            return;
                        }
                        ItemStack goods = purchase.item().clone();
                        goods.setAmount(Math.max(1, Math.min(goods.getMaxStackSize(),
                                purchase.quantity() * purchase.item().getAmount())));
                        var leftover = viewer.getInventory().addItem(goods);
                        leftover.values().forEach(item ->
                                viewer.getWorld().dropItemNaturally(viewer.getLocation(), item));
                        messages.send(viewer, "shops.bought",
                                "amount", String.valueOf(purchase.quantity()),
                                "item", goods.getType().name(),
                                "total", economy.format(purchase.total()));
                        theme().play(viewer, GuiTheme.Click.PURCHASE);
                    }));
        });
    }
}
