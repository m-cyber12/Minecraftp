package io.craftrealm.plugin;

import io.craftrealm.admin.AdminModule;
import io.craftrealm.api.CraftRealmAPI;
import io.craftrealm.core.CraftRealmCore;
import io.craftrealm.core.auction.AuctionServiceImpl;
import io.craftrealm.core.bank.BankServiceImpl;
import io.craftrealm.core.command.CommandRouter;
import io.craftrealm.core.config.ConfigLoader;
import io.craftrealm.core.config.Settings;
import io.craftrealm.core.db.Database;
import io.craftrealm.core.db.DatabaseSettings;
import io.craftrealm.core.db.Schema;
import io.craftrealm.core.economy.EconomyServiceImpl;
import io.craftrealm.core.economy.ShardServiceImpl;
import io.craftrealm.core.jobs.JobServiceImpl;
import io.craftrealm.core.market.MarketServiceImpl;
import io.craftrealm.core.text.MessageBundle;
import io.craftrealm.core.text.Messages;
import io.craftrealm.core.ui.GuiListener;
import io.craftrealm.core.ui.GuiTheme;
import io.craftrealm.core.util.Scheduler;
import io.craftrealm.economy.EconomyModule;
import io.craftrealm.events.EventModule;
import io.craftrealm.web.WebPanel;
import io.craftrealm.web.WebPanelSettings;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * The CraftRealm plugin entry point.
 *
 * <p>This class is deliberately thin: it opens the database, builds the settings and
 * message objects, hands them to the four feature modules and schedules the
 * periodic jobs. Everything else lives in a module and can be tested without a
 * running server.</p>
 *
 * <p>Startup order matters and is enforced here:</p>
 * <ol>
 *   <li>config → settings (nothing else can be built first)</li>
 *   <li>database → schema migration (the ledger must exist before any money moves)</li>
 *   <li>core services (economy, shards, bank, market, auctions, jobs)</li>
 *   <li>feature modules (economy items, admin, events)</li>
 *   <li>commands, listeners, tasks, web panel, Vault bridge</li>
 * </ol>
 */
public final class CraftRealmPlugin extends JavaPlugin {

    private Database database;
    private Settings settings;
    private Messages messages;
    private GuiTheme theme;

    private EconomyServiceImpl economyService;
    private ShardServiceImpl shardService;
    private BankServiceImpl bankService;
    private MarketServiceImpl marketService;
    private AuctionServiceImpl auctionService;
    private JobServiceImpl jobService;

    private EconomyModule economyModule;
    private AdminModule adminModule;
    private EventModule eventModule;
    private ApiImpl api;
    private WebPanel webPanel;
    private WebBridgeImpl webBridge;
    private VaultHook vaultHook;

    private CommandRouter router;
    private final List<Integer> tasks = new ArrayList<>();

