package io.craftrealm.economy.listener;

import io.craftrealm.api.service.EconomyService;
import io.craftrealm.core.text.Messages;
import io.craftrealm.core.util.Scheduler;
import io.craftrealm.economy.bounty.BountyService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

/** Pays bounties to the killer. */
public final class BountyListener implements Listener {

    private final BountyService bounties;
    private final Messages messages;
    private final EconomyService economy;

    public BountyListener(BountyService bounties, Messages messages, EconomyService economy) {
        this.bounties = bounties;
        this.messages = messages;
        this.economy = economy;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }
        bounties.claim(killer.getUniqueId(), victim.getUniqueId()).thenAccept(total -> {
            if (total <= 0) {
                return;
            }
            Scheduler.sync(() -> {
                String formatted = economy.format(total);
                messages.send(killer, "bounty.claimed", "amount", formatted, "player", victim.getName());
                messages.broadcast("bounty.claimed-broadcast", "player", victim.getName(),
                        "killer", killer.getName(), "amount", formatted);
            });
        });
    }
}
