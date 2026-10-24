package io.craftrealm.api.model;

import java.util.UUID;

/** One staff action, recorded forever. */
public record AuditEntry(long id, UUID staff, String staffName, String action, String target,
                         String detail, long createdAt) {
}
