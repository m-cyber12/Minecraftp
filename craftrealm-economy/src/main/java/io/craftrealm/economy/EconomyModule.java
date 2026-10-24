package io.craftrealm.economy;

import io.craftrealm.api.service.AuctionService;
import io.craftrealm.api.service.BankService;
import io.craftrealm.api.service.EconomyService;
import io.craftrealm.api.service.JobService;
import io.craftrealm.api.service.MarketService;
import io.craftrealm.api.service.ShardService;
import io.craftrealm.core.command.CommandRouter;
import io.craftrealm.core.command.SubCommand;
import io.craftrealm.core.config.Settings;
import io.craftrealm.core.db.Database;
import io.craftrealm.core.repo.ProgressionRepository;
import io.craftrealm.core.text.Messages;
import io.craftrealm.core.ui.GuiTheme;
import io.craftrealm.economy.bounty.BountyService;
import io.craftrealm.economy.command.AuctionCommand;
import io.craftrealm.economy.command.BalanceCommand;
import io.craftrealm.economy.command.BaltopCommand;
import io.craftrealm.economy.command.BankCommand;
import io.craftrealm.economy.command.BountyCommand;
import io.craftrealm.economy.command.CoinsCommand;
import io.craftrealm.economy.command.JobCommand;
import io.craftrealm.economy.command.PayCommand;
import io.craftrealm.economy.command.SellCommand;
import io.craftrealm.economy.command.ShopCommand;
import io.craftrealm.economy.items.CurrencyItems;
import io.craftrealm.economy.items.CurrencyService;
import io.craftrealm.economy.items.NoteSecret;
import io.craftrealm.economy.listener.BountyListener;
import io.craftrealm.economy.listener.CurrencyListener;
import io.craftrealm.economy.listener.JobListener;
import io.craftrealm.economy.listener.StreakListener;
import io.craftrealm.economy.streak.StreakService;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Wires the economy module: physical currency, bounties, jobs, streaks, commands
 * and the listener set. The plugin module only calls {@link #register}.
 */
public final class EconomyModule {

    private final EconomyContext context;
    private final CurrencyService currency;
    private final StreakService streaks;
    private final List<SubCommand> commands = new ArrayList<>();
    private final List<Listener> listeners = new ArrayList<>();

    public EconomyModule(Plugin plugin, Settings settings, Messages messages, GuiTheme theme,
                         EconomyService economy, ShardService shards, BankService bank,
                         MarketService market, AuctionService auctions, JobService jobs,
                         Database database, ProgressionRepository progression) {
        NoteSecret secret = NoteSecret.load(new File(plugin.getDataFolder(), "data/note.key"));
        CurrencyItems items = new CurrencyItems(plugin, messages, secret, settings.theme());
        BountyService bounties = new BountyService(database, economy, settings.bounties());
        this.context = new EconomyContext(plugin, settings, messages, theme, economy, shards, bank,
                market, auctions, jobs, items, database, bounties);
        this.currency = new CurrencyService(context, progression);
        this.streaks = new StreakService(new io.craftrealm.core.repo.PlayerRepository(database),
                economy, shards, settings.streaks(), messages);

        commands.add(new BalanceCommand(messages, context));
        commands.add(new PayCommand(messages, context));
        commands.add(new BaltopCommand(messages, context));
        commands.add(new ShopCommand(messages, context));
        commands.add(new SellCommand(messages, context));
        commands.add(new BankCommand(messages, context));
        commands.add(new AuctionCommand(messages, context));
        commands.add(new JobCommand(messages, context));
        commands.add(new BountyCommand(messages, context));
        commands.add(new CoinsCommand(messages, context, currency));

        listeners.add(new JobListener(jobs, settings.jobs()));
        listeners.add(new CurrencyListener(currency));
        listeners.add(new BountyListener(bounties, messages, economy));
        listeners.add(new StreakListener(streaks));
    }

    /** Registers commands on the shared router. */
    public void registerCommands(CommandRouter router) {
        commands.forEach(router::register);
    }

    public void registerListeners(PluginManager manager) {
        listeners.forEach(listener -> manager.registerEvents(listener, context.plugin));
    }

    /** Detaches every listener; used when the plugin reloads its settings. */
    public void unregisterListeners() {
        listeners.forEach(org.bukkit.event.HandlerList::unregisterAll);
    }

    public EconomyContext context() {
        return context;
    }

    public CurrencyService currency() {
        return currency;
    }

    public StreakService streaks() {
        return streaks;
    }
}
