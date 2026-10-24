package io.craftrealm.api.model;

/** Per-world counters shown on the Server Health page. */
public record WorldHealth(String world, int chunks, int entities, int players) {
}
