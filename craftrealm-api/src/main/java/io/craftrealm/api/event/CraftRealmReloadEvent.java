package io.craftrealm.api.event;

import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired after {@code /craftrealm reload} finished applying the new settings. */
public class CraftRealmReloadEvent extends CraftRealmEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
