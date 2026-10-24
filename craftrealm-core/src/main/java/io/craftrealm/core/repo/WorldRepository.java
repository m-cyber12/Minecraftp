package io.craftrealm.core.repo;

import io.craftrealm.api.model.ChestShop;
import io.craftrealm.api.model.Claim;
import io.craftrealm.api.model.Treasure;
import io.craftrealm.core.db.Database;
import io.craftrealm.core.db.Jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Land claims, player chest shops and treasure hunts. */
public final class WorldRepository {

    private final Database database;

    public WorldRepository(Database database) {
        this.database = database;
    }

    // ----------------------------------------------------------------- claims

    public CompletableFuture<Claim> createClaim(UUID owner, String world, int minX, int minZ,
                                                int maxX, int maxZ, long paidUntil) {
        return database.supply(connection -> {
            long now = System.currentTimeMillis();
            long id = Jdbc.insert(connection,
                    "INSERT INTO cr_claims (owner, world, min_x, min_z, max_x, max_z, created_at,"
                            + " paid_until, last_tax_run) VALUES (?,?,?,?,?,?,?,?,?)",
                    owner, world, minX, minZ, maxX, maxZ, now, paidUntil, now);
            return new Claim(id, owner, world, minX, minZ, maxX, maxZ, now, paidUntil, now);
        });
    }

    public CompletableFuture<List<Claim>> claims() {
        return database.supply(connection -> Jdbc.list(connection,
                "SELECT * FROM cr_claims", WorldRepository::mapClaim));
    }

    public CompletableFuture<List<Claim>> claims(UUID owner) {
        return database.supply(connection -> Jdbc.list(connection,
                "SELECT * FROM cr_claims WHERE owner = ? ORDER BY id DESC", WorldRepository::mapClaim, owner));
    }

    public CompletableFuture<Optional<Claim>> claimAt(String world, int x, int z) {
        return database.supply(connection -> Jdbc.one(connection,
                "SELECT * FROM cr_claims WHERE world = ? AND min_x <= ? AND max_x >= ? "
                        + "AND min_z <= ? AND max_z >= ? LIMIT 1",
                WorldRepository::mapClaim, world, x, x, z, z));
    }

    public CompletableFuture<Boolean> deleteClaim(long id, UUID owner) {
        return database.supply(connection -> Jdbc.update(connection,
                "DELETE FROM cr_claims WHERE id = ? AND owner = ?", id, owner) > 0);
    }

    public CompletableFuture<Boolean> forceDeleteClaim(long id) {
        return database.supply(connection -> Jdbc.update(connection,
                "DELETE FROM cr_claims WHERE id = ?", id) > 0);
    }

    public CompletableFuture<Boolean> extendClaim(long id, long paidUntil) {
        return database.supply(connection -> Jdbc.update(connection,
                "UPDATE cr_claims SET paid_until = ?, last_tax_run = ? WHERE id = ?",
                paidUntil, System.currentTimeMillis(), id) > 0);
    }

    public CompletableFuture<Void> stampTaxRun(long id) {
        return database.run(connection -> Jdbc.update(connection,
                "UPDATE cr_claims SET last_tax_run = ? WHERE id = ?", System.currentTimeMillis(), id));
    }

    // ------------------------------------------------------------ chest shops

    public CompletableFuture<ChestShop> createShop(UUID owner, String world, int x, int y, int z,
                                                   byte[] item, int amount, double price, int stock) {
        return database.supply(connection -> {
            long id = Jdbc.insert(connection,
                    "INSERT INTO cr_chest_shops (owner, world, x, y, z, item, amount, price, stock) "
                            + "VALUES (?,?,?,?,?,?,?,?,?)",
                    owner, world, x, y, z, item, amount, price, stock);
            return new ChestShop(id, owner, world, x, y, z, item, amount, price, stock);
        });
    }

    public CompletableFuture<List<ChestShop>> shops() {
        return database.supply(connection -> Jdbc.list(connection,
                "SELECT * FROM cr_chest_shops", WorldRepository::mapShop));
    }

