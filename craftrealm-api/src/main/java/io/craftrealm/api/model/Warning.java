package io.craftrealm.api.model;

import java.util.UUID;

/** A warning note attached to a player, used by the auto-escalation ladder. */
public record Warning(long id, UUID target, UUID staff, String reason, long createdAt, boolean active) {
}
