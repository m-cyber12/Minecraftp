package io.craftrealm.admin.gui;

import io.craftrealm.admin.AdminServices;
import io.craftrealm.api.model.Report;
import io.craftrealm.api.model.ReportStatus;
import io.craftrealm.core.config.Settings;
import io.craftrealm.core.ui.Gui;
import io.craftrealm.core.ui.GuiTheme;
import io.craftrealm.core.ui.ItemBuilder;
import io.craftrealm.core.ui.Pagination;
import io.craftrealm.core.util.Scheduler;
import io.craftrealm.core.util.TimeUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * The report queue, plus a read-only view of the warning escalation ladder so
 * staff know exactly what the next warning will trigger.
 *
 * <p>Left click resolves a report, right click rejects it. Both paths ask for the
 * resolution to be typed in chat, which keeps moderation honest.</p>
 */
public final class PunishmentsGui extends Gui {

    private static final int PER_PAGE = 36;

    private final AdminServices services;
    private final int page;
    private List<Report> reports = List.of();

    public PunishmentsGui(AdminServices services, Player player) {
        this(services, player, 0);
    }

    public PunishmentsGui(AdminServices services, Player player, int page) {
        super(player, services.context.theme, 6, services.context.messages.get("admin.punishments.title"));
        this.services = services;
        this.page = page;
    }

    @Override
    public void open() {
        services.reports.queue(ReportStatus.OPEN).thenAccept(list -> Scheduler.sync(() -> {
            reports = list;
            super.open();
        }));
    }

    @Override
    public void render() {
        var messages = services.context.messages;
        int pages = Pagination.pages(reports.size(), PER_PAGE);
        int current = Pagination.clamp(page, pages);
        List<Report> shown = Pagination.slice(reports, current, PER_PAGE);

        for (int index = 0; index < shown.size(); index++) {
            Report report = shown.get(index);
            set(index, ItemBuilder.of(Material.PAPER)
                    .name(messages.raw("admin.punishments.report.name",
                            "id", String.valueOf(report.id()),
                            "reporter", report.reporterName(),
                            "target", report.targetName()))
                    .lore(lore(report))
                    .build(), click -> {
                ReportStatus status = click.rightClick() ? ReportStatus.REJECTED : ReportStatus.RESOLVED;
                String resolution = click.rightClick() ? "rejected" : "handled by " + viewer.getName();
                services.reports.resolve(viewer.getUniqueId(), report.id(), status, resolution)
                        .thenAccept(done -> Scheduler.sync(() -> {
                            messages.send(viewer, done ? "admin.punishments.resolved"
                                    : "admin.punishments.failed", "id", String.valueOf(report.id()));
                            theme.play(viewer, done ? GuiTheme.Click.SUCCESS : GuiTheme.Click.DENY);
                            open();
                        }));
            });
        }

        if (shown.isEmpty()) {
            set(22, ItemBuilder.of(Material.CHEST)
                    .name(messages.raw("admin.punishments.empty"))
                    .build());
        }

        if (current > 0) {
            set(48, ItemBuilder.of(GuiTheme.Icon.PREVIOUS.material())
                    .name(messages.raw("gui.previous")).build(),
                    click -> new PunishmentsGui(services, viewer, current - 1).open());
        }
        if (current + 1 < pages) {
            set(50, ItemBuilder.of(GuiTheme.Icon.NEXT.material())
                    .name(messages.raw("gui.next")).build(),
                    click -> new PunishmentsGui(services, viewer, current + 1).open());
        }

        set(45, ItemBuilder.of(Material.NAME_TAG)
                .name(messages.raw("admin.punishments.ladder.name"))
                .lore(ladder())
                .build());

        backButton(() -> new ControlRoomGui(services, viewer).open());
        closeButton();
        fillEmpty();
    }

    private List<Component> lore(Report report) {
        return new ArrayList<>(services.context.messages.bundle().lines("admin.punishments.report.lore",
                "reason", report.reason(),
                "when", TimeUtil.relative(report.createdAt()),
                "id", String.valueOf(report.id())));
    }

    private List<Component> ladder() {
        var messages = services.context.messages;
        List<Component> lines = new ArrayList<>();
        for (Settings.EscalationRule rule : services.context.settings.staff().escalation()) {
            lines.add(messages.get("admin.punishments.ladder.entry",
                    "warnings", String.valueOf(rule.warnings()),
                    "action", rule.action().name(),
                    "duration", rule.durationMillis() <= 0 ? "-" : TimeUtil.formatDuration(rule.durationMillis())));
        }
        if (lines.isEmpty()) {
            lines.add(messages.get("admin.punishments.ladder.empty"));
        }
        return lines;
    }
}
