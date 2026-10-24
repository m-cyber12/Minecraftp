package io.craftrealm.api;

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

import java.util.concurrent.CompletableFuture;

/**
 * Root of the CraftRealm public API.
 *
 * <pre>{@code
 * CraftRealmAPI api = CraftRealm.get();
 * api.economy().deposit(player.getUniqueId(), 250, TransactionType.EVENT_REWARD, "mob arena");
 * }</pre>
 *
 * <p>Obtain it through {@link CraftRealm#get()} or
 * {@code Bukkit.getServicesManager().load(CraftRealmAPI.class)}. It is also
 * registered with the services manager so a soft dependency is enough.</p>
 */
public interface CraftRealmAPI {

    EconomyService economy();

    ShardService shards();

    BankService bank();

    MarketService market();

    AuctionService auctions();

    JobService jobs();

    ClaimService claims();

    QuestService quests();

    SeasonService seasons();

    CosmeticService cosmetics();

    AdminService admin();

    EventService events();

    /** Plugin version, e.g. {@code "1.0.0"}. */
    String version();

    /** Reloads configuration and localisation. Completes when the new settings are live. */
    CompletableFuture<Void> reload();
}
