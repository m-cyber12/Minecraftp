package io.craftrealm.events.treasure;

import io.craftrealm.api.model.Treasure;
import io.craftrealm.api.service.ShardService;
import io.craftrealm.core.config.Settings;
import io.craftrealm.core.repo.WorldRepository;
import io.craftrealm.core.text.Messages;
import io.craftrealm.core.util.Scheduler;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Admin-hidden treasure chests that pay Shards to the first finder.
 *
 * <p>Finding is claimed with a conditional database update, so two players opening
 * the chest in the same tick cannot both be paid. The chest is then refilled with a
 * small consolation prize and kept as a memento, which makes screenshots nicer and
 * tells other hunters that this one is gone.</p>
 */
public final class TreasureService {

    private final WorldRepository repository;
    private final ShardService shards;
    private final Messages messages;
    private final Settings.TreasureSettings settings;

    private final java.util.Map<String, Treasure> cache = new ConcurrentHashMap<>();

    public TreasureService(WorldRepository repository, ShardService shards, Messages messages,
                           Settings.TreasureSettings settings) {
        this.repository = repository;
        this.shards = shards;
        this.messages = messages;
        this.settings = settings;
    }

    public CompletableFuture<Void> warmUp() {
        return repository.treasures(false).thenAccept(list -> {
            cache.clear();
            list.forEach(treasure -> cache.put(key(treasure.world(), treasure.x(), treasure.y(),
                    treasure.z()), treasure));
        });
    }

    /** Hides a new treasure at the given block. */
    public CompletableFuture<Treasure> hide(Location location, String clue, int rewards) {
        return repository.createTreasure(location.getWorld().getName(), location.getX(), location.getY(),
                        location.getZ(), clue, rewards <= 0 ? settings.defaultShards() : rewards)
                .thenApply(treasure -> {
                    cache.put(key(treasure.world(), treasure.x(), treasure.y(), treasure.z()), treasure);
                    return treasure;
                });
    }

    public CompletableFuture<List<Treasure>> active() {
        return repository.treasures(false);
    }

    public List<Treasure> cachedActive() {
        return List.copyOf(cache.values());
    }

    public int cachedCount() {
        return cache.size();
    }

    /** Attempts to claim the treasure at a block; returns the paid amount (0 if none). */
    public CompletableFuture<Integer> claim(Player player, Location location) {
        String key = key(location.getWorld().getName(), location.getBlockX(), location.getBlockY(),
                location.getBlockZ());
        Treasure cached = cache.get(key);
        if (cached == null) {
            return CompletableFuture.completedFuture(0);
        }
        UUID finder = player.getUniqueId();
        return repository.markFound(cached.id(), finder).thenCompose(found -> {
            if (!found) {
                cache.remove(key);
                return CompletableFuture.completedFuture(0);
            }
            cache.remove(key);
            return shards.add(finder, cached.shards(), "treasure " + cached.id())
                    .thenApply(balance -> {
                        Scheduler.sync(() -> {
                            messages.send(player, "treasure.found", "shards", String.valueOf(cached.shards()));
                            if (settings.announce()) {
                                messages.broadcast("treasure.broadcast", "player", player.getName(),
                                        "shards", String.valueOf(cached.shards()));
                            }
                        });
                        return cached.shards();
                    });
        });
    }

    /** Clue for the nearest unclaimed treasure, used by {@code /treasure hint}. */
    public Optional<Treasure> nearest(Location location) {
        Treasure best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Treasure treasure : cache.values()) {
            if (!treasure.world().equals(location.getWorld().getName())) {
                continue;
            }
            double distance = treasure.x() == 0 && treasure.z() == 0 ? Double.MAX_VALUE
                    : location.distanceSquared(new Location(location.getWorld(), treasure.x(), treasure.y(),
                    treasure.z()));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = treasure;
            }
        }
        return Optional.ofNullable(best);
    }

    public Settings.TreasureSettings settings() {
        return settings;
    }

    private static String key(String world, double x, double y, double z) {
        return world + ':' + (int) Math.floor(x) + ':' + (int) Math.floor(y) + ':' + (int) Math.floor(z);
    }
}
