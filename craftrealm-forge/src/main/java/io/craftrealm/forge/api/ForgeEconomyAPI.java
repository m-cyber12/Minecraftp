package io.craftrealm.forge.api;

import io.craftrealm.forge.config.ForgeConfig;
import io.craftrealm.forge.market.ForgeMarket;
import io.craftrealm.forge.storage.ForgeDatabase;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Public server-scoped economy API for other Forge mods. Database work is always
 * asynchronous; cancellable balance events are posted on the server thread.
 */
public final class ForgeEconomyAPI {
    private static final AtomicReference<ForgeEconomyAPI> INSTANCE = new AtomicReference<>();

    private final Supplier<ForgeDatabase> database;
    private final Supplier<ForgeMarket> market;
    private final Supplier<MinecraftServer> server;

    public ForgeEconomyAPI(Supplier<ForgeDatabase> database, Supplier<ForgeMarket> market,
                           Supplier<MinecraftServer> server) {
        this.database = database;
        this.market = market;
        this.server = server;
    }

    /** Returns the active API for the running logical server, or null when stopped. */
    public static ForgeEconomyAPI getInstance() {
        return INSTANCE.get();
    }

    /** The mod lifecycle installs and removes the per-server API instance. */
    public static void install(ForgeEconomyAPI api) {
        INSTANCE.set(api);
    }

    public static void uninstall(ForgeEconomyAPI api) {
        INSTANCE.compareAndSet(api, null);
    }

    public CompletableFuture<Long> balance(UUID player, String playerName) {
        ForgeDatabase store = database.get();
        if (store == null) return CompletableFuture.failedFuture(new IllegalStateException("CraftRealm is offline"));
        return store.balance(player, playerName, cents(ForgeConfig.STARTING_BALANCE.get()));
    }

    /**
     * Transfers exact cents between accounts. Cancelled or rejected operations
     * complete normally with a status so API consumers can inspect the outcome.
     */
    public CompletableFuture<TransferResult> pay(UUID payer, String payerName, UUID recipient,
                                                   String recipientName, long amountCents) {
        if (amountCents <= 0 || amountCents > cents(ForgeConfig.MAX_TRANSACTION.get())) {
            return CompletableFuture.completedFuture(TransferResult.failed(Status.INVALID_AMOUNT));
        }
        if (payer.equals(recipient)) return CompletableFuture.completedFuture(TransferResult.failed(Status.SELF_PAYMENT));
        ForgeDatabase store = database.get();
        MinecraftServer activeServer = server.get();
        if (store == null || activeServer == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("CraftRealm is offline"));
        }
        CompletableFuture<TransferResult> result = new CompletableFuture<>();
        store.balance(payer, payerName, cents(ForgeConfig.STARTING_BALANCE.get()))
                .thenCombine(store.balance(recipient, recipientName, cents(ForgeConfig.STARTING_BALANCE.get())),
                        (payerBalance, recipientBalance) -> new long[]{payerBalance, recipientBalance})
                .whenComplete((balances, readFailure) -> {
                    if (readFailure != null) {
                        result.completeExceptionally(readFailure);
                        return;
                    }
                    try {
                        activeServer.execute(() -> {
                        long maximum = cents(ForgeConfig.MAX_BALANCE.get());
                        if (amountCents > balances[0]) {
                            result.complete(TransferResult.failed(Status.INSUFFICIENT_FUNDS));
                            return;
                        }
                        if (balances[1] > maximum - amountCents) {
                            result.complete(TransferResult.failed(Status.RECIPIENT_LIMIT));
                            return;
                        }
                        ForgeBalanceChangeEvent debit = new ForgeBalanceChangeEvent(payer,
                                balances[0], balances[0] - amountCents, ForgeTransactionType.PAY,
                                "player payment", payer);
                        ForgeBalanceChangeEvent credit = new ForgeBalanceChangeEvent(recipient,
                                balances[1], balances[1] + amountCents, ForgeTransactionType.PAY,
                                "player payment", payer);
                        if (MinecraftForge.EVENT_BUS.post(debit) || MinecraftForge.EVENT_BUS.post(credit)) {
                            result.complete(TransferResult.failed(Status.CANCELLED));
                            return;
                        }
                        store.pay(payer, payerName, recipient, recipientName, amountCents,
                                        cents(ForgeConfig.STARTING_BALANCE.get()), maximum)
                                .whenComplete((payment, failure) -> {
                                    if (failure != null) result.completeExceptionally(failure);
                                    else if (payment.paid()) result.complete(new TransferResult(Status.SUCCESS,
                                            payment.payerBalanceCents(), payment.recipientBalanceCents()));
                                    else if (payment.insufficientFunds()) result.complete(TransferResult.failed(Status.INSUFFICIENT_FUNDS));
                                    else result.complete(TransferResult.failed(Status.RECIPIENT_LIMIT));
                                });
                        });
                    } catch (RuntimeException serverStopping) {
                        result.completeExceptionally(serverStopping);
                    }
                });
        return result;
    }

    public CompletableFuture<List<ForgeDatabase.MarketQuote>> marketQuotes() {
        ForgeMarket service = market.get();
        return service == null ? CompletableFuture.failedFuture(new IllegalStateException("market unavailable"))
                : service.quotes();
    }

    public enum Status {
        SUCCESS, INVALID_AMOUNT, SELF_PAYMENT, INSUFFICIENT_FUNDS, RECIPIENT_LIMIT, CANCELLED
    }

    public record TransferResult(Status status, long payerBalanceCents, long recipientBalanceCents) {
        public boolean success() { return status == Status.SUCCESS; }
        private static TransferResult failed(Status status) { return new TransferResult(status, 0, 0); }
    }

    private static long cents(double amount) {
        return BigDecimal.valueOf(amount).movePointRight(2).longValueExact();
    }
}
