package io.craftrealm.api.service;

import io.craftrealm.api.model.Quest;
import io.craftrealm.api.model.QuestScope;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Daily and weekly quests generated from the templates in {@code config.yml}.
 */
public interface QuestService {

    /** Makes sure the player has the current daily and weekly quests. */
    CompletableFuture<Void> ensure(UUID uuid);

    CompletableFuture<List<Quest>> quests(UUID uuid);

    CompletableFuture<List<Quest>> quests(UUID uuid, QuestScope scope);

    /** Collects the reward of a completed quest. */
    CompletableFuture<Boolean> claim(UUID uuid, long questId);

    /**
     * Advances every active quest of the player that listens to {@code kind}.
     * Called from gameplay listeners; cheap (cache only) and safe to spam.
     */
    void addProgress(UUID uuid, String kind, double amount);

    /** Scheduled: expires stale quests and generates new ones. */
    CompletableFuture<Integer> rotate();
}
