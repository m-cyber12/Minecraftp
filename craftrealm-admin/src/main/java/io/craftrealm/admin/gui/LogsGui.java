package io.craftrealm.admin.gui;

import io.craftrealm.admin.AdminServices;
import io.craftrealm.api.model.AuditEntry;
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
 * The audit log, newest first.
 *
 * <p>Read only on purpose: the tab-completion text of every entry is the staff
 * member's own name, so an entry can never be edited to hide a mistake.</p>
 */
public final class LogsGui extends Gui {

    private static final int PER_PAGE = 45;

    private final AdminServices services;
    private final int page;
    private List<AuditEntry> entries = List.of();

    public LogsGui(AdminServices services, Player player, int page) {
        super(player, services.context.theme, 6, services.context.messages.get("admin.logs.title"));
        this.services = services;
        this.page = page;
    }

    @Override
    public void open() {
        services.audit.recent(500).thenAccept(list -> Scheduler.sync(() -> {
            entries = list;
            super.open();
        }));
    }

    @Override
    public void render() {
        var messages = services.context.messages;
        int pages = Pagination.pages(entries.size(), PER_PAGE);
        int current = Pagination.clamp(page, pages);
        List<AuditEntry> shown = Pagination.slice(entries, current, PER_PAGE);

        for (int index = 0; index < shown.size(); index++) {
            AuditEntry entry = shown.get(index);
            set(index, ItemBuilder.of(materialFor(entry.action()))
                    .name(messages.raw("admin.logs.entry.name",
                            "action", entry.action(),
                            "staff", entry.staffName()))
                    .lore(lore(entry))
                    .build());
        }

        if (shown.isEmpty()) {
            set(22, ItemBuilder.of(Material.BOOK)
                    .name(messages.raw("admin.logs.empty"))
                    .build());
        }

        if (current > 0) {
            set(48, ItemBuilder.of(GuiTheme.Icon.PREVIOUS.material())
                    .name(messages.raw("gui.previous")).build(),
                    click -> new LogsGui(services, viewer, current - 1).open());
        }
        if (current + 1 < pages) {
            set(50, ItemBuilder.of(GuiTheme.Icon.NEXT.material())
                    .name(messages.raw("gui.next")).build(),
                    click -> new LogsGui(services, viewer, current + 1).open());
        }
        set(49, ItemBuilder.of(Material.PAPER)
                .name(messages.raw("gui.page",
                        "page", String.valueOf(current + 1), "pages", String.valueOf(pages)))
                .build());

        backButton(() -> new ControlRoomGui(services, viewer).open());
        closeButton();
        fillEmpty();
    }

    private List<Component> lore(AuditEntry entry) {
        List<Component> lines = new ArrayList<>(services.context.messages.bundle().lines(
                "admin.logs.entry.lore",
                "target", entry.target() == null ? "-" : entry.target(),
                "detail", entry.detail() == null ? "-" : entry.detail(),
                "when", TimeUtil.relative(entry.createdAt()),
                "id", String.valueOf(entry.id())));
        return lines;
    }

    /** Colour codes the icon by the risk of the action. */
    private static Material materialFor(String action) {
        if (action == null) {
            return Material.PAPER;
        }
        String upper = action.toUpperCase(java.util.Locale.ROOT);
        if (upper.contains("BAN") || upper.contains("KICK") || upper.contains("ROLLBACK")) {
            return Material.REDSTONE;
        }
        if (upper.contains("MUTE") || upper.contains("WARN") || upper.contains("FREEZE")) {
            return Material.LAVA_BUCKET;
        }
        if (upper.contains("BALANCE") || upper.contains("GIVE") || upper.contains("TAKE")) {
            return Material.GOLD_INGOT;
        }
        return Material.PAPER;
    }
}
