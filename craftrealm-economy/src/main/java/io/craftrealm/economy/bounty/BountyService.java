package io.craftrealm.economy.bounty;

import io.craftrealm.api.model.Bounty;
import io.craftrealm.api.model.TransactionType;
import io.craftrealm.api.service.EconomyService;
import io.craftrealm.core.config.Settings;
import io.craftrealm.core.db.Database;
import io.craftrealm.core.repo.BountyRepository;
import io.craftrealm.core.util.Money;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * The bounty board.
 *
 * <p>Money is escrowed the moment a bounty is placed, so a bounty can never be
 * "unfunded" when someone finally claims it, and it cannot be paid twice because
 * claiming is a conditional status transition.</p>
 */
public final class BountyService {

    private final BountyRepository repository;
    private final EconomyService economy;
    private final Settings.BountySettings settings;

    public BountyService(Database database, EconomyService economy, Settings.BountySettings settings) {
        this.repository = new BountyRepository(database);
        this.economy = economy;
        this.settings = settings;
    }

    /**
     * Places a bounty.
     *
     * @param feedback receives {@code (success, messageKey)}
     */
    public void place(UUID placer, String placerName, UUID target, String targetName, double amount,
                      BiConsumer<Boolean, String> feedback) {
        double value = Money.round(amount);
        if (!settings.enabled()) {
            feedback.accept(false, "bounty.disabled");
            return;
        }
        if (placer.equals(target)) {
            feedback.accept(false, "bounty.self");
            return;
        }
        if (!Money.isValidAmount(value) || value < settings.minimum() || value > settings.maximum()) {
            feedback.accept(false, "bounty.invalid-amount");
            return;
        }
        economy.withdraw(placer, value, TransactionType.BOUNTY_PLACE, "bounty on " + targetName)
                .thenCompose(result -> {
                    if (!result.success()) {
                        feedback.accept(false, result.messageKey());
                        return CompletableFuture.completedFuture(null);
                    }
                    return repository.create(target, targetName, placer, value)
                            .thenAccept(bounty -> feedback.accept(true, "bounty.placed"))
                            .exceptionally(throwable -> {
                                // Never keep the escrow if the row could not be written.
                                economy.credit(placer, value, TransactionType.AUCTION_REFUND,
                                        "bounty rollback", null);
                                feedback.accept(false, "bounty.error");
                                return null;
                            });
                });
    }

    /** Pays every open bounty on the victim to the killer, one conditional claim each. */
    public CompletableFuture<Double> claim(UUID killer, UUID victim) {
        return repository.openFor(victim).thenCompose(bounties -> {
            List<CompletableFuture<Double>> payouts = bounties.stream()
                    .map(bounty -> repository.claim(bounty.id())
                            .thenCompose(claimed -> {
                                if (!claimed) {
                                    return CompletableFuture.completedFuture(0.0);
                                }
                                return economy.credit(killer, bounty.amount(), TransactionType.BOUNTY_CLAIM,
                                                "bounty on " + bounty.targetName(), "bounty:" + bounty.id())
                                        .thenApply(result -> result.success() ? bounty.amount() : 0.0);
                            }))
                    .toList();
            return CompletableFuture.allOf(payouts.toArray(CompletableFuture[]::new))
                    .thenApply(ignored -> payouts.stream().mapToDouble(CompletableFuture::join).sum());
        });
    }

    public CompletableFuture<List<Bounty>> open(int limit) {
        return repository.open(limit);
    }

    public CompletableFuture<Double> total(UUID target) {
        return repository.totalFor(target);
    }

    public Settings.BountySettings settings() {
        return settings;
    }
}
