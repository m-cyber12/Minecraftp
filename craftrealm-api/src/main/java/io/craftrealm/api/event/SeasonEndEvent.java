package io.craftrealm.api.event;

import io.craftrealm.api.model.SeasonInfo;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired when a season closes, before the next one opens. */
public class SeasonEndEvent extends CraftRealmEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final SeasonInfo ended;

    public SeasonEndEvent(@NotNull SeasonInfo ended) {
        this.ended = ended;
    }

    public @NotNull SeasonInfo season() {
        return ended;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
