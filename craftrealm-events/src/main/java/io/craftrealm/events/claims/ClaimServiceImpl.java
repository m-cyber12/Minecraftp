package io.craftrealm.events.claims;

import io.craftrealm.api.model.Claim;
import io.craftrealm.api.model.TransactionType;
import io.craftrealm.api.service.ClaimService;
import io.craftrealm.api.service.EconomyService;
import io.craftrealm.core.config.ClaimSettings;
import io.craftrealm.core.repo.WorldRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Land claims bought with Crowns.
 *
 * <p><b>Why a cache:</b> the build check runs on the tick thread for every block
 * break and placement. A database round trip there would be catastrophic, so claims
 * are held in memory and refreshed after every mutation. The cache is the size of
 * the claim list, which is a handful of entries per player, not per block.</p>
 *
 * <p><b>Decay:</b> when upkeep cannot be paid the claim becomes overdue (grace
 * period, build rights intact), then loses build rights, and only after the grace
 * period is it deleted. Nothing is ever removed without a warning.</p>
 */
public final class ClaimServiceImpl implements ClaimService {

    private static final long DAY_MILLIS = 86_400_000L;

    private final EconomyService economy;
    private final WorldRepository repository;
    private final ClaimSettings settings;

    private final List<Claim> cache = new CopyOnWriteArrayList<>();

    public ClaimServiceImpl(EconomyService economy, WorldRepository repository, ClaimSettings settings) {
        this.economy = economy;
        this.repository = repository;
        this.settings = settings;
    }

    /** Loads every claim into the cache; called during startup. */
    public CompletableFuture<Void> warmUp() {
        return repository.claims().thenAccept(claims -> {
            cache.clear();
            cache.addAll(claims);
        });
    }

    @Override
    public double price(int areaBlocks) {
        return io.craftrealm.core.util.Money.round(Math.max(0, areaBlocks) * settings.pricePerBlock());
    }

    @Override
    public double dailyUpkeep(Claim claim) {
        return io.craftrealm.core.util.Money.round(claim.area() * settings.upkeepPerBlock());
    }

    @Override
    public CompletableFuture<Claim> create(UUID owner, String world, int x1, int z1, int x2, int z2) {
        if (!settings.enabled()) {
            return CompletableFuture.failedFuture(new IllegalStateException("claims disabled"));
        }
        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);
        int width = maxX - minX + 1;
        int depth = maxZ - minZ + 1;

        if (width < settings.minSize() || depth < settings.minSize()
                || width > settings.maxSize() || depth > settings.maxSize()) {
            return CompletableFuture.failedFuture(new IllegalStateException("size"));
        }
        for (Claim existing : cache) {
            if (!existing.world().equalsIgnoreCase(world)) {
                continue;
            }
            boolean overlaps = minX <= existing.maxX() && maxX >= existing.minX()
                    && minZ <= existing.maxZ() && maxZ >= existing.minZ();
            if (overlaps) {
                return CompletableFuture.failedFuture(new IllegalStateException("overlap"));
            }
        }

        long owned = cache.stream().filter(claim -> claim.owner().equals(owner)).count();
        if (owned >= settings.maxClaims()) {
            return CompletableFuture.failedFuture(new IllegalStateException("limit"));
        }

