package io.craftrealm.admin;

import io.craftrealm.admin.health.HealthMonitor;
import io.craftrealm.admin.health.LagFinder;
import io.craftrealm.admin.punish.PunishmentService;
import io.craftrealm.admin.staff.AuditService;
import io.craftrealm.admin.staff.ReportService;
import io.craftrealm.admin.staff.StaffTools;
import io.craftrealm.core.repo.AccountRepository;
import io.craftrealm.core.repo.MarketRepository;
import io.craftrealm.core.repo.PlayerRepository;
import io.craftrealm.core.repo.StaffRepository;

/**
 * The admin module's wired service set, passed to every menu and command.
 */
public final class AdminServices {

    public final AdminContext context;
    public final PlayerRepository players;
    public final StaffRepository staffRepository;
    public final AccountRepository accounts;
    public final MarketRepository market;
    public final AuditService audit;
    public final PunishmentService punishments;
    public final ReportService reports;
    public final StaffTools staffTools;
    public final HealthMonitor health;
    public final LagFinder lagFinder;
    public final AdminServiceImpl admin;

    public AdminServices(AdminContext context, PlayerRepository players, StaffRepository staffRepository,
                         AccountRepository accounts, MarketRepository market, AuditService audit,
                         PunishmentService punishments, ReportService reports,
                         StaffTools staffTools, HealthMonitor health, LagFinder lagFinder,
                         AdminServiceImpl admin) {
        this.context = context;
        this.players = players;
        this.staffRepository = staffRepository;
        this.accounts = accounts;
        this.market = market;
        this.audit = audit;
        this.punishments = punishments;
        this.reports = reports;
        this.staffTools = staffTools;
        this.health = health;
        this.lagFinder = lagFinder;
        this.admin = admin;
    }

    public String money(double amount) {
        return context.money(amount);
    }
}