    public CompletableFuture<Optional<ChestShop>> shopAt(String world, int x, int y, int z) {
        return database.supply(connection -> Jdbc.one(connection,
                "SELECT * FROM cr_chest_shops WHERE world = ? AND x = ? AND y = ? AND z = ? LIMIT 1",
                WorldRepository::mapShop, world, x, y, z));
    }

    public CompletableFuture<List<ChestShop>> shops(UUID owner) {
        return database.supply(connection -> Jdbc.list(connection,
                "SELECT * FROM cr_chest_shops WHERE owner = ?", WorldRepository::mapShop, owner));
    }

    public CompletableFuture<Boolean> setStock(long id, int stock) {
        return database.supply(connection -> Jdbc.update(connection,
                "UPDATE cr_chest_shops SET stock = ? WHERE id = ?", stock, id) > 0);
    }

    public CompletableFuture<Boolean> deleteShop(long id) {
        return database.supply(connection -> Jdbc.update(connection,
                "DELETE FROM cr_chest_shops WHERE id = ?", id) > 0);
    }

    // -------------------------------------------------------------- treasures

    public CompletableFuture<Treasure> createTreasure(String world, double x, double y, double z,
                                                      String clue, int shards) {
        return database.supply(connection -> {
            long id = Jdbc.insert(connection,
                    "INSERT INTO cr_treasures (world, x, y, z, clue, shards, found_by, found_at) "
                            + "VALUES (?,?,?,?,?,?,NULL,0)",
                    world, x, y, z, clue, shards);
            return new Treasure(id, world, x, y, z, clue, shards, null, 0L);
        });
    }

    public CompletableFuture<List<Treasure>> treasures(boolean includeFound) {
        String sql = includeFound ? "SELECT * FROM cr_treasures ORDER BY id DESC"
                : "SELECT * FROM cr_treasures WHERE found_by IS NULL ORDER BY id DESC";
        return database.supply(connection -> Jdbc.list(connection, sql, WorldRepository::mapTreasure));
    }

    public CompletableFuture<Optional<Treasure>> treasureAt(String world, int x, int y, int z) {
        return database.supply(connection -> Jdbc.one(connection,
                "SELECT * FROM cr_treasures WHERE world = ? AND found_by IS NULL "
                        + "AND ROUND(x) = ? AND ROUND(y) = ? AND ROUND(z) = ? LIMIT 1",
                WorldRepository::mapTreasure, world, (double) x, (double) y, (double) z));
    }

    public CompletableFuture<Boolean> markFound(long id, UUID finder) {
        return database.supply(connection -> Jdbc.update(connection,
                "UPDATE cr_treasures SET found_by = ?, found_at = ? WHERE id = ? AND found_by IS NULL",
                finder, System.currentTimeMillis(), id) > 0);
    }

    // ----------------------------------------------------------------- mappers

    private static Claim mapClaim(ResultSet row) throws SQLException {
        return new Claim(row.getLong("id"),
                UUID.fromString(row.getString("owner")),
                row.getString("world"),
                row.getInt("min_x"), row.getInt("min_z"),
                row.getInt("max_x"), row.getInt("max_z"),
                row.getLong("created_at"),
                row.getLong("paid_until"),
                row.getLong("last_tax_run"));
    }

    private static ChestShop mapShop(ResultSet row) throws SQLException {
        return new ChestShop(row.getLong("id"),
                UUID.fromString(row.getString("owner")),
                row.getString("world"),
                row.getInt("x"), row.getInt("y"), row.getInt("z"),
                row.getBytes("item"),
                row.getInt("amount"),
                row.getDouble("price"),
                row.getInt("stock"));
    }

    private static Treasure mapTreasure(ResultSet row) throws SQLException {
        return new Treasure(row.getLong("id"),
                row.getString("world"),
                row.getDouble("x"), row.getDouble("y"), row.getDouble("z"),
                row.getString("clue"),
                row.getInt("shards"),
                Jdbc.uuid(row, "found_by"),
                row.getLong("found_at"));
    }
}
