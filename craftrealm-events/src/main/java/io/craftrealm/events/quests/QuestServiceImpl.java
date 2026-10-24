package io.craftrealm.events.quests;

import io.craftrealm.api.model.Quest;
import io.craftrealm.api.model.QuestScope;
import io.craftrealm.api.model.QuestStatus;
import io.craftrealm.api.model.TransactionType;
import io.craftrealm.api.service.EconomyService;
import io.craftrealm.api.service.QuestService;
import io.craftrealm.api.service.ShardService;
import io.craftrealm.core.config.Settings;
import io.craftrealm.core.repo.ProgressionRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Daily and weekly quests.
 *
 * <p><b>Progress is cache first.</b> Mining a block must never wait for a database
 * round trip, so quest progress is advanced in memory and flushed asynchronously.
 * A crash can therefore lose a few points of progress — acceptable, because the
 * reward is only granted when the player clicks "collect", which re-reads the
 * persisted row and re-checks completion.</p>
 */
public final class QuestServiceImpl implements QuestService {

    private static final long DAY = 86_400_000L;

    private final EconomyService economy;
    private final ShardService shards;
    private final ProgressionRepository repository;
    private final Settings.QuestSettings settings;

    /** Player → active quests (cache). */
    private final Map<UUID, List<Quest>> cache = new ConcurrentHashMap<>();

    public QuestServiceImpl(EconomyService economy, ShardService shards,
                            ProgressionRepository repository, Settings.QuestSettings settings) {
        this.economy = economy;
        this.shards = shards;
        this.repository = repository;
        this.settings = settings;
    }

    @Override
    public CompletableFuture<Void> ensure(UUID uuid) {
        return repository.activeQuests(uuid).thenCompose(active -> {
            long now = System.currentTimeMillis();
            List<Quest> valid = new ArrayList<>(active.stream()
                    .filter(quest -> quest.expiresAt() > now)
                    .toList());
            cache.put(uuid, valid);

            boolean hasDaily = valid.stream().anyMatch(quest -> quest.scope() == QuestScope.DAILY);
            boolean hasWeekly = valid.stream().anyMatch(quest -> quest.scope() == QuestScope.WEEKLY);
            if (!settings.enabled() || (hasDaily && hasWeekly)) {
                return CompletableFuture.completedFuture(null);
            }

            List<CompletableFuture<Quest>> created = new ArrayList<>();
            if (!hasDaily) {
                create(uuid, QuestScope.DAILY, settings.dailyCount(), created, valid);
            }
            if (!hasWeekly) {
                create(uuid, QuestScope.WEEKLY, settings.weeklyCount(), created, valid);
            }
            return CompletableFuture.allOf(created.toArray(CompletableFuture[]::new));
        });
    }

    private void create(UUID uuid, QuestScope scope, int count, List<CompletableFuture<Quest>> sink,
                        List<Quest> into) {
        List<Settings.QuestTemplate> templates = settings.templates().stream()
                .filter(template -> template.scope().equalsIgnoreCase(scope.name()))
                .toList();
        if (templates.isEmpty()) {
            return;
        }
        java.util.Collections.shuffle(new java.util.ArrayList<>(templates));
        long now = System.currentTimeMillis();
        long expires = scope == QuestScope.DAILY ? now + DAY : now + DAY * 7;
        for (int index = 0; index < Math.min(count, templates.size()); index++) {
            Settings.QuestTemplate template = templates.get(index);
            Quest quest = new Quest(0, uuid, template.id(), template.kind(), template.id(),
                    template.id(), template.target(), 0,
                    template.shardReward() ? TransactionType.QUEST_REWARD : TransactionType.QUEST_REWARD,
                    template.reward(), scope, now, expires, QuestStatus.ACTIVE);
            sink.add(repository.createQuest(quest).thenApply(saved -> {
                into.add(saved);
                return saved;
            }));
        }
        cache.put(uuid, into);
    }

    @Override
    public CompletableFuture<List<Quest>> quests(UUID uuid) {
        List<Quest> cached = cache.get(uuid);
        if (cached != null && !cached.isEmpty()) {
            return CompletableFuture.completedFuture(List.copyOf(cached));
        }
        return repository.quests(uuid).thenApply(list -> {
            cache.put(uuid, list);
            return list;
        });
    }

    @Override
    public CompletableFuture<List<Quest>> quests(UUID uuid, QuestScope scope) {
        return quests(uuid).thenApply(list -> list.stream()
                .filter(quest -> quest.scope() == scope)
                .toList());
    }

    @Override
    public CompletableFuture<Boolean> claim(UUID uuid, long questId) {
        return repository.quest(questId).thenCompose(optional -> {
            if (optional.isEmpty()) {
                return CompletableFuture.completedFuture(false);
            }
            Quest quest = optional.get();
            if (!quest.owner().equals(uuid) || quest.status() != QuestStatus.ACTIVE || !quest.isComplete()) {
                return CompletableFuture.completedFuture(false);
            }
            return repository.setQuestStatus(questId, QuestStatus.ACTIVE, QuestStatus.CLAIMED)
                    .thenCompose(claimed -> {
                        if (!claimed) {
                            return CompletableFuture.completedFuture(false);
                        }
                        CompletableFuture<?> payout = quest.rewardType() == TransactionType.QUEST_REWARD
                                ? economy.deposit(uuid, quest.reward(), TransactionType.QUEST_REWARD,
                                "quest " + quest.templateId())
                                : shards.add(uuid, (long) quest.reward(), "quest " + quest.templateId());
                        return payout.thenApply(done -> {
                            removeFromCache(uuid, questId);
                            return true;
                        });
                    });
        });
    }

    @Override
    public void addProgress(UUID uuid, String kind, double amount) {
        List<Quest> active = cache.get(uuid);
        if (active == null || active.isEmpty() || amount <= 0) {
            return;
        }
        for (Quest quest : active) {
            if (quest.status() != QuestStatus.ACTIVE || !quest.kind().equalsIgnoreCase(kind)) {
                continue;
            }
            double progress = Math.min(quest.target(), quest.progress() + amount);
            if (progress == quest.progress()) {
                continue;
            }
            Quest updated = new Quest(quest.id(), quest.owner(), quest.templateId(), quest.kind(),
                    quest.title(), quest.description(), quest.target(), progress, quest.rewardType(),
                    quest.reward(), quest.scope(), quest.createdAt(), quest.expiresAt(), quest.status());
            replace(uuid, updated);
            repository.addProgress(quest.id(), amount);
        }
    }

    @Override
    public CompletableFuture<Integer> rotate() {
        long now = System.currentTimeMillis();
        return repository.expireQuests(now).thenCompose(expired -> {
            cache.values().forEach(quests -> quests.removeIf(quest -> quest.expiresAt() <= now));
            return CompletableFuture.completedFuture(cache.size());
        });
    }

    private void replace(UUID uuid, Quest updated) {
        List<Quest> active = cache.get(uuid);
        if (active == null) {
            return;
        }
        for (int index = 0; index < active.size(); index++) {
            if (active.get(index).id() == updated.id()) {
                active.set(index, updated);
                return;
            }
        }
    }

    private void removeFromCache(UUID uuid, long questId) {
        List<Quest> active = cache.get(uuid);
        if (active != null) {
            active.removeIf(quest -> quest.id() == questId);
        }
    }

    public void unload(UUID uuid) {
        cache.remove(uuid);
    }

    public Collection<Quest> cached(UUID uuid) {
        return cache.getOrDefault(uuid, List.of());
    }
}
