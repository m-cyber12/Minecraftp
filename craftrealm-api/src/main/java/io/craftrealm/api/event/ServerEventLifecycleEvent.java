package io.craftrealm.api.event;

import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired when a world event (Gold Rush, Blood Moon, …) starts or stops. */
public class ServerEventLifecycleEvent extends CraftRealmEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    public enum Phase { START, END }

    private final String eventId;
    private final Phase phase;

    public ServerEventLifecycleEvent(@NotNull String eventId, @NotNull Phase phase) {
        this.eventId = eventId;
        this.phase = phase;
    }

    public @NotNull String eventId() {
        return eventId;
    }

    public @NotNull Phase phase() {
        return phase;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
