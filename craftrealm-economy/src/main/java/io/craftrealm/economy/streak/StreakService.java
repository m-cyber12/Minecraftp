package io.craftrealm.economy.streak;

import io.craftrealm.api.model.TransactionType;
import io.craftrealm.api.service.EconomyService;
import io.craftrealm.api.service.ShardService;
import io.craftrealm.core.config.Settings;
import io.craftrealm.core.repo.PlayerRepository;
import io.craftrealm.core.text.Messages;
import io.craftrealm.core.util.Money;
import io.craftrealm.core.util.Scheduler;
import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;

/**
 * Daily login streaks.
 *
 * <p>The reward grows geometrically and is capped, which rewards loyalty without
 * turning alt accounts into a money printer. Milestone days pay Shards instead of
 * Crowns.</p>
 */
public final class StreakService {

    private final PlayerRepository players;
    private final EconomyService economy;
    private final ShardService shards;
    private final Settings.StreakSettings settings;
    private final Messages messages;

    public StreakService(PlayerRepository players, EconomyService economy, ShardService shards,
                         Settings.StreakSettings settings, Messages messages) {
        this.players = players;
        this.economy = economy;
        this.shards = shards;
        this.settings = settings;
        this.messages = messages;
    }

    /** Called on join; credits the streak reward when a new day starts. */
    public void handleJoin(Player player) {
        if (!settings.enabled()) {
            return;
        }
        players.touch(player.getUniqueId(), player.getName()).thenCompose(row -> {
            long today = today();
            int previous = row.streak();
            long lastDay = row.lastStreakDay();
            if (lastDay == today) {
                return CompletableFuture.completedFuture((Void) null);
            }
            int streak = lastDay == today - 1 ? previous + 1 : 1;
            streak = Math.min(streak, Math.max(1, settings.maxStreak()));
            double reward = reward(streak);
            int day = streak;
            return players.updateStreak(player.getUniqueId(), streak, today).thenCompose(updated -> {
                CompletableFuture<?> money = economy.deposit(player.getUniqueId(), reward,
                        TransactionType.STREAK_REWARD, "login streak day " + day);
                CompletableFuture<?> shardGrant = CompletableFuture.completedFuture(null);
                if (settings.shardEveryDays() > 0 && day % settings.shardEveryDays() == 0) {
                    shardGrant = shards.add(player.getUniqueId(), settings.shardsPerMilestone(),
                            "streak milestone " + day);
                }
                return CompletableFuture.allOf(money, shardGrant).thenRun(() ->
                        Scheduler.sync(() -> {
                            messages.send(player, "streak.reward",
                                    "day", String.valueOf(day),
                                    "amount", economy.format(reward));
                            if (settings.shardEveryDays() > 0 && day % settings.shardEveryDays() == 0) {
                                messages.send(player, "streak.milestone",
                                        "shards", String.valueOf(settings.shardsPerMilestone()));
                            }
                        }));
            });
        }).exceptionally(throwable -> {
            io.craftrealm.core.CraftRealmCore.logger()
                    .warning("Streak update failed for " + player.getName() + ": " + throwable.getMessage());
            return null;
        });
    }

    /** Reward for a streak length. */
    public double reward(int streak) {
        double growth = 1.0 + settings.growthPercent() / 100.0;
        double value = settings.baseReward() * Math.pow(growth, Math.max(0, streak - 1));
        return Money.round(value);
    }

    private static long today() {
        return System.currentTimeMillis() / 86_400_000L;
    }
}
