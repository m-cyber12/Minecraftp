package io.craftrealm.core.repo;

import io.craftrealm.api.model.MarketQuote;
import io.craftrealm.core.db.Database;
import io.craftrealm.core.db.Jdbc;
import org.bukkit.Material;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Current market prices plus a rolling history for the sparkline charts. */
public final class MarketRepository {

    private final Database database;

    public MarketRepository(Database database) {
        this.database = database;
    }

    public CompletableFuture<List<MarketQuote>> loadAll() {
        return database.supply(connection -> Jdbc.list(connection,
                "SELECT * FROM cr_market", MarketRepository::map));
    }

    public CompletableFuture<MarketQuote> save(MarketQuote quote) {
        return database.supply(connection -> {
            String[] columns = {"material", "base_price", "price", "total_sold", "total_bought",
                    "pressure", "updated_at"};
            Jdbc.update(connection, database.dialect().upsert("cr_market", columns, new String[]{"material"}),
                    quote.material().name(), quote.basePrice(), quote.price(), quote.totalSold(),
                    quote.totalBought(), quote.pressure(), quote.updatedAt());
            return quote;
        });
    }

    /** Writes the whole price table in one transaction (used by the recovery tick). */
    public CompletableFuture<Void> saveAll(List<MarketQuote> quotes) {
        return database.inTransaction(connection -> {
            String[] columns = {"material", "base_price", "price", "total_sold", "total_bought",
                    "pressure", "updated_at"};
            String sql = database.dialect().upsert("cr_market", columns, new String[]{"material"});
            for (MarketQuote quote : quotes) {
                Jdbc.update(connection, sql, quote.material().name(), quote.basePrice(), quote.price(),
                        quote.totalSold(), quote.totalBought(), quote.pressure(), quote.updatedAt());
            }
            return null;
        });
    }

    public CompletableFuture<Void> recordHistory(Material material, double price, long volume) {
        return database.run(connection -> Jdbc.update(connection,
                "INSERT INTO cr_market_history (material, price, volume, recorded_at) VALUES (?, ?, ?, ?)",
                material.name(), price, volume, System.currentTimeMillis()));
    }

    public CompletableFuture<List<Double>> history(Material material, int points) {
        return database.supply(connection -> Jdbc.list(connection,
                "SELECT price FROM cr_market_history WHERE material = ? ORDER BY id DESC LIMIT ?",
                row -> row.getDouble(1), material.name(), Math.max(1, points)));
    }

    public CompletableFuture<Void> snapshot(double total, int accounts) {
        return database.run(connection -> Jdbc.update(connection,
                "INSERT INTO cr_economy_snapshots (total, accounts, recorded_at) VALUES (?, ?, ?)",
                total, accounts, System.currentTimeMillis()));
    }

    /** Money-supply history, oldest first, for the inflation graph. */
    public CompletableFuture<List<Double>> supplyHistory(int points) {
        return database.supply(connection -> {
            List<Double> newestFirst = Jdbc.list(connection,
                    "SELECT total FROM cr_economy_snapshots ORDER BY id DESC LIMIT ?",
                    row -> row.getDouble(1), Math.max(1, points));
            java.util.Collections.reverse(newestFirst);
            return newestFirst;
        });
    }

    private static MarketQuote map(java.sql.ResultSet row) throws java.sql.SQLException {
        Material material = Material.matchMaterial(row.getString("material"));
        if (material == null) {
            material = Material.STONE;
        }
        return new MarketQuote(material,
                row.getDouble("base_price"),
                row.getDouble("price"),
                row.getLong("total_sold"),
                row.getLong("total_bought"),
                row.getDouble("pressure"),
                row.getLong("updated_at"));
    }
}
