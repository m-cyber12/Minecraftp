package io.craftrealm.api.event;

import org.bukkit.event.Event;

/**
 * Base class for every CraftRealm event.
 *
 * <p>Money related events are fired <b>synchronously on the main thread</b> after
 * the database write has been committed, so listeners can safely touch the
 * Bukkit API.</p>
 */
public abstract class CraftRealmEvent extends Event {

    protected CraftRealmEvent() {
    }

    /** Convenience for plugins that fire CraftRealm events themselves in tests. */
    protected CraftRealmEvent(boolean isAsync) {
        super(isAsync);
    }
}
