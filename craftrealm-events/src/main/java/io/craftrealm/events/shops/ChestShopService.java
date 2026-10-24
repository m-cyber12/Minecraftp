package io.craftrealm.events.shops;

import io.craftrealm.api.model.ChestShop;
import io.craftrealm.api.model.EconomyResult;
import io.craftrealm.api.model.TransactionType;
import io.craftrealm.api.service.EconomyService;
import io.craftrealm.core.config.Settings;
import io.craftrealm.core.repo.WorldRepository;
import io.craftrealm.core.util.Money;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player-run chest shops.
 *
 * <p><b>Stock is database-authoritative.</b> The chest is emptied when the shop is
 * created and kept as a display block that only the owner can open. That is the
 * only design that cannot be duped: if the goods lived in the chest, a buyer could
 * open it and take them, and a crash between the sale and the save could pay the
 * seller twice.</p>
 *
 * <p>Removing a shop returns whatever stock is left to the owner: as an item drop
 * when they are online at the chest, into the mailbox otherwise.</p>
 */
public final class ChestShopService {

    private final EconomyService economy;
    private final WorldRepository repository;
    private final Settings.ChestShopSettings settings;

    private final java.util.Map<String, ChestShop> cache = new ConcurrentHashMap<>();

    public ChestShopService(EconomyService economy, WorldRepository repository, Settings.ChestShopSettings settings) {
        this.economy = economy;
        this.repository = repository;
        this.settings = settings;
    }

    public CompletableFuture<Void> warmUp() {
        return repository.shops().thenAccept(shops -> {
            cache.clear();
            shops.forEach(shop -> cache.put(key(shop.world(), shop.x(), shop.y(), shop.z()), shop));
        });
    }

    public static String key(String world, int x, int y, int z) {
        return world + ':' + x + ':' + y + ':' + z;
    }

    /** Main-thread safe lookup by chest/sign position. */
    public Optional<ChestShop> cachedAt(String world, int x, int y, int z) {
        return Optional.ofNullable(cache.get(key(world, x, y, z)));
    }

    public CompletableFuture<List<ChestShop>> byOwner(java.util.UUID owner) {
        return repository.shops(owner);
    }

    /** How many shops a player may own; staff bypass the cap. */
    public int maxPerPlayer() {
        return settings.maxPerPlayer();
    }

    public long countOwned(java.util.UUID owner) {
        return cache.values().stream().filter(shop -> shop.owner().equals(owner)).count();
    }

    /**
     * Creates a shop from the chest's first non-empty stack, which becomes the stock.
     *
     * @return the shop, or an error message key
     */
    public CompletableFuture<?> create(java.util.UUID owner, String world, int x, int y, int z,
                                       ItemStack display, int stock, double price) {
        if (price <= 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("price"));
        }
        byte[] serialised = serialise(display);
        return repository.createShop(owner, world, x, y, z, serialised, display.getAmount(), price, stock)
                .thenApply(shop -> {
                    cache.put(key(world, x, y, z), shop);
                    return shop;
                });
    }

    /**
     * Buys {@code quantity} units.
     *
     * <p>The money moves first (buyer → seller) and the stock is decremented with a
     * conditional update; if the stock update fails the payment is reversed. That
     * ordering means a seller can never be paid for goods that were already sold.</p>
     */
    public CompletableFuture<Purchase> buy(java.util.UUID buyer, ChestShop shop, int quantity) {
        int units = Math.max(1, Math.min(quantity, shop.stock()));
        double total = Money.round(shop.price() * units);
        ChestShop current = cache.getOrDefault(key(shop.world(), shop.x(), shop.y(), shop.z()), shop);
        if (current.stock() < units) {
            return CompletableFuture.completedFuture(Purchase.of(false, "shops.out-of-stock", 0));
        }
        return economy.transfer(buyer, current.owner(), total, TransactionType.CHEST_SHOP_BUY,
                "chest shop #" + current.id()).thenCompose(result -> {
            if (!result.success()) {
                return CompletableFuture.completedFuture(Purchase.of(false, result.messageKey(), 0));
            }
            int remaining = current.stock() - units;
            return repository.setStock(current.id(), remaining).thenApply(updated -> {
                if (!updated) {
                    economy.credit(buyer, total, TransactionType.ROLLBACK,
                            "chest shop stock failure", "shop-refund:" + current.id() + ':' + buyer);
                    return Purchase.of(false, "shops.out-of-stock", 0);
                }
                ChestShop next = new ChestShop(current.id(), current.owner(), current.world(), current.x(),
                        current.y(), current.z(), current.item(), current.amount(), current.price(), remaining);
                cache.put(key(next.world(), next.x(), next.y(), next.z()), next);
                return new Purchase(true, "shops.bought", units, total, deserialise(current.item()));
            });
        });
    }

    /** Owner restock: adds to the stored stock. */
    public CompletableFuture<Boolean> restock(ChestShop shop, int amount) {
        int updatedStock = shop.stock() + Math.max(1, amount);
        return repository.setStock(shop.id(), updatedStock).thenApply(done -> {
            if (done) {
                cache.put(key(shop.world(), shop.x(), shop.y(), shop.z()),
                        new ChestShop(shop.id(), shop.owner(), shop.world(), shop.x(), shop.y(), shop.z(),
                                shop.item(), shop.amount(), shop.price(), updatedStock));
            }
            return done;
        });
    }

    /** Removes a shop and hands the remaining stock back as an item. */
    public CompletableFuture<ItemStack> remove(java.util.UUID actor, ChestShop shop) {
        return repository.deleteShop(shop.id()).thenApply(deleted -> {
            if (!deleted) {
                return null;
            }
            cache.remove(key(shop.world(), shop.x(), shop.y(), shop.z()));
            ItemStack left = deserialise(shop.item());
            if (left != null) {
                left.setAmount(Math.max(1, Math.min(left.getMaxStackSize(), shop.stock() * shop.amount())));
            }
            return left;
        });
    }

    /** Items the seller receives per unit sold (the display stack). */
    public ItemStack itemOf(ChestShop shop) {
        return deserialise(shop.item());
    }

    public List<ChestShop> cached() {
        return new ArrayList<>(cache.values());
    }

    // ------------------------------------------------------------- serialising

    /**
     * Bukkit's item serialisation. Kept in one place so both the shop and the
     * auction house use the same (version-tolerant) byte format.
     */
    public static byte[] serialise(ItemStack item) {
        return item.serializeAsBytes();
    }

    public static ItemStack deserialise(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        try {
            return ItemStack.deserializeBytes(data);
        } catch (RuntimeException exception) {
            // A material removed by a server update: better to drop the entry than to
            // break the whole shop list.
            return null;
        }
    }

    /** A completed (or refused) purchase. */
    public record Purchase(boolean success, String messageKey, int quantity, double total, ItemStack item) {

        static Purchase of(boolean success, String messageKey, int quantity) {
            return new Purchase(success, messageKey, quantity, 0, null);
        }

        static Purchase of(boolean success, String messageKey, int quantity, double total, ItemStack item) {
            return new Purchase(success, messageKey, quantity, total, item);
        }
    }
}
