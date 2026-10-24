package io.craftrealm.events.world;

import io.craftrealm.api.service.EventService;
import io.craftrealm.core.config.Settings;
import io.craftrealm.core.text.Messages;
import io.craftrealm.core.util.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The world-event engine.
 *
 * <p>Four events, each a pure multiplier or weather switch so the engine cannot
 * corrupt the economy: Gold Rush doubles sell prices for configured ores, Blood
 * Moon doubles mob drops, Market Crash halves every sell price, and the Merchant
 * Caravan spawns a trader with rare goods.</p>
 *
 * <p>Events never stack — starting one while another runs replaces it, which keeps
 * the numbers predictable.</p>
 */
public final class EventServiceImpl implements EventService {

    public static final String GOLD_RUSH = "gold_rush";
    public static final String BLOOD_MOON = "blood_moon";
    public static final String MARKET_CRASH = "market_crash";
    public static final String CARAVAN = "merchant_caravan";

    private final Settings.EventSettings settings;
    private final Messages messages;

    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> startedAt = new ConcurrentHashMap<>();

    public EventServiceImpl(Settings.EventSettings settings, Messages messages) {
        this.settings = settings;
        this.messages = messages;
    }

    @Override
    public List<String> activeCached() {
        return List.copyOf(active);
    }

    @Override
    public CompletableFuture<List<String>> active() {
        return CompletableFuture.completedFuture(activeCached());
    }

    @Override
    public boolean isActive(String id) {
        return id != null && active.contains(id.toLowerCase(Locale.ROOT));
    }

    @Override
    public CompletableFuture<Boolean> trigger(String id) {
        if (id == null || !registered().contains(id.toLowerCase(Locale.ROOT))) {
            return CompletableFuture.completedFuture(false);
        }
        String key = id.toLowerCase(Locale.ROOT);
        if (active.contains(key)) {
            return CompletableFuture.completedFuture(false);
        }
        stopAll();
        active.add(key);
        startedAt.put(key, System.currentTimeMillis());

        // trigger() can be reached from the API or the console on any thread, so the
        // world/chat side effects hop to the tick thread (sync() runs inline when the
        // caller already is on it).
        Scheduler.sync(() -> {
            switch (key) {
                case GOLD_RUSH -> announce("events.gold-rush.start");
                case BLOOD_MOON -> {
                    for (World world : Bukkit.getWorlds()) {
                        world.setTime(18_000);
                    }
                    announce("events.blood-moon.start");
                }
                case MARKET_CRASH -> announce("events.market-crash.start");
                case CARAVAN -> announce("events.caravan.start");
                default -> {
                }
            }
        });
        long started = System.currentTimeMillis();
        long durationMillis = Math.max(1, settings.durationMinutes()) * 60_000L;
        // A self-cancelling stop: if the event was stopped or replaced meanwhile the
        // start timestamp no longer matches and this runnable does nothing.
        Scheduler.syncLater(() -> {
            Long current = startedAt.get(key);
            if (current != null && current == started) {
                stop(key);
            }
        }, Math.max(20L, durationMillis / 50L));
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<Boolean> stop(String id) {
        String key = id == null ? null : id.toLowerCase(Locale.ROOT);
        if (key == null || !active.remove(key)) {
            return CompletableFuture.completedFuture(false);
        }
        startedAt.remove(key);
        announce("events.stop", "event", key);
        return CompletableFuture.completedFuture(true);
    }

    /** Ends whichever event is running; used when a new one starts. */
    public void stopAll() {
        for (String key : new ArrayList<>(active)) {
            stop(key);
        }
    }

    @Override
    public double sellMultiplier(Material material) {
        if (isActive(GOLD_RUSH) && material != null && settings.goldRushMaterials().contains(material)) {
            return settings.goldRushMultiplier();
        }
        if (isActive(MARKET_CRASH)) {
            return settings.marketCrashMultiplier();
        }
        return 1.0;
    }

    @Override
    public double mobDropMultiplier() {
        return isActive(BLOOD_MOON) ? settings.bloodMoonMultiplier() : 1.0;
    }

    @Override
    public List<String> registered() {
        return List.of(GOLD_RUSH, BLOOD_MOON, MARKET_CRASH, CARAVAN);
    }

    /** Human readable remaining time, for the events menu. */
    public String remainingLabel(String id) {
        Long start = startedAt.get(id);
        if (start == null) {
            return "-";
        }
        long endsAt = start + settings.durationMinutes() * 60_000L;
        long left = Math.max(0, endsAt - System.currentTimeMillis());
        return (left / 60_000) + "m " + ((left % 60_000) / 1000) + "s";
    }

    /** Scheduler entry point: roll for a random event. */
    public void maybeStartRandom() {
        if (!settings.enabled() || !settings.autoStart() || !active.isEmpty()) {
            return;
        }
        List<String> pool = settings.pool().isEmpty() ? registered() : settings.pool();
        String pick = pool.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(pool.size()));
        trigger(pick);
    }

    private void announce(String key, Object... placeholders) {
        if (settings.announce()) {
            messages.broadcast(key, placeholders);
        }
    }
}
