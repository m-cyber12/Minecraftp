package io.craftrealm.api.service;

import org.bukkit.Material;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The random world-event engine: Gold Rush, Merchant Caravan, Blood Moon and
 * Market Crash.
 */
public interface EventService {

    /** Ids of the events currently running. */
    List<String> activeCached();

    CompletableFuture<List<String>> active();

    boolean isActive(String id);

    /** Starts an event by id; {@code false} if unknown or already running. */
    CompletableFuture<Boolean> trigger(String id);

    CompletableFuture<Boolean> stop(String id);

    /** Sell multiplier for a material, e.g. 2.0 during a Gold Rush. */
    double sellMultiplier(Material material);

    /** Mob drop multiplier, e.g. 2.0 during a Blood Moon. */
    double mobDropMultiplier();

    /** Every registered event id. */
    List<String> registered();
}
