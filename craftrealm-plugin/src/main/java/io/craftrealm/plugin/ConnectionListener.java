package io.craftrealm.plugin;

import io.craftrealm.core.repo.PlayerRepository;
import io.craftrealm.core.util.Scheduler;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Primes and drops the per-player caches.
 *
 * <p>Everything here is asynchronous: the join handler returns immediately and the
 * wallet, bank, job and quest caches fill in a few milliseconds later. Commands read
 * the cache, so a player who types {@code /bal} the instant they spawn sees their
 * balance rather than an error.</p>
 */
public final class ConnectionListener implements Listener {

    private final CraftRealmPlugin plugin;

    public ConnectionListener(CraftRealmPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        var uuid = player.getUniqueId();
        String name = player.getName();

        plugin.economyService().loadAccount(uuid, name);
        plugin.jobService().load(uuid);
        plugin.eventModule().cosmetics().load(uuid);
        plugin.eventModule().quests().ensure(uuid);
        plugin.eventModule().seasons().addScore(uuid, 0);

        new PlayerRepository(plugin.database()).touch(uuid, name);

        // A short delayed sync pass: cosmetic prefixes need the cache warm before chat.
        Scheduler.syncLater(() -> plugin.eventModule().cosmetics().load(uuid), 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        var uuid = event.getPlayer().getUniqueId();
        plugin.economyService().unloadAccount(uuid);
        plugin.jobService().unload(uuid);
        plugin.eventModule().cosmetics().unload(uuid);
    }
}
