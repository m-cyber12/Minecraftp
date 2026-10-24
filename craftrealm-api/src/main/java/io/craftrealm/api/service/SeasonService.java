package io.craftrealm.api.service;

import io.craftrealm.api.model.BalanceEntry;
import io.craftrealm.api.model.SeasonInfo;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 30-day leaderboard seasons with rewards and titles.
 */
public interface SeasonService {

    CompletableFuture<SeasonInfo> current();

    CompletableFuture<List<SeasonInfo>> history(int limit);

    /** Leaderboard for the running season (score = Crowns earned, not balance). */
    CompletableFuture<List<BalanceEntry>> leaderboard(int limit);

    CompletableFuture<Integer> addScore(UUID uuid, long amount);

    /** Scheduled: ends the season when due, pays rewards, opens the next one. */
    CompletableFuture<Boolean> tick();

    /** Title granted for a finishing position, e.g. {@code "Guildmaster"}. */
    String titleForRank(int rank);

    /** Rank of a player in the running season, {@code 0} if unranked. */
    CompletableFuture<Integer> rank(UUID uuid);
}
