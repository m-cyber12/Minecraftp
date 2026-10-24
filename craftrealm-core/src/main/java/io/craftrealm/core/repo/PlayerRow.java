package io.craftrealm.core.repo;

import java.util.UUID;

/** The general-purpose player record: presence, moderation state and streaks. */
public record PlayerRow(UUID uuid, String name, long firstSeen, long lastSeen, long playtime,
                        boolean frozen, long mutedUntil, String lastIp, int streak, long lastStreakDay) {
}
