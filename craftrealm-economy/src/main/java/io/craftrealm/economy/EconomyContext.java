package io.craftrealm.economy;

import io.craftrealm.api.service.AuctionService;
import io.craftrealm.api.service.BankService;
import io.craftrealm.api.service.EconomyService;
import io.craftrealm.api.service.JobService;
import io.craftrealm.api.service.MarketService;
import io.craftrealm.api.service.ShardService;
import io.craftrealm.core.config.Settings;
import io.craftrealm.core.db.Database;
import io.craftrealm.core.text.Messages;
import io.craftrealm.core.ui.GuiTheme;
import io.craftrealm.economy.items.CurrencyItems;
import org.bukkit.plugin.Plugin;

/**
 * Everything the economy module needs, injected once.
 *
 * <p>Plain constructor injection instead of a static service locator: tests can
 * build a context with fakes, and there is no hidden global state to reset between
 * test cases.</p>
 */
public final class EconomyContext {

    public final Plugin plugin;
    public final Settings settings;
    public final Messages messages;
    public final GuiTheme theme;
    public final EconomyService economy;
    public final ShardService shards;
    public final BankService bank;
    public final MarketService market;
    public final AuctionService auctions;
    public final JobService jobs;
    public final CurrencyItems items;
    public final Database database;
    public final io.craftrealm.economy.bounty.BountyService bounties;

    public EconomyContext(Plugin plugin, Settings settings, Messages messages, GuiTheme theme,
                          EconomyService economy, ShardService shards, BankService bank,
                          MarketService market, AuctionService auctions, JobService jobs,
                          CurrencyItems items, Database database,
                          io.craftrealm.economy.bounty.BountyService bounties) {
        this.plugin = plugin;
        this.settings = settings;
        this.messages = messages;
        this.theme = theme;
        this.economy = economy;
        this.shards = shards;
        this.bank = bank;
        this.market = market;
        this.auctions = auctions;
        this.jobs = jobs;
        this.items = items;
        this.database = database;
        this.bounties = bounties;
    }

    public io.craftrealm.economy.bounty.BountyService bounties() {
        return bounties;
    }

    public String money(double amount) {
        return economy.format(amount);
    }
}
