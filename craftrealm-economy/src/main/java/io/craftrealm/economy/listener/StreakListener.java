package io.craftrealm.economy.listener;

import io.craftrealm.core.util.Scheduler;
import io.craftrealm.economy.streak.StreakService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Kicks off the daily streak check a moment after a player joins. */
public final class StreakListener implements Listener {

    private final StreakService streaks;

    public StreakListener(StreakService streaks) {
        this.streaks = streaks;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        // Small delay so the account row and the balance cache exist first.
        Scheduler.syncLater(() -> streaks.handleJoin(event.getPlayer()), 20L);
    }
}