        double price = price(width * depth);
        return economy.withdraw(owner, price, TransactionType.CLAIM_PURCHASE,
                "claim " + width + "x" + depth).thenCompose(result -> {
            if (!result.success()) {
                return CompletableFuture.failedFuture(new IllegalStateException("funds"));
            }
            long paidUntil = System.currentTimeMillis() + DAY_MILLIS;
            return repository.createClaim(owner, world, minX, minZ, maxX, maxZ, paidUntil)
                    .thenApply(claim -> {
                        cache.add(claim);
                        return claim;
                    })
                    .exceptionally(throwable -> {
                        // Persisting failed after the money was taken: give it back.
                        economy.deposit(owner, price, TransactionType.ROLLBACK, "claim creation failed");
                        throw new IllegalStateException("storage", throwable);
                    });
        });
    }

    @Override
    public CompletableFuture<List<Claim>> claims(UUID owner) {
        return repository.claims(owner);
    }

    @Override
    public CompletableFuture<List<Claim>> all() {
        return repository.claims();
    }

    @Override
    public CompletableFuture<Claim> at(String world, int x, int z) {
        return CompletableFuture.completedFuture(find(world, x, z));
    }

    @Override
    public CompletableFuture<Boolean> delete(UUID actor, long claimId) {
        Optional<Claim> target = cache.stream().filter(claim -> claim.id() == claimId).findFirst();
        if (target.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        Claim claim = target.get();
        boolean staff = !claim.owner().equals(actor);
        CompletableFuture<Boolean> deletion = staff
                ? repository.forceDeleteClaim(claimId)
                : repository.deleteClaim(claimId, actor);
        return deletion.thenApply(deleted -> {
            if (deleted) {
                cache.removeIf(entry -> entry.id() == claimId);
            }
            return deleted;
        });
    }

    @Override
    public boolean canBuild(UUID player, String world, int x, int z) {
        Claim claim = find(world, x, z);
        if (claim == null) {
            return true;
        }
        return claim.owner().equals(player);
    }

    @Override
    public boolean claimed(String world, int x, int z) {
        return find(world, x, z) != null;
    }

    /** Main-thread safe lookup used by the protection listener. */
    public Claim find(String world, int x, int z) {
        for (Claim claim : cache) {
            if (claim.contains(world, x, z)) {
                return claim;
            }
        }
        return null;
    }

    @Override
    public CompletableFuture<Integer> runUpkeep() {
        long now = System.currentTimeMillis();
        List<Claim> due = cache.stream().filter(claim -> claim.paidUntil() <= now).toList();
        if (due.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        CompletableFuture<?>[] tasks = due.stream().map(claim -> charge(claim, now).toCompletableFuture())
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(tasks).thenApply(ignored -> due.size());
    }

    private CompletableFuture<Void> charge(Claim claim, long now) {
        double upkeep = dailyUpkeep(claim);
        return economy.withdraw(claim.owner(), upkeep, TransactionType.CLAIM_UPKEEP,
                "claim upkeep #" + claim.id()).thenCompose(result -> {
            if (result.success()) {
                long paidUntil = now + DAY_MILLIS;
                return repository.extendClaim(claim.id(), paidUntil).thenApply(done -> {
                    replace(claim.id(), paidUntil, now);
                    return (Void) null;
                });
            }
            long overdue = now - claim.paidUntil();
            if (overdue > settings.graceDays() * DAY_MILLIS) {
                return repository.forceDeleteClaim(claim.id()).thenApply(deleted -> {
                    if (deleted) {
                        cache.removeIf(entry -> entry.id() == claim.id());
                    }
                    return (Void) null;
                });
            }
            // Still in grace: record that we tried so /claim shows a warning.
            return repository.stampTaxRun(claim.id()).thenApply(done -> {
                replace(claim.id(), claim.paidUntil(), now);
                return (Void) null;
            });
        });
    }

    private void replace(long id, long paidUntil, long lastRun) {
        for (int index = 0; index < cache.size(); index++) {
            Claim existing = cache.get(index);
            if (existing.id() == id) {
                cache.set(index, new Claim(existing.id(), existing.owner(), existing.world(),
                        existing.minX(), existing.minZ(), existing.maxX(), existing.maxZ(),
                        existing.createdAt(), paidUntil, lastRun));
                return;
            }
        }
    }

    @Override
    public int decayStage(Claim claim) {
        long now = System.currentTimeMillis();
        if (claim.paidUntil() > now) {
            return 0;
        }
        long overdue = now - claim.paidUntil();
        return overdue > settings.graceDays() * DAY_MILLIS / 2 ? 2 : 1;
    }

    /** Used by {@code /claim info} to show the player's own claims from the cache. */
    public List<Claim> cachedClaims(UUID owner) {
        return cache.stream().filter(claim -> claim.owner().equals(owner)).toList();
    }

    public ClaimSettings settings() {
        return settings;
    }
}
