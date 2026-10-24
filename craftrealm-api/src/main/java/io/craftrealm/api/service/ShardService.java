package io.craftrealm.api.service;

import io.craftrealm.api.model.ShardEntry;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Shards — the premium currency.
 *
 * <p>Shards are never sold and never converted to Crowns; they are only granted
 * by achievements, events, treasure hunts and season rewards. That keeps the
 * premium economy free of pay-to-win pressure.</p>
 */
public interface ShardService {

    String format(long amount);

    long cached(UUID uuid);

    CompletableFuture<Long> balance(UUID uuid);

    CompletableFuture<Long> add(UUID uuid, long amount, String reason);

    /** Fails (returns {@code false}) when the player cannot afford it. */
    CompletableFuture<Boolean> take(UUID uuid, long amount, String reason);

    CompletableFuture<List<ShardEntry>> top(int limit);
}
