package io.craftrealm.api.model;

import java.util.UUID;

/**
 * A recorded punishment.
 *
 * @param expiresAt {@code 0} for permanent entries
 */
public record Punishment(long id, UUID target, PunishmentType type, UUID staff, String reason,
                         long createdAt, long expiresAt, boolean active) {

    public boolean permanent() {
        return expiresAt <= 0L;
    }

    public boolean expired(long now) {
        return !permanent() && now >= expiresAt;
    }
}
