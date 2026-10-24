package io.craftrealm.api.model;

import java.util.UUID;

/**
 * A read-only projection of a player used by the Control Room and the web
 * dashboard. Built on the main thread, then consumed anywhere.
 */
public record PlayerView(UUID uuid, String name, boolean online, double balance, long shards,
                         int ping, String world, String gameMode, long lastSeen,
                         boolean frozen, boolean muted) {
}
