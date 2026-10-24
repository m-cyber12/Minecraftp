package io.craftrealm.api.model;

import java.util.UUID;

/**
 * A 30-day leaderboard season.
 *
 * @param number     sequential season number, starting at 1
 * @param startedAt  epoch millis
 * @param endsAt     epoch millis
 * @param winner     winner of the previous season, {@code null} while running
 * @param winnerName winner display name
 * @param winnerScore winning score
 */
public record SeasonInfo(int number, long startedAt, long endsAt, UUID winner,
                         String winnerName, long winnerScore) {

    public long remainingMillis() {
        return Math.max(0L, endsAt - System.currentTimeMillis());
    }
}
