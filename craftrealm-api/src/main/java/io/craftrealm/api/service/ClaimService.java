package io.craftrealm.api.service;

import io.craftrealm.api.model.Claim;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Land claims bought with Crowns, with upkeep tax and decay for unpaid land.
 */
public interface ClaimService {

    /** Price of a claim covering {@code areaBlocks} blocks. */
    double price(int areaBlocks);

    /** Upkeep charged per day for a claim. */
    double dailyUpkeep(Claim claim);

    CompletableFuture<Claim> create(UUID owner, String world, int x1, int z1, int x2, int z2);

    CompletableFuture<List<Claim>> claims(UUID owner);

    CompletableFuture<List<Claim>> all();

    CompletableFuture<Claim> at(String world, int x, int z);

    CompletableFuture<Boolean> delete(UUID actor, long claimId);

    /** Cached, main-thread safe build check. */
    boolean canBuild(UUID player, String world, int x, int z);

    /** Cached, main-thread safe "is this claimed at all". */
    boolean claimed(String world, int x, int z);

    /** Scheduled: charges upkeep, starts and completes decay. */
    CompletableFuture<Integer> runUpkeep();

    /** Decay stage 0 = healthy, 1 = overdue, 2 = build rights lost. */
    int decayStage(Claim claim);
}
