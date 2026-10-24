package io.craftrealm.economy.listener;

import org.bukkit.block.Block;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers blocks players placed recently so the Miner job cannot be farmed by
 * placing and re-breaking the same stone.
 *
 * <p>Bounded on purpose: at most 20k entries, pruned every few minutes, so a long
 * uptime cannot leak memory.</p>
 */
final class PlacedBlockTracker {

    private static final int MAX_ENTRIES = 20_000;
    private static final long TTL_MILLIS = 5 * 60_000L;

    private final Map<String, Long> placed = new ConcurrentHashMap<>();

    void remember(Block block) {
        if (placed.size() > MAX_ENTRIES) {
            prune();
        }
        placed.put(key(block), System.currentTimeMillis());
    }

    /** @return true when this exact block was placed by a player within the TTL */
    boolean wasPlayerPlaced(Block block) {
        Long stamp = placed.get(key(block));
        if (stamp == null) {
            return false;
        }
        if (System.currentTimeMillis() - stamp > TTL_MILLIS) {
            placed.remove(key(block));
            return false;
        }
        return true;
    }

    void forget(Block block) {
        placed.remove(key(block));
    }

    private void prune() {
        long cutoff = System.currentTimeMillis() - TTL_MILLIS;
        placed.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }

    private String key(Block block) {
        return block.getWorld().getName() + ':' + block.getX() + ':' + block.getY() + ':' + block.getZ();
    }
}
