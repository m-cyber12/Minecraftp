package io.craftrealm.core.economy;

import io.craftrealm.api.event.ShardChangeEvent;
import io.craftrealm.api.model.ShardEntry;
import io.craftrealm.api.service.ShardService;
import io.craftrealm.core.db.Database;
import io.craftrealm.core.repo.AccountRepository;
import io.craftrealm.core.util.Events;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shards — the premium currency earned through achievements, events and treasure
 * hunts. Deliberately not purchasable and not convertible to Crowns.
 */
public final class ShardServiceImpl implements ShardService {

    private final AccountRepository accounts;
    private final Map<UUID, Long> cache = new ConcurrentHashMap<>();

    public ShardServiceImpl(Database database) {
        this.accounts = new AccountRepository(database);
    }

    @Override
    public String format(long amount) {
        return amount + " ✦";
    }

    @Override
    public long cached(UUID uuid) {
        return cache.getOrDefault(uuid, 0L);
    }

    @Override
    public CompletableFuture<Long> balance(UUID uuid) {
        return accounts.shards(uuid);
    }

    /** Caches the Shard balance after a successful account load. */
    public void prime(UUID uuid) {
        accounts.shards(uuid).thenAccept(value -> cache.put(uuid, value));
    }

    public void unload(UUID uuid) {
        cache.remove(uuid);
    }

    @Override
    public CompletableFuture<Long> add(UUID uuid, long amount, String reason) {
        if (amount <= 0) {
            return CompletableFuture.completedFuture(cached(uuid));
        }
        long before = cached(uuid);
        return accounts.addShards(uuid, amount).thenApply(after -> {
            cache.put(uuid, after);
            Events.call(new ShardChangeEvent(uuid, before, after, reason));
            return after;
        });
    }

    @Override
    public CompletableFuture<Boolean> take(UUID uuid, long amount, String reason) {
        if (amount <= 0) {
            return CompletableFuture.completedFuture(false);
        }
        long before = cached(uuid);
        return accounts.takeShards(uuid, amount).thenApply(taken -> {
            if (taken) {
                long after = Math.max(0L, before - amount);
                cache.put(uuid, after);
                Events.call(new ShardChangeEvent(uuid, before, after, reason));
            }
            return taken;
        });
    }

    @Override
    public CompletableFuture<List<ShardEntry>> top(int limit) {
        return accounts.topShards(limit);
    }
}
