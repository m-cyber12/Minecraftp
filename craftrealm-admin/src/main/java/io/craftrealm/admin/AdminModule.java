package io.craftrealm.admin;

import io.craftrealm.admin.command.AdminCommand;
import io.craftrealm.admin.command.AuditCommand;
import io.craftrealm.admin.command.NotesCommand;
import io.craftrealm.admin.command.PunishCommand;
import io.craftrealm.admin.command.ReportCommand;
import io.craftrealm.admin.command.ReportsCommand;
import io.craftrealm.admin.command.ShortcutPunishCommand;
import io.craftrealm.admin.command.StaffChatCommand;
import io.craftrealm.admin.command.UnbanCommand;
import io.craftrealm.admin.command.VanishCommand;
import io.craftrealm.admin.health.HealthMonitor;
import io.craftrealm.admin.health.LagFinder;
import io.craftrealm.admin.listener.ModerationListener;
import io.craftrealm.admin.punish.PunishmentService;
import io.craftrealm.admin.staff.AuditService;
import io.craftrealm.admin.staff.ReportService;
import io.craftrealm.admin.staff.StaffTools;
import io.craftrealm.api.model.PunishmentType;
import io.craftrealm.api.model.Report;
import io.craftrealm.api.service.AdminService;
import io.craftrealm.api.service.EconomyService;
import io.craftrealm.api.service.MarketService;
import io.craftrealm.api.service.ShardService;
import io.craftrealm.core.command.CommandRouter;
import io.craftrealm.core.command.SubCommand;
import io.craftrealm.core.config.Settings;
import io.craftrealm.core.db.Database;
import io.craftrealm.core.repo.AccountRepository;
import io.craftrealm.core.repo.MarketRepository;
import io.craftrealm.core.repo.PlayerRepository;
import io.craftrealm.core.repo.StaffRepository;
import io.craftrealm.core.text.Messages;
import io.craftrealm.core.ui.GuiTheme;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Wires the admin module.
 *
 * <p>Everything is built here and nowhere else, so the plugin class stays a thin
 * composition root and every menu can be constructed from {@link AdminServices}.</p>
 */
public final class AdminModule {

    private final AdminServices services;
    private final AdminService adminService;
    private final List<SubCommand> commands = new ArrayList<>();
    private final List<Listener> listeners = new ArrayList<>();

    public AdminModule(Plugin plugin, Settings settings, Messages messages, GuiTheme theme,
                       EconomyService economy, ShardService shards, MarketService market,
                       Database database, Consumer<Report> reportHook, Consumer<String> discordHook) {
        AdminContext context = new AdminContext(plugin, settings, messages, theme, economy, shards,
                market, database);

        PlayerRepository players = new PlayerRepository(database);
        StaffRepository staffRepository = new StaffRepository(database);
        AccountRepository accounts = new AccountRepository(database);
        MarketRepository marketRepository = new MarketRepository(database);

        AuditService audit = new AuditService(staffRepository);
        PunishmentService punishments = new PunishmentService(staffRepository, players,
                settings.admin(), settings.staff(), messages, audit);
        ReportService reports = new ReportService(staffRepository, settings.admin(), messages, audit, reportHook);
        StaffTools staffTools = new StaffTools(plugin, messages, settings.staff());

        LagFinder lagFinder = new LagFinder(settings.admin().lagEntityThreshold(),
                settings.admin().lagScanRadiusChunks(), settings.admin().maxCleanupChunks());
        HealthMonitor health = new HealthMonitor(lagFinder);

        AdminServiceImpl impl = new AdminServiceImpl(context, players, staffRepository, punishments,
                reports, audit);
        this.adminService = impl;
        this.services = new AdminServices(context, players, staffRepository, accounts, marketRepository,
                audit, punishments, reports, staffTools, health, lagFinder, impl);

        commands.add(new AdminCommand(messages, services));
        commands.add(new PunishCommand(messages, services));
        commands.add(new ShortcutPunishCommand(messages, services, "warn", PunishmentType.WARN, false));
        commands.add(new ShortcutPunishCommand(messages, services, "mute", PunishmentType.MUTE, true));
        commands.add(new ShortcutPunishCommand(messages, services, "kick", PunishmentType.KICK, false));
        commands.add(new ShortcutPunishCommand(messages, services, "ban", PunishmentType.BAN, false));
        commands.add(new UnbanCommand(messages, services));
        commands.add(new VanishCommand(messages, services));
        commands.add(new StaffChatCommand(messages, services));
        commands.add(new ReportCommand(messages, services));
        commands.add(new ReportsCommand(messages, services));
        commands.add(new NotesCommand(messages, services));
        commands.add(new AuditCommand(messages, services));

        listeners.add(new ModerationListener(punishments, staffTools, messages, settings.admin(),
                players, discordHook));
    }

    public void registerCommands(CommandRouter router) {
        commands.forEach(router::register);
    }

    public void registerListeners(PluginManager manager) {
        listeners.forEach(listener -> manager.registerEvents(listener, services.context.plugin));
    }

    public AdminServices services() {
        return services;
    }

    public AdminService adminService() {
        return adminService;
    }

    public PunishmentService punishments() {
        return services.punishments;
    }

    public StaffTools staffTools() {
        return services.staffTools;
    }
}
