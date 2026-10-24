package io.craftrealm.api.service;

import io.craftrealm.api.model.MarketQuote;
import io.craftrealm.api.model.PriceTrend;
import org.bukkit.Material;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A supply-and-demand market.
 *
 * <p>Selling a lot of a material pushes its price down; buying it back pushes the
 * price up. Between trades the price drifts back towards the configured base
 * price, which stops the economy from dying after one bad day of farming.</p>
 */
public interface MarketService {

    /** Base price configured in {@code market.base-prices}. */
    double basePrice(Material material);

    /** Current price, from the hot cache. Main-thread safe. */
    double cachedPrice(Material material);

    /** Buy price for players (market price × configured spread). */
    double cachedBuyPrice(Material material);

    CompletableFuture<MarketQuote> quote(Material material);

    CompletableFuture<List<MarketQuote>> quotes(List<Material> materials);

    CompletableFuture<List<MarketQuote>> allQuotes();

    /** Trend compared with the base price, for the ↑ ↓ → arrows. */
    PriceTrend cachedTrend(Material material);

    /** Records a sale (player → market) and re-prices. */
    CompletableFuture<MarketQuote> recordSell(Material material, int amount);

    /** Records a purchase (market → player) and re-prices. */
    CompletableFuture<MarketQuote> recordBuy(Material material, int amount);

    /** Loads the hot cache; called on enable. */
    CompletableFuture<Void> warmUp();

    /** Scheduled: lets prices drift back towards base. */
    CompletableFuture<Integer> tickRecovery();
}
