package io.craftrealm.plugin;

import io.craftrealm.admin.AdminModule;
import io.craftrealm.api.CraftRealmAPI;
import io.craftrealm.api.service.AdminService;
import io.craftrealm.api.service.AuctionService;
import io.craftrealm.api.service.BankService;
import io.craftrealm.api.service.ClaimService;
import io.craftrealm.api.service.CosmeticService;
import io.craftrealm.api.service.EconomyService;
import io.craftrealm.api.service.EventService;
import io.craftrealm.api.service.JobService;
import io.craftrealm.api.service.MarketService;
import io.craftrealm.api.service.QuestService;
import io.craftrealm.api.service.SeasonService;
import io.craftrealm.api.service.ShardService;
import io.craftrealm.core.auction.AuctionServiceImpl;
import io.craftrealm.core.bank.BankServiceImpl;
import io.craftrealm.core.economy.EconomyServiceImpl;
import io.craftrealm.core.economy.ShardServiceImpl;
import io.craftrealm.core.jobs.JobServiceImpl;
import io.craftrealm.core.market.MarketServiceImpl;
import io.craftrealm.core.util.Scheduler;
import io.craftrealm.events.EventModule;

import java.util.concurrent.CompletableFuture;

/**
 * The {@link CraftRealmAPI} implementation.
 *
 * <p>It is a pure facade: every method returns a service that other plugins can use
 * directly. No plugin state is exposed, so a dependent plugin cannot break the
 * economy by mutating internals.</p>
 */
public final class ApiImpl implements CraftRealmAPI {

    private final CraftRealmPlugin plugin;
    private final EconomyService economy;
    private final ShardService shards;
    private final BankService bank;
    private final MarketService market;
    private final AuctionService auctions;
    private final JobService jobs;
    private final EventModule events;
    private final AdminModule admin;

    public ApiImpl(CraftRealmPlugin plugin, EconomyServiceImpl economy, ShardServiceImpl shards,
                   BankServiceImpl bank, MarketServiceImpl market, AuctionServiceImpl auctions,
                   JobServiceImpl jobs, EventModule events, AdminModule admin) {
        this.plugin = plugin;
        this.economy = economy;
        this.shards = shards;
        this.bank = bank;
        this.market = market;
        this.auctions = auctions;
        this.jobs = jobs;
        this.events = events;
        this.admin = admin;
    }

    @Override
    public EconomyService economy() {
        return economy;
    }

    @Override
    public ShardService shards() {
        return shards;
    }

    @Override
    public BankService bank() {
        return bank;
    }

    @Override
    public MarketService market() {
        return market;
    }

    @Override
    public AuctionService auctions() {
        return auctions;
    }

    @Override
    public JobService jobs() {
        return jobs;
    }

    @Override
    public ClaimService claims() {
        return events.claims();
    }

    @Override
    public QuestService quests() {
        return events.quests();
    }

    @Override
    public SeasonService seasons() {
        return events.seasons();
    }

    @Override
    public CosmeticService cosmetics() {
        return events.cosmetics();
    }

    @Override
    public AdminService admin() {
        return admin.adminService();
    }

    @Override
    public EventService events() {
        return events.events();
    }

    @Override
    public String version() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public CompletableFuture<Void> reload() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Scheduler.sync(() -> {
            try {
                plugin.reloadEverything();
                future.complete(null);
            } catch (RuntimeException exception) {
                future.completeExceptionally(exception);
            }
        });
        return future;
    }
}
