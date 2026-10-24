package io.craftrealm.api.model;

import java.util.UUID;

/** A player report sitting in the staff review queue. */
public record Report(long id, UUID reporter, String reporterName, UUID target, String targetName,
                     String reason, ReportStatus status, long createdAt,
                     UUID handledBy, long handledAt, String resolution) {
}
