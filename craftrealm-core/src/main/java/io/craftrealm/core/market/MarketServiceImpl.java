package io.craftrealm.core.market;

import io.craftrealm.api.event.MarketPriceChangeEvent;
import io.craftrealm.api.model.MarketQuote;
import io.craftrealm.api.model.PriceTrend;
import io.craftrealm.api.service.MarketService;
import io.craftrealm.core.config.MarketSettings;
import io.craftrealm.core.db.Database;
import io.craftrealm.core.repo.MarketRepository;
import io.craftrealm.core.util.Events;
import io.craftrealm.core.util.Money;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the price table in memory for instant GUI reads and persists changes in
 * the background.
 *
 * <p>Price mutation is serialised on this object: market ticks are rare enough
 * (a few per second at most) that a single lock is cheaper than any lock-free
 * scheme, and it guarantees two simultaneous wheat sales cannot lose an update.</p>
 */
public final class MarketServiceImpl implements MarketService {

    private final Database database;
    private final MarketRepository repository;
    private final MarketSettings settings;
    private final MarketPriceEngine engine;
    private final Map<Material, MarketQuote> quotes = new ConcurrentHashMap<>();

    public MarketServiceImpl(Database database, MarketSettings settings) {
        this.database = database;
        this.repository = new MarketRepository(database);
        this.settings = settings;
        this.engine = new MarketPriceEngine(settings);
    }

    public MarketPriceEngine engine() {
        return engine;
    }

    @Override
    public double basePrice(Material material) {
        MarketQuote quote = quotes.get(material);
        return quote == null ? 0.0 : quote.basePrice();
    }

    @Override
    public double cachedPrice(Material material) {
        MarketQuote quote = quotes.get(material);
        return quote == null ? 0.0 : quote.price();
    }

    @Override
    public double cachedBuyPrice(Material material) {
        return engine.buyPrice(cachedPrice(material));
    }

    @Override
    public PriceTrend cachedTrend(Material material) {
        MarketQuote quote = quotes.get(material);
        return quote == null ? PriceTrend.STABLE : engine.trend(quote.basePrice(), quote.price());
    }

    @Override
    public CompletableFuture<MarketQuote> quote(Material material) {
        MarketQuote cached = quotes.get(material);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        return CompletableFuture.completedFuture(new MarketQuote(material, 0.0, 0.0, 0, 0, 0, 0));
    }

    @Override
    public CompletableFuture<List<MarketQuote>> quotes(List<Material> materials) {
        List<MarketQuote> result = new ArrayList<>(materials.size());
        for (Material material : materials) {
            MarketQuote quote = quotes.get(material);
            if (quote != null) {
                result.add(quote);
            }
        }
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<List<MarketQuote>> allQuotes() {
        return CompletableFuture.completedFuture(new ArrayList<>(quotes.values()));
    }

    @Override
    public CompletableFuture<MarketQuote> recordSell(Material material, int amount) {
        return repriced(material, amount, true);
    }

    @Override
    public CompletableFuture<MarketQuote> recordBuy(Material material, int amount) {
        return repriced(material, amount, false);
    }

    private CompletableFuture<MarketQuote> repriced(Material material, int amount, boolean sale) {
        if (amount <= 0) {
            return quote(material);
        }
        MarketQuote updated;
        double oldPrice;
        synchronized (this) {
            MarketQuote current = quotes.get(material);
            if (current == null) {
                return CompletableFuture.completedFuture(null);
            }
            double pressure = sale
                    ? engine.pressureAfterSell(current.pressure(), amount)
                    : engine.pressureAfterBuy(current.pressure(), amount);
            double price = engine.priceFor(current.basePrice(), pressure);
            oldPrice = current.price();
            updated = new MarketQuote(material, current.basePrice(), price,
                    current.totalSold() + (sale ? amount : 0),
                    current.totalBought() + (sale ? 0 : amount),
                    pressure, System.currentTimeMillis());
            quotes.put(material, updated);
        }
        if (Math.abs(oldPrice - updated.price()) >= 0.005) {
            Events.call(new MarketPriceChangeEvent(material, oldPrice, updated.price()));
        }
        // Persist asynchronously; the hot cache is already correct for gameplay.
        repository.save(updated);
        repository.recordHistory(material, updated.price(), amount);
        return CompletableFuture.completedFuture(updated);
    }

    @Override
    public CompletableFuture<Void> warmUp() {
        return repository.loadAll().thenAccept(rows -> {
            quotes.clear();
            for (MarketQuote row : rows) {
                quotes.put(row.material(), row);
            }
        });
    }

    /**
     * Seeds the table from configuration: new materials get their base price, and
     * materials that disappeared from the config are dropped from the hot cache.
     */
    public void seed(Map<Material, Double> basePrices) {
        long now = System.currentTimeMillis();
        for (Map.Entry<Material, Double> entry : basePrices.entrySet()) {
            MarketQuote created = quotes.computeIfAbsent(entry.getKey(), material -> {
                double price = Money.round(entry.getValue());
                return new MarketQuote(material, price, price, 0, 0, 0.0, now);
            });
            // Persist the anchor price once so restarts keep the configured value.
            if (created.updatedAt() <= 0L) {
                repository.save(created);
            }
        }
        // Drop anything that is no longer configured.
        retainOnly(basePrices.keySet());
    }

    @Override
    public CompletableFuture<Integer> tickRecovery() {
        List<MarketQuote> changed = new ArrayList<>();
        long now = System.currentTimeMillis();
        synchronized (this) {
            for (MarketQuote quote : quotes.values()) {
                double hours = Math.max(0.0, (now - quote.updatedAt()) / 3_600_000.0);
                if (hours <= 0.0 || Math.abs(quote.pressure()) < 0.001) {
                    continue;
                }
                double pressure = engine.pressureAfterRecovery(quote.pressure(), hours);
                double price = engine.priceFor(quote.basePrice(), pressure);
                if (Math.abs(price - quote.price()) < 0.01) {
                    continue;
                }
                MarketQuote updated = new MarketQuote(quote.material(), quote.basePrice(), price,
                        quote.totalSold(), quote.totalBought(), pressure, now);
                quotes.put(quote.material(), updated);
                changed.add(updated);
                Events.call(new MarketPriceChangeEvent(quote.material(), quote.price(), price));
            }
        }
        if (!changed.isEmpty()) {
            repository.saveAll(changed);
        }
        return CompletableFuture.completedFuture(changed.size());
    }

    /**
     * Writes the whole hot price table in one transaction.
     *
     * <p>Trades already persist individually; this is the shutdown path, where a
     * recovery tick may have been in flight. Bounded by the caller's timeout.</p>
     */
    public CompletableFuture<Void> flush() {
        List<MarketQuote> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(quotes.values());
        }
        return snapshot.isEmpty() ? CompletableFuture.completedFuture(null) : repository.saveAll(snapshot);
    }

    /** Restricts the tracked catalogue to the configured materials. */
    public void retainOnly(Collection<Material> materials) {
        quotes.keySet().retainAll(materials);
    }

    /** Every material with a live quote — used by the shop and the web API. */
    public List<Material> trackedMaterials() {
        return new ArrayList<>(quotes.keySet());
    }
}
