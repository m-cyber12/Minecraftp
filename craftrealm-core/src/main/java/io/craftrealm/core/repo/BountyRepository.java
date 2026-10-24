package io.craftrealm.core.repo;

import io.craftrealm.api.model.Bounty;
import io.craftrealm.api.model.BountyStatus;
import io.craftrealm.core.db.Database;
import io.craftrealm.core.db.Jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Bounties: escrowed at placement, claimed exactly once. */
public final class BountyRepository {

    private final Database database;

    public BountyRepository(Database database) {
        this.database = database;
    }

    public CompletableFuture<Bounty> create(UUID target, String targetName, UUID placer, double amount) {
        return database.supply(connection -> {
            long now = System.currentTimeMillis();
            long id = Jdbc.insert(connection,
                    "INSERT INTO cr_bounties (target, target_name, placer, amount, created_at, status) "
                            + "VALUES (?,?,?,?,?,?)",
                    target, targetName, placer, amount, now, BountyStatus.OPEN);
            return new Bounty(id, target, targetName, placer, amount, now, BountyStatus.OPEN);
        });
    }

    public CompletableFuture<List<Bounty>> open(int limit) {
        return database.supply(connection -> Jdbc.list(connection,
                "SELECT * FROM cr_bounties WHERE status = ? ORDER BY amount DESC LIMIT ?",
                BountyRepository::map, BountyStatus.OPEN, Math.max(1, limit)));
    }

    public CompletableFuture<List<Bounty>> openFor(UUID target) {
        return database.supply(connection -> Jdbc.list(connection,
                "SELECT * FROM cr_bounties WHERE status = ? AND target = ? ORDER BY amount DESC",
                BountyRepository::map, BountyStatus.OPEN, target));
    }

    public CompletableFuture<Double> totalFor(UUID target) {
        return database.supply(connection -> Jdbc.scalarDouble(connection,
                "SELECT COALESCE(SUM(amount), 0) FROM cr_bounties WHERE status = ? AND target = ?",
                BountyStatus.OPEN, target));
    }

    public CompletableFuture<Optional<Bounty>> find(long id) {
        return database.supply(connection -> Jdbc.one(connection,
                "SELECT * FROM cr_bounties WHERE id = ?", BountyRepository::map, id));
    }

    /** Conditional claim — only one killer can cash a bounty. */
    public CompletableFuture<Boolean> claim(long id) {
        return database.supply(connection -> Jdbc.update(connection,
                "UPDATE cr_bounties SET status = ? WHERE id = ? AND status = ?",
                BountyStatus.CLAIMED, id, BountyStatus.OPEN) > 0);
    }

    public CompletableFuture<Boolean> cancel(long id) {
        return database.supply(connection -> Jdbc.update(connection,
                "UPDATE cr_bounties SET status = ? WHERE id = ? AND status = ?",
                BountyStatus.CANCELLED, id, BountyStatus.OPEN) > 0);
    }

    private static Bounty map(ResultSet row) throws SQLException {
        return new Bounty(row.getLong("id"),
                UUID.fromString(row.getString("target")),
                row.getString("target_name"),
                UUID.fromString(row.getString("placer")),
                row.getDouble("amount"),
                row.getLong("created_at"),
                BountyStatus.valueOf(row.getString("status")));
    }
}
