package io.craftrealm.api.service;

import io.craftrealm.api.model.JobProgress;
import io.craftrealm.api.model.JobType;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Jobs pay Crowns for honest work. Every job has its own level curve; the level
 * multiplies the payout, so a level 30 miner earns noticeably more per ore than a
 * level 1 miner.
 */
public interface JobService {

    /** Income multiplier for a job level, e.g. {@code 1.0 + level * 0.05}. */
    double multiplier(int level);

    /** XP required to advance from {@code level} to {@code level + 1}. */
    long xpForLevel(int level);

    CompletableFuture<Void> load(UUID uuid);

    void unload(UUID uuid);

    JobProgress cached(UUID uuid, JobType job);

    CompletableFuture<JobProgress> progress(UUID uuid, JobType job);

    CompletableFuture<List<JobProgress>> progress(UUID uuid);

    /**
     * Pays a player for job work and grants XP.
     *
     * @param baseAmount base Crowns before the level multiplier
     * @param source     short description for the ledger (e.g. {@code "diamond_ore"})
     * @return the amount actually paid
     */
    CompletableFuture<Double> award(UUID uuid, JobType job, double baseAmount, String source);

    CompletableFuture<List<JobProgress>> top(JobType job, int limit);

    /** Daily cap accounting: Crowns already earned today in this job. */
    double earnedToday(UUID uuid, JobType job);
}
