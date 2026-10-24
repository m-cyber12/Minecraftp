package io.craftrealm.events.gui;

import io.craftrealm.api.service.EventService;
import io.craftrealm.core.text.Messages;
import io.craftrealm.core.ui.Gui;
import io.craftrealm.core.ui.GuiTheme;
import io.craftrealm.core.ui.ItemBuilder;
import io.craftrealm.core.util.Scheduler;
import io.craftrealm.events.world.EventServiceImpl;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * The events board.
 *
 * <p>Everyone sees what is running and how long is left; staff with the admin
 * permission can also start an event with a click, which is how a livestream
 * "Gold Rush in five minutes" moment is actually delivered.</p>
 */
public final class EventsGui extends Gui {

    private final EventService events;
    private final Messages messages;

    public EventsGui(EventService events, Messages messages, GuiTheme theme, Player player) {
        super(player, theme, 5, messages.get("events.gui.title"));
        this.events = events;
        this.messages = messages;
    }

    @Override
    public void render() {
        int slot = 10;
        for (String id : events.registered()) {
            if (slot >= 25) {
                break;
            }
            boolean active = events.isActive(id);
            set(slot, ItemBuilder.of(icon(id))
                    .name(messages.raw("events.gui.entry.name", "event", id))
                    .lore(lore(id, active))
                    .glint(active)
                    .build(), click -> {
                if (!viewer.hasPermission("craftrealm.events.admin")) {
                    messages.send(viewer, "events.gui.no-permission");
                    return;
                }
                if (active) {
                    events.stop(id).thenAccept(done -> Scheduler.sync(() -> {
                        messages.send(viewer, "events.stopped", "event", id);
                        open();
                    }));
                    return;
                }
                events.trigger(id).thenAccept(started -> Scheduler.sync(() -> {
                    messages.send(viewer, started ? "events.started" : "events.failed", "event", id);
                    theme().play(viewer, started ? GuiTheme.Click.CONFIRM : GuiTheme.Click.DENY);
                    open();
                }));
            });
            slot += 2;
        }

        set(4, ItemBuilder.of(Material.CLOCK)
                .name(messages.raw("events.gui.header"))
                .lore(messages.bundle().lines("events.gui.header-lore",
                        "active", String.join(", ", events.activeCached())))
                .build());

        backButton(() -> viewer.closeInventory());
        closeButton();
        fillEmpty();
    }

    private List<Component> lore(String id, boolean active) {
        List<Component> lines = new ArrayList<>(messages.bundle().lines("events.gui.entry.lore." + id,
                "state", active ? "running" : "idle"));
        if (active && events instanceof EventServiceImpl impl) {
            lines.add(messages.get("events.gui.remaining", "time", impl.remainingLabel(id)));
        }
        return lines;
    }

    private static Material icon(String id) {
        return switch (id) {
            case EventServiceImpl.GOLD_RUSH -> Material.GOLD_ORE;
            case EventServiceImpl.BLOOD_MOON -> Material.REDSTONE_BLOCK;
            case EventServiceImpl.MARKET_CRASH -> Material.ANVIL;
            case EventServiceImpl.CARAVAN -> Material.VILLAGER_SPAWN_EGG;
            default -> Material.CLOCK;
        };
    }
}
