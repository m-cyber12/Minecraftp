package io.craftrealm.core.util;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Token bucket used to stop transaction spam (and the packet floods that come
 * with it) from turning into database pressure.
 *
 * <p>One bucket per key, refilled at {@code permitsPerSecond}. Idle buckets are
 * pruned so a busy server does not leak a map entry per player forever.</p>
 */
public final class RateLimiter {

    private static final class Bucket {
        private double tokens;
        private long lastRefill;

        private Bucket(double tokens, long now) {
            this.tokens = tokens;
            this.lastRefill = now;
        }
    }

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final double capacity;
    private final double permitsPerSecond;
    private volatile long lastPrune = System.currentTimeMillis();

    public RateLimiter(double permitsPerSecond) {
        this(permitsPerSecond, Math.max(1.0, permitsPerSecond));
    }

    public RateLimiter(double permitsPerSecond, double capacity) {
        this.permitsPerSecond = Math.max(0.1, permitsPerSecond);
        this.capacity = Math.max(1.0, capacity);
    }

    /** @return {@code true} when the action may proceed */
    public boolean tryAcquire(String key) {
        return tryAcquire(key, 1.0);
    }

    public boolean tryAcquire(String key, double permits) {
        long now = System.currentTimeMillis();
        pruneIfNeeded(now);
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, now));
        synchronized (bucket) {
            double elapsedSeconds = (now - bucket.lastRefill) / 1000.0;
            bucket.lastRefill = now;
            bucket.tokens = Math.min(capacity, bucket.tokens + elapsedSeconds * permitsPerSecond);
            if (bucket.tokens < permits) {
                return false;
            }
            bucket.tokens -= permits;
            return true;
        }
    }

    /** Drops a bucket, e.g. when a player disconnects. */
    public void forget(String key) {
        buckets.remove(key);
    }

    private void pruneIfNeeded(long now) {
        if (now - lastPrune < TimeUnit.MINUTES.toMillis(5)) {
            return;
        }
        lastPrune = now;
        buckets.entrySet().removeIf(entry -> now - entry.getValue().lastRefill > TimeUnit.MINUTES.toMillis(10));
    }
}
