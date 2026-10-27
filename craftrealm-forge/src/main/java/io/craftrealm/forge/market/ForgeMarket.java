package io.craftrealm.forge.market;

import io.craftrealm.forge.config.ForgeConfig;
import io.craftrealm.forge.storage.ForgeDatabase;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Configured item allow-list and market persistence adapter. */
public final class ForgeMarket {
    private final ForgeDatabase database;
    private final Logger logger;
    private final Map<String, Item> items;
    private final CompletableFuture<Void> ready;

    public ForgeMarket(ForgeDatabase database, Logger logger) {
        this.database = database;
        this.logger = logger;
        Map<String, Item> parsedItems = new LinkedHashMap<>();
        Map<String, Long> prices = new LinkedHashMap<>();
        for (String entry : ForgeConfig.MARKET_CATALOGUE.get()) {
            int equals = entry.indexOf('=');
            if (equals <= 0 || equals == entry.length() - 1) {
                logger.warn("Ignoring malformed market catalogue entry: {}", entry);
                continue;
            }
            ResourceLocation id = ResourceLocation.tryParse(entry.substring(0, equals).trim().toLowerCase(Locale.ROOT));
            if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
                logger.warn("Ignoring unknown market item: {}", entry);
                continue;
            }
            Item item = BuiltInRegistries.ITEM.get(id);
            if (item == Items.AIR) {
                logger.warn("Ignoring air in market catalogue: {}", entry);
                continue;
            }
            try {
                long cents = new BigDecimal(entry.substring(equals + 1).trim())
                        .setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
                if (cents <= 0) {
                    logger.warn("Ignoring non-positive market price: {}", entry);
                    continue;
                }
                String key = id.toString();
                parsedItems.put(key, item);
                prices.put(key, cents);
            } catch (NumberFormatException | ArithmeticException invalidPrice) {
                logger.warn("Ignoring invalid market price: {}", entry);
            }
        }
        this.items = Map.copyOf(parsedItems);
        if (prices.isEmpty()) {
            logger.warn("Market catalogue is empty; /market, /shop and /sell have no available items");
            this.ready = CompletableFuture.completedFuture(null);
        } else {
            this.ready = database.seedMarket(prices);
        }
    }

    public Item item(String id) {
        ResourceLocation resource = ResourceLocation.tryParse(id.contains(":") ? id : "minecraft:" + id);
        return resource == null ? null : items.get(resource.toString());
    }

    public String itemId(String input) {
        ResourceLocation resource = ResourceLocation.tryParse(input.contains(":") ? input : "minecraft:" + input);
        return resource == null || !items.containsKey(resource.toString()) ? null : resource.toString();
    }

    public CompletableFuture<java.util.List<ForgeDatabase.MarketQuote>> quotes() {
        return ready.thenCompose(ignored -> database.marketQuotes());
    }

    public CompletableFuture<ForgeDatabase.MarketQuote> quote(String id) {
        return ready.thenCompose(ignored -> database.marketQuote(id));
    }

    public CompletableFuture<ForgeDatabase.MarketTrade> trade(java.util.UUID player, String name, String itemId,
                                                                 int quantity, boolean buy,
                                                                 long initialBalanceCents, long maxBalanceCents) {
        if (!items.containsKey(itemId)) {
            return CompletableFuture.completedFuture(ForgeDatabase.MarketTrade.failed("unknown-item"));
        }
        return ready.thenCompose(ignored -> database.tradeMarket(player, name, itemId, quantity, buy,
                initialBalanceCents, maxBalanceCents));
    }

    public CompletableFuture<Void> refund(java.util.UUID player, long amountCents, String itemId, int quantity) {
        return database.refundMarketPurchase(player, amountCents, itemId, quantity);
    }

    public Map<String, Item> items() {
        return items;
    }
}