    @Override
    public void onEnable() {
        CraftRealmCore.logger(getLogger());
        Scheduler.init(this);

        saveDefaultConfig();
        saveLanguageFiles();

        this.settings = ConfigLoader.load(getConfig());
        this.messages = loadMessages();
        this.theme = new GuiTheme(settings.theme());

        try {
            this.database = openDatabase();
            Schema.apply(database);
        } catch (SQLException exception) {
            getLogger().severe("Could not open the database: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        buildServices();
        buildModules();
        registerCommands();
        registerListeners();
        scheduleTasks();
        startWebPanel();
        registerVault();
        registerApi();

        getLogger().info("CraftRealm " + getPluginMeta().getVersion() + " enabled — "
                + settings.economy().currencyName() + " economy ready.");
    }

    @Override
    public void onDisable() {
        tasks.forEach(Scheduler::cancel);
        tasks.clear();

        if (webPanel != null) {
            webPanel.stop();
        }
        if (webBridge != null) {
            webBridge.close();
        }
        if (vaultHook != null) {
            vaultHook.unregister();
        }
        if (api != null) {
            io.craftrealm.api.CraftRealm.unregister(api);
        }
        if (marketService != null) {
            // Persist the current prices so a restart does not reset the market.
            marketService.allQuotes().thenAccept(quotes -> {
                if (database != null) {
                    marketService.tickRecovery().join();
                }
            });
        }
        if (database != null) {
            database.close();
        }
        getLogger().info("CraftRealm disabled.");
    }

    // ---------------------------------------------------------------- startup

    private Database openDatabase() throws SQLException {
        Settings.Storage storage = settings.storage();
        DatabaseSettings databaseSettings = storage.mysql()
                ? DatabaseSettings.mysql(storage.host(), storage.port(), storage.database(),
                storage.username(), storage.password(), storage.properties(), storage.poolSize(),
                storage.threads())
                // A relative SQLite path belongs to the plugin, not to the server's
                // working directory: resolve it against plugins/CraftRealm/ so a
                // restart from a different directory cannot silently create a
                // second, empty ledger.
                : DatabaseSettings.sqlite(databaseFile(storage.file()), storage.threads());
        return Database.open(databaseSettings, getLogger());
    }

    /** Absolute path of the SQLite file, creating the parent folder if needed. */
    private String databaseFile(String configured) {
        File file = new File(configured == null ? "craftrealm.db" : configured);
        if (!file.isAbsolute()) {
            file = new File(getDataFolder(), file.getPath());
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            getLogger().warning("Could not create " + parent.getPath() + " — SQLite may fail to open.");
        }
        return file.getAbsolutePath();
    }

    private void buildServices() {
        economyService = new EconomyServiceImpl(database, settings.economy());
        shardService = new ShardServiceImpl(database);
        bankService = new BankServiceImpl(database, economyService, settings.bank());
        marketService = new MarketServiceImpl(database, settings.market());
        marketService.seed(settings.catalogue().basePrices());
        marketService.retainOnly(settings.catalogue().basePrices().keySet());
        marketService.warmUp();
        auctionService = new AuctionServiceImpl(database, economyService, settings.auction());
        jobService = new JobServiceImpl(database, economyService, settings.jobs());
    }

    private void buildModules() {
        economyModule = new EconomyModule(this, settings, messages, theme, economyService, shardService,
                bankService, marketService, auctionService, jobService, database,
                new io.craftrealm.core.repo.ProgressionRepository(database));
        adminModule = new AdminModule(this, settings, messages, theme, economyService, shardService,
                marketService, database, report -> {
            getServer().getOnlinePlayers().stream()
                    .filter(player -> player.hasPermission("craftrealm.staff.reports"))
                    .forEach(player -> messages.send(player, "report.staff-alert",
                            "reporter", report.reporterName(),
                            "target", report.targetName()));
        }, this::notifyDiscord);
        eventModule = new EventModule(this, settings, messages, theme, economyService, shardService,
                database);
        eventModule.warmUp();
    }

    private void registerCommands() {
        router = new CommandRouter(messages);
        economyModule.registerCommands(router);
        adminModule.registerCommands(router);
        eventModule.registerCommands(router);
        router.register(new RootCommand(this));

        bind("craftrealm", "cr", "balance", "bal", "pay", "baltop", "shop", "sell", "bank",
                "auction", "ah", "jobs", "job", "bounty", "coins", "cash", "note",
                "admin", "controlroom", "staff", "punish", "warn", "mute", "kick", "ban",
                "unban", "pardon", "vanish", "v", "staffchat", "sc", "report", "reports",
                "notes", "audit", "claim", "claims", "land", "chestshop", "myshops", "quests",
                "quest", "cosmetics", "cosmetic", "titles", "season", "seasons", "leaderboard",
                "events", "event", "treasure", "hunt");
    }

    private void bind(String... labels) {
        for (String label : labels) {
            PluginCommand command = getCommand(label);
            if (command == null) {
                getLogger().fine("No plugin.yml entry for /" + label + " (skipped)");
                continue;
            }
            command.setExecutor(router);
            command.setTabCompleter(router);
        }
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new GuiListener(), this);
        getServer().getPluginManager().registerEvents(new ConnectionListener(this), this);
        economyModule.registerListeners(getServer().getPluginManager());
        adminModule.registerListeners(getServer().getPluginManager());
        eventModule.registerListeners(getServer().getPluginManager());
    }

    private void scheduleTasks() {
        // Bank interest: hourly, off the tick thread.
        tasks.add(Scheduler.asyncTimer(() -> bankService.runInterestCycle(), 20L * 60 * 5, 20L * 60 * 60));
        // Loan defaults: every six hours.
        tasks.add(Scheduler.asyncTimer(() -> bankService.processOverdue(), 20L * 60 * 10, 20L * 60 * 60 * 6));
        // Auction expiry: every minute.
        tasks.add(Scheduler.asyncTimer(() -> auctionService.expireDue(), 20L * 30, 20L * 60));
        // Market recovery: every ten minutes.
        tasks.add(Scheduler.asyncTimer(() -> marketService.tickRecovery(), 20L * 60, 20L * 60 * 10));
        eventModule.startTasks();
    }

    private void startWebPanel() {
        WebPanelSettings webSettings = new WebPanelSettings(settings.web().enabled(),
                settings.web().host(), settings.web().port(), settings.web().tokens(),
                settings.web().sessionMinutes(), settings.web().allowConsole(),
                settings.web().maxConsoleLines());
        if (!webSettings.enabled()) {
            return;
        }
        webBridge = new WebBridgeImpl(this);
        webPanel = new WebPanel(webBridge, webSettings, message -> getLogger().info(message));
        try {
            webPanel.start();
        } catch (RuntimeException exception) {
            getLogger().warning("Web panel failed to start: " + exception.getMessage());
        }
    }

    private void registerVault() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            getLogger().info("Vault not found — the economy is still available via /bal and the API.");
            return;
        }
        vaultHook = new VaultHook(this, settings, economyService);
        if (vaultHook.register()) {
            getLogger().info("Registered with Vault as the economy provider.");
        }
    }

    private void registerApi() {
        api = new ApiImpl(this, economyService, shardService, bankService, marketService,
                auctionService, jobService, eventModule, adminModule);
        io.craftrealm.api.CraftRealm.register(api);
        getServer().getServicesManager().register(CraftRealmAPI.class, api, this,
                org.bukkit.plugin.ServicePriority.Normal);
    }

    // ----------------------------------------------------------------- messages

    private Messages loadMessages() {
        String locale = settings.messages().locale();
        String fallback = settings.messages().fallback();
        File languageFolder = new File(getDataFolder(), "lang");
        MessageBundle fallbackBundle = MessageBundle.load(new File(languageFolder, fallback + ".yml"),
                fallback);
        MessageBundle primary = MessageBundle.load(new File(languageFolder, locale + ".yml"), locale)
                .mergedWith(fallbackBundle);
        return new Messages(primary, settings.messages().prefix());
    }

    /** Ships the bundled language files on first start. */
    private void saveLanguageFiles() {
        for (String locale : new String[]{"en_US", "fa_IR"}) {
            File file = new File(getDataFolder(), "lang/" + locale + ".yml");
            if (!file.exists()) {
                saveResource("lang/" + locale + ".yml", false);
            }
        }
    }

    /** Posts to the configured Discord webhook, if any. */
    public void notifyDiscord(String message) {
        DiscordNotifier.send(settings.discord(), message, getLogger());
    }

    // ------------------------------------------------------------------ reload

    /** Re-reads config.yml and language files; used by {@code /craftrealm reload}. */
    public void reloadEverything() {
        reloadConfig();
        settings = ConfigLoader.load(getConfig());
        messages.update(loadMessages().bundle(), settings.messages().prefix());
        getServer().getPluginManager().callEvent(new io.craftrealm.api.event.CraftRealmReloadEvent());
    }

    // ------------------------------------------------------------------ access

    public Settings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public GuiTheme theme() {
        return theme;
    }

    public Database database() {
        return database;
    }

    public EconomyServiceImpl economyService() {
        return economyService;
    }

    public ShardServiceImpl shardService() {
        return shardService;
    }

    public BankServiceImpl bankService() {
        return bankService;
    }

    public MarketServiceImpl marketService() {
        return marketService;
    }

    public AuctionServiceImpl auctionService() {
        return auctionService;
    }

    public JobServiceImpl jobService() {
        return jobService;
    }

    public EconomyModule economyModule() {
        return economyModule;
    }

    public AdminModule adminModule() {
        return adminModule;
    }

    public EventModule eventModule() {
        return eventModule;
    }

    public WebPanel webPanel() {
        return webPanel;
    }
}
