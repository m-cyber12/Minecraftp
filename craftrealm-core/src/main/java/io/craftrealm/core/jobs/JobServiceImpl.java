package io.craftrealm.core.jobs;

import io.craftrealm.api.event.JobLevelUpEvent;
import io.craftrealm.api.model.JobProgress;
import io.craftrealm.api.model.JobType;
import io.craftrealm.api.model.TransactionType;
import io.craftrealm.api.service.EconomyService;
import io.craftrealm.api.service.JobService;
import io.craftrealm.core.config.JobSettings;
import io.craftrealm.core.db.Database;
import io.craftrealm.core.repo.JobRepository;
import io.craftrealm.core.util.Events;
import io.craftrealm.core.util.Money;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Jobs: pays Crowns for work, levels up, and never lets one player farm more than
 * the configured daily cap.
 *
 * <p>The in-memory state is authoritative during a session and is flushed after
 * every award. Mutations are synchronised per player, so two rapid block breaks
 * cannot both read "level 5, xp 99" and overwrite each other's payout.</p>
 */
public final class JobServiceImpl implements JobService {

    /** Mutable per-job session state. */
    private static final class State {
        private int level;
        private long xp;
        private double earnedToday;
        private long earnDay;

        private State(JobProgress progress, double earnedToday, long earnDay) {
            this.level = progress.level();
            this.xp = progress.xp();
            this.earnedToday = earnedToday;
            this.earnDay = earnDay;
        }

        private JobProgress toProgress(UUID uuid, JobType job) {
            return new JobProgress(uuid, job, level, xp);
        }
    }

    private final Database database;
    private final JobRepository repository;
    private final EconomyService economy;
    private final JobSettings settings;
    private final JobRewardCalculator calculator;
    private final Map<UUID, Map<JobType, State>> cache = new ConcurrentHashMap<>();

    public JobServiceImpl(Database database, EconomyService economy, JobSettings settings) {
        this.database = database;
        this.repository = new JobRepository(database);
        this.economy = economy;
        this.settings = settings;
        this.calculator = new JobRewardCalculator(settings.multiplierPerLevel(), settings.dailyCap());
    }

    // --------------------------------------------------------------- interface

    @Override
    public double multiplier(int level) {
        return JobLevelCurve.multiplier(level, settings.multiplierPerLevel());
    }

    @Override
    public long xpForLevel(int level) {
        return JobLevelCurve.xpForLevel(level, settings.xpBase(), settings.xpGrowth());
    }

    @Override
    public CompletableFuture<Void> load(UUID uuid) {
        return repository.load(uuid).thenAccept(rows -> cache.compute(uuid, (key, existing) -> {
            // Merge into whatever is already cached instead of replacing it: a block
            // break that lands while the load is in flight must not be lost.
            Map<JobType, State> states = existing == null ? new ConcurrentHashMap<>() : existing;
            for (JobRepository.JobRow row : rows) {
                states.put(row.progress().job(), new State(row.progress(), row.earnedToday(), row.earnDay()));
            }
            for (JobType type : JobType.values()) {
                states.computeIfAbsent(type, ignored -> new State(JobProgress.fresh(uuid, type), 0.0, 0L));
            }
            return states;
        }));
    }

    @Override
    public void unload(UUID uuid) {
        Map<JobType, State> states = cache.remove(uuid);
        if (states != null) {
            states.forEach((job, state) -> persist(uuid, job, state));
        }
    }

    @Override
    public JobProgress cached(UUID uuid, JobType job) {
        State state = state(uuid, job);
        synchronized (state) {
            return state.toProgress(uuid, job);
        }
    }

    @Override
    public CompletableFuture<JobProgress> progress(UUID uuid, JobType job) {
        return CompletableFuture.completedFuture(cached(uuid, job));
    }

    @Override
    public CompletableFuture<List<JobProgress>> progress(UUID uuid) {
        List<JobProgress> result = new ArrayList<>(JobType.values().length);
        for (JobType type : JobType.values()) {
            result.add(cached(uuid, type));
        }
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<Double> award(UUID uuid, JobType job, double baseAmount, String source) {
        if (baseAmount <= 0) {
            return CompletableFuture.completedFuture(0.0);
        }
        if (cache.containsKey(uuid)) {
            return pay(uuid, job, baseAmount, source);
        }
        return load(uuid).thenCompose(ignored -> pay(uuid, job, baseAmount, source));
    }

    @Override
    public CompletableFuture<List<JobProgress>> top(JobType job, int limit) {
        return repository.top(job, limit);
    }

    @Override
    public double earnedToday(UUID uuid, JobType job) {
        State state = state(uuid, job);
        synchronized (state) {
            return state.earnDay == today() ? state.earnedToday : 0.0;
        }
    }

    // ---------------------------------------------------------------- internals

    private CompletableFuture<Double> pay(UUID uuid, JobType job, double baseAmount, String source) {
        State state = state(uuid, job);
        double payout;
        int newLevel = -1;
        synchronized (state) {
            if (state.earnDay != today()) {
                state.earnDay = today();
                state.earnedToday = 0.0;
            }
            payout = calculator.cappedPayout(baseAmount, state.level, 1.0, state.earnedToday);
            // XP is earned even when the daily cap has been reached — players keep
            // progressing, they just stop printing money.
            long xpGain = Math.max(1L, Math.round(payout * settings.xpPerCrown()));
            state.xp += xpGain;
            while (state.level < settings.maxLevel() && state.xp >= xpForLevel(state.level)) {
                state.xp -= xpForLevel(state.level);
                state.level++;
                newLevel = state.level;
            }
            state.earnedToday = Money.round(state.earnedToday + payout);
        }
        final double paid = payout;
        final int levelReached = newLevel;
        if (paid > 0) {
            economy.deposit(uuid, paid, TransactionType.JOB_INCOME, job.key() + ":" + source);
        }
        persist(uuid, job, state);
        if (levelReached > 0) {
            Events.call(new JobLevelUpEvent(uuid, job, levelReached));
        }
        return CompletableFuture.completedFuture(paid);
    }

    private State state(UUID uuid, JobType job) {
        Map<JobType, State> states = cache.get(uuid);
        if (states == null) {
            Map<JobType, State> placeholder = new ConcurrentHashMap<>();
            Map<JobType, State> winner = cache.putIfAbsent(uuid, placeholder);
            states = winner != null ? winner : placeholder;
            if (winner == null) {
                // Refresh from storage in the background; defaults are usable meanwhile.
                load(uuid);
            }
        }
        return states.computeIfAbsent(job, type -> new State(JobProgress.fresh(uuid, type), 0.0, 0L));
    }

    private void persist(UUID uuid, JobType job, State state) {
        if (database.executor().isShuttingDown()) {
            return;
        }
        JobProgress snapshot;
        double earned;
        long day;
        synchronized (state) {
            snapshot = state.toProgress(uuid, job);
            earned = state.earnedToday;
            day = state.earnDay;
        }
        repository.save(uuid, snapshot, earned, day);
    }

    private static long today() {
        return System.currentTimeMillis() / 86_400_000L;
    }
}
