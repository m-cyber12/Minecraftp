package io.craftrealm.api.model;

import java.util.UUID;

/**
 * One generated quest instance.
 *
 * @param id          row id
 * @param owner       player
 * @param templateId  template that generated it (see {@code quests.templates})
 * @param kind        activity counter it listens to, e.g. {@code MINE_BLOCK}
 * @param title       rendered title
 * @param description rendered description
 * @param target      amount of {@code kind} required
 * @param progress    amount accumulated so far
 * @param rewardType  currency the reward is paid in
 * @param reward      reward amount
 * @param scope       daily or weekly
 * @param createdAt   epoch millis
 * @param expiresAt   epoch millis
 * @param status      lifecycle state
 */
public record Quest(long id, UUID owner, String templateId, String kind, String title,
                    String description, double target, double progress,
                    TransactionType rewardType, double reward, QuestScope scope,
                    long createdAt, long expiresAt, QuestStatus status) {

    public boolean isComplete() {
        return progress >= target;
    }

    public double percent() {
        return target <= 0 ? 1.0 : Math.min(1.0, progress / target);
    }
}
