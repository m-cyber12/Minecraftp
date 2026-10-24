package io.craftrealm.api.model;

import java.util.UUID;

/** A free-text staff note attached to a player. */
public record PlayerNote(long id, UUID target, UUID staff, String text, long createdAt) {
}
