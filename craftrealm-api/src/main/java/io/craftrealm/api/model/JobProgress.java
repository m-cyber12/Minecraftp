package io.craftrealm.api.model;

import java.util.UUID;

/**
 * A player's standing in one job.
 *
 * @param uuid  player
 * @param job   career
 * @param level current level, starting at 1
 * @param xp    experience towards the next level
 */
public record JobProgress(UUID uuid, JobType job, int level, long xp) {

    public static JobProgress fresh(UUID uuid, JobType job) {
        return new JobProgress(uuid, job, 1, 0L);
    }
}
