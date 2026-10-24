package io.craftrealm.events;

import io.craftrealm.api.service.ClaimService;
import io.craftrealm.api.service.CosmeticService;
import io.craftrealm.api.service.EconomyService;
import io.craftrealm.api.service.EventService;
import io.craftrealm.api.service.QuestService;
import io.craftrealm.api.service.SeasonService;
import io.craftrealm.api.service.ShardService;
import io.craftrealm.core.command.CommandRouter;
import io.craftrealm.core.command.SubCommand;
import io.craftrealm.core.config.Settings;
import io.craftrealm.core.db.Database;
import io.craftrealm.core.repo.ProgressionRepository;
import io.craftrealm.core.repo.WorldRepository;
import io.craftrealm.core.text.Messages;
import io.craftrealm.core.ui.GuiTheme;
import io.craftrealm.core.util.Scheduler;
import io.craftrealm.events.claims.ClaimCommand;
import io.craftrealm.events.claims.ClaimListener;
import io.craftrealm.events.claims.ClaimServiceImpl;
import io.craftrealm.events.command.ChestShopCommand;
import io.craftrealm.events.command.CosmeticsCommand;
import io.craftrealm.events.command.EventsCommand;
import io.craftrealm.events.command.QuestsCommand;
import io.craftrealm.events.command.SeasonCommand;
import io.craftrealm.events.command.TreasureCommand;
import io.craftrealm.events.cosmetics.CosmeticServiceImpl;
import io.craftrealm.events.listener.CosmeticListener;
import io.craftrealm.events.listener.GameplayListener;
import io.craftrealm.events.quests.QuestServiceImpl;
import io.craftrealm.events.seasons.SeasonServiceImpl;
import io.craftrealm.events.shops.ChestShopListener;
import io.craftrealm.events.shops.ChestShopService;
import io.craftrealm.events.treasure.TreasureService;
import io.craftrealm.events.world.EventServiceImpl;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Wires the "creative extras" module: claims, chest shops, treasure, quests,
 * seasons, cosmetics and the world-event engine — plus their scheduled tasks.
 *
 * <p>Everything is created once here; the plugin class only calls
 * {@link #registerCommands}, {@link #registerListeners} and {@link #startTasks}.
 * Services are exposed so the web panel can read them without reaching into the
 * plugin.</p>
 */
public final class EventModule {

    private final Plugin plugin;
    private final Messages messages;
    private final GuiTheme theme;

    private final ClaimServiceImpl claims;
    private final ChestShopService shops;
    private final TreasureService treasures;
    private final QuestServiceImpl quests;
    private final SeasonServiceImpl seasons;
    private final CosmeticServiceImpl cosmetics;
    private final EventServiceImpl events;
    private final CosmeticListener cosmeticListener;

    private final List<SubCommand> commands = new ArrayList<>();
    private final List<Listener> listeners = new ArrayList<>();

    public EventModule(Plugin plugin, Settings settings, Messages messages, GuiTheme theme,
                       EconomyService economy, ShardService shards, Database database) {
        this.plugin = plugin;
        this.messages = messages;
        this.theme = theme;

        WorldRepository world = new WorldRepository(database);
        ProgressionRepository progression = new ProgressionRepository(database);

        this.claims = new ClaimServiceImpl(economy, world, settings.claims());
        this.shops = new ChestShopService(economy, world, settings.chestShops());
        this.treasures = new TreasureService(world, shards, messages, settings.treasures());
        this.quests = new QuestServiceImpl(economy, shards, progression, settings.quests());
        this.seasons = new SeasonServiceImpl(progression, economy, shards, settings.seasons());
        this.cosmetics = new CosmeticServiceImpl(shards, progression, settings.cosmetics());
        this.events = new EventServiceImpl(settings.events(), messages);
        this.cosmeticListener = new CosmeticListener(cosmetics);

        commands.add(new ClaimCommand(messages, theme, claims, claims));
        commands.add(new ChestShopCommand(messages, shops, economy));
        commands.add(new QuestsCommand(messages, quests, economy, theme));
        commands.add(new CosmeticsCommand(messages, cosmetics, shards, theme));
        commands.add(new SeasonCommand(messages, seasons, economy, theme));
        commands.add(new EventsCommand(messages, events, theme));
        commands.add(new TreasureCommand(messages, treasures));

        listeners.add(new ClaimListener(claims, messages));
        listeners.add(new ChestShopListener(shops, economy, messages, theme));
        listeners.add(new GameplayListener(quests, treasures, messages));
        listeners.add(cosmeticListener);
    }

    /** Loads the caches that the protection listeners depend on. */
    public void warmUp() {
        claims.warmUp();
        shops.warmUp();
        treasures.warmUp();
        seasons.ensureRunning();
    }

    public void registerCommands(CommandRouter router) {
        commands.forEach(router::register);
    }

    public void registerListeners(PluginManager manager) {
        listeners.forEach(listener -> manager.registerEvents(listener, plugin));
    }

    /** Starts the recurring work. Everything is async-safe or runs off-tick. */
    public void startTasks() {
        // Claim upkeep once a day (20 min after startup to avoid login storms).
        Scheduler.asyncTimer(() -> claims.runUpkeep(), 20L * 60 * 20, 20L * 60 * 60 * 24);
        // Quests rotate every ten minutes; cheap and makes "new day" feel instant.
        Scheduler.asyncTimer(() -> quests.rotate(), 20L * 60 * 5, 20L * 60 * 10);
        // Seasons check hourly.
        Scheduler.asyncTimer(() -> seasons.tick(), 20L * 60, 20L * 60 * 60);
        // Random events.
        long interval = Math.max(1, 20L * 60 * 5);
        Scheduler.asyncTimer(() -> events.maybeStartRandom(), interval, interval);
        // Particle trails, twice a second, on the tick thread.
        Scheduler.syncTimer(() -> cosmeticListener.tickTrails(), 20L, 10L);
    }

    public ClaimService claims() {
        return claims;
    }

    public ChestShopService shops() {
        return shops;
    }

    public TreasureService treasures() {
        return treasures;
    }

    public QuestService quests() {
        return quests;
    }

    public SeasonService seasons() {
        return seasons;
    }

    public CosmeticService cosmetics() {
        return cosmetics;
    }

    public EventService events() {
        return events;
    }
}
