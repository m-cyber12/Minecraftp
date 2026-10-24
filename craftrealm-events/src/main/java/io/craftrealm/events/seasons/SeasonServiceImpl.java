package io.craftrealm.events.seasons;

import io.craftrealm.api.model.BalanceEntry;
import io.craftrealm.api.model.SeasonInfo;
import io.craftrealm.api.model.TransactionType;
import io.craftrealm.api.service.EconomyService;
import io.craftrealm.api.service.SeasonService;
import io.craftrealm.api.service.ShardService;
import io.craftrealm.core.config.Settings;
import io.craftrealm.core.repo.ProgressionRepository;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Thirty-day leaderboard seasons.
 *
 * <p>Score is <b>earned</b> Crowns (every source-type transaction), not the current
 * balance — otherwise the richest player would win every season by doing nothing.</p>
 */
public final class SeasonServiceImpl implements SeasonService {

    private static final long DAY = 86_400_000L;

    private final ProgressionRepository repository;
    private final EconomyService economy;
    private final ShardService shards;
    private final Settings.SeasonSettings settings;

    public SeasonServiceImpl(ProgressionRepository repository, EconomyService economy,
                             ShardService shards, Settings.SeasonSettings settings) {
        this.repository = repository;
        this.economy = economy;
        this.shards = shards;
        this.settings = settings;
    }

    /** Makes sure a season is running; called on enable and by the daily task. */
    public CompletableFuture<SeasonInfo> ensureRunning() {
        return repository.latestSeason().thenCompose(optional -> {
            long now = System.currentTimeMillis();
            if (optional.isEmpty()) {
                return repository.createSeason(1, now, now + length());
            }
            SeasonInfo latest = optional.get();
            if (latest.endsAt() <= now) {
                return close(latest).thenCompose(ignored ->
                        repository.createSeason(latest.number() + 1, now, now + length()));
            }
            return CompletableFuture.completedFuture(latest);
        });
    }

    @Override
    public CompletableFuture<SeasonInfo> current() {
        return ensureRunning();
    }

    @Override
    public CompletableFuture<List<SeasonInfo>> history(int limit) {
        return repository.seasonHistory(limit);
    }

    @Override
    public CompletableFuture<List<BalanceEntry>> leaderboard(int limit) {
        return repository.latestSeason().thenCompose(optional -> {
            if (optional.isEmpty()) {
                return CompletableFuture.completedFuture(List.of());
            }
            return repository.leaderboard(optional.get().number(), limit);
        });
    }

    @Override
    public CompletableFuture<Integer> addScore(UUID uuid, long amount) {
        if (amount <= 0 || !settings.enabled()) {
            return CompletableFuture.completedFuture(0);
        }
        return repository.latestSeason().thenCompose(optional -> {
            if (optional.isEmpty()) {
                return CompletableFuture.completedFuture(0);
            }
            SeasonInfo season = optional.get();
            return repository.addScore(season.number(), uuid,
                    io.craftrealm.core.CraftRealmCore.nameOf(uuid), amount)
                    .thenCompose(ignored -> repository.rank(season.number(), uuid));
        });
    }

    @Override
    public CompletableFuture<Boolean> tick() {
        if (!settings.enabled()) {
            return CompletableFuture.completedFuture(false);
        }
        long now = System.currentTimeMillis();
        return repository.latestSeason().thenCompose(optional -> {
            if (optional.isEmpty()) {
                return ensureRunning().thenApply(ignored -> true);
            }
            SeasonInfo latest = optional.get();
            if (latest.endsAt() > now) {
                return CompletableFuture.completedFuture(false);
            }
            return close(latest).thenCompose(ignored ->
                    repository.createSeason(latest.number() + 1, now, now + length()))
                    .thenApply(ignored -> true);
        });
    }

    /** Pays the configured rewards and closes the season. */
    private CompletableFuture<Void> close(SeasonInfo season) {
        return repository.leaderboard(season.number(), Math.max(1, settings.rewards().size()))
                .thenCompose(entries -> {
                    List<CompletableFuture<?>> payouts = new java.util.ArrayList<>();
                    int rank = 1;
                    for (BalanceEntry entry : entries) {
                        Settings.SeasonReward reward = rewardFor(rank);
                        if (reward != null) {
                            if (reward.crowns() > 0) {
                                payouts.add(economy.deposit(entry.uuid(), reward.crowns(),
                                        TransactionType.SEASON_REWARD,
                                        "season " + season.number() + " rank " + rank));
                            }
                            if (reward.shards() > 0) {
                                payouts.add(shards.add(entry.uuid(), reward.shards(),
                                        "season " + season.number() + " rank " + rank));
                            }
                        }
                        rank++;
                    }
                    BalanceEntry winner = entries.isEmpty() ? null : entries.get(0);
                    return CompletableFuture.allOf(payouts.toArray(CompletableFuture[]::new))
                            .thenCompose(ignored -> repository.closeSeason(season.number(),
                                    winner == null ? null : winner.uuid(),
                                    winner == null ? null : winner.name(),
                                    winner == null ? 0 : (long) winner.balance()));
                });
    }

    @Override
    public String titleForRank(int rank) {
        Settings.SeasonReward reward = rewardFor(rank);
        return reward == null ? null : reward.title();
    }

    @Override
    public CompletableFuture<Integer> rank(UUID uuid) {
        return repository.latestSeason().thenCompose(optional -> optional
                .map(season -> repository.rank(season.number(), uuid))
                .orElseGet(() -> CompletableFuture.completedFuture(0)));
    }

    private Settings.SeasonReward rewardFor(int rank) {
        for (Settings.SeasonReward reward : settings.rewards()) {
            if (reward.rank() == rank) {
                return reward;
            }
        }
        return null;
    }

    private long length() {
        return Math.max(1, settings.lengthDays()) * DAY;
    }

    /** Announcement text for the closing ceremony. */
    public String seasonLabel(SeasonInfo season) {
        return "Season " + season.number();
    }
}
