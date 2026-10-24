package io.craftrealm.events.shops;

import io.craftrealm.api.model.ChestShop;
import io.craftrealm.api.service.EconomyService;
import io.craftrealm.core.text.Messages;
import io.craftrealm.core.util.Scheduler;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

/**
 * Creates and enforces chest shops.
 *
 * <p>A shop is a sign whose first line is {@code [shop]} placed against a chest.
 * The chest is emptied the moment the shop is created — from then on the database
 * owns the stock, and the chest is only a display case.</p>
 */
public final class ChestShopListener implements Listener {

    private static final String TAG = "[shop]";

    private final ChestShopService shops;
    private final EconomyService economy;
    private final Messages messages;
    private final io.craftrealm.core.ui.GuiTheme theme;

    public ChestShopListener(ChestShopService shops, EconomyService economy, Messages messages,
                             io.craftrealm.core.ui.GuiTheme theme) {
        this.shops = shops;
        this.economy = economy;
        this.messages = messages;
        this.theme = theme;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSign(SignChangeEvent event) {
        if (!event.getLine(0).trim().equalsIgnoreCase(TAG)) {
            return;
        }
        Player player = event.getPlayer();
        Block signBlock = event.getBlock();
        Block chest = attachedTo(signBlock);
        if (chest == null || !isChest(chest.getType())) {
            messages.send(player, "shops.need-chest");
            return;
        }
        if (shops.cachedAt(chest.getWorld().getName(), chest.getX(), chest.getY(), chest.getZ()).isPresent()) {
            messages.send(player, "shops.exists");
            return;
        }
        if (!player.hasPermission("craftrealm.shop.limit-bypass")
                && shops.countOwned(player.getUniqueId()) >= shops.maxPerPlayer()) {
            messages.send(player, "shops.limit");
            return;
        }
        double price;
        try {
            price = io.craftrealm.core.util.Money.parse(event.getLine(1));
        } catch (NumberFormatException exception) {
            messages.send(player, "shops.bad-price");
            return;
        }
        if (!io.craftrealm.core.util.Money.isValidAmount(price)) {
            messages.send(player, "shops.bad-price");
            return;
        }

        org.bukkit.block.Chest container = (org.bukkit.block.Chest) chest.getState();
        ItemStack display = null;
        int stock = 0;
        for (ItemStack content : container.getBlockInventory().getContents()) {
            if (content != null && content.getType() != Material.AIR) {
                display = content.clone();
                stock = content.getAmount();
                content.setAmount(0);
                break;
            }
        }
        if (display == null) {
            messages.send(player, "shops.empty-chest");
            return;
        }
        if (stock < 1) {
            messages.send(player, "shops.empty-chest");
            return;
        }

        // The chest is emptied: stock now lives in the database.
        container.getBlockInventory().clear();
        ItemStack finalDisplay = display;
        int finalStock = stock;
        shops.create(player.getUniqueId(), chest.getWorld().getName(), chest.getX(), chest.getY(),
                        chest.getZ(), display, stock, price)
                .thenAccept(result -> Scheduler.sync(() -> {
                    if (signBlock.getState() instanceof Sign sign) {
                        sign.setLine(0, "\u00A76[Shop]");
                        sign.setLine(1, "\u00A7f" + economy.formatShort(price));
                        sign.setLine(2, "\u00A7a" + finalStock + " in stock");
                        sign.setLine(3, "\u00A77" + player.getName());
                        sign.update(true);
                    }
                    messages.send(player, "shops.created", "item", finalDisplay.getType().name(),
                            "stock", String.valueOf(finalStock), "price", economy.format(price));
                }));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null) {
            return;
        }
        Block block = event.getClickedBlock();
        Player player = event.getPlayer();

        Optional<ChestShop> shop = resolve(block);
        if (shop.isEmpty()) {
            return;
        }
        event.setCancelled(true);

        if (block.getType() == Material.CHEST || block.getType() == Material.TRAPPED_CHEST) {
            // Display case: only the owner may look inside.
            if (!shop.get().owner().equals(player.getUniqueId()) && !player.hasPermission("craftrealm.admin")) {
                messages.send(player, "shops.locked");
                return;
            }
            return;
        }
        new ShopBrowseGui(shops, economy, messages, theme, player, shop.get()).open();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Optional<ChestShop> shop = resolve(block);
        if (shop.isEmpty()) {
            return;
        }
        Player player = event.getPlayer();
        if (block.getType() != Material.CHEST && block.getType() != Material.TRAPPED_CHEST
                && !(block.getState() instanceof Sign)) {
            return;
        }
        if (!shop.get().owner().equals(player.getUniqueId()) && !player.hasPermission("craftrealm.admin")) {
            event.setCancelled(true);
            messages.send(player, "shops.not-owner");
            return;
        }
        ChestShop target = shop.get();
        shops.remove(player.getUniqueId(), target).thenAccept(leftover -> Scheduler.sync(() -> {
            if (leftover != null) {
                var drop = player.getInventory().addItem(leftover);
                drop.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
            }
            messages.send(player, "shops.removed");
        }));
    }

    // ----------------------------------------------------------------- helpers

    /** The shop at a chest or at a sign attached to a chest. */
    private Optional<ChestShop> resolve(Block block) {
        Optional<ChestShop> direct = shops.cachedAt(block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ());
        if (direct.isPresent()) {
            return direct;
        }
        if (block.getState() instanceof Sign) {
            Block chest = attachedTo(block);
            if (chest != null) {
                return shops.cachedAt(chest.getWorld().getName(), chest.getX(), chest.getY(), chest.getZ());
            }
        }
        return Optional.empty();
    }

    /** The block a sign is attached to. */
    private static Block attachedTo(Block signBlock) {
        if (signBlock.getBlockData() instanceof Directional directional) {
            return signBlock.getRelative(directional.getFacing().getOppositeFace());
        }
        return signBlock.getRelative(BlockFace.DOWN);
    }

    private static boolean isChest(Material material) {
        return material == Material.CHEST || material == Material.TRAPPED_CHEST;
    }
}
