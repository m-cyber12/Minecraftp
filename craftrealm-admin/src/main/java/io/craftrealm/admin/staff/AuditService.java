package io.craftrealm.admin.staff;

import io.craftrealm.api.model.AuditEntry;
import io.craftrealm.core.repo.StaffRepository;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Every staff action is written here — balance edits, punishments, teleports,
 * inventory snooping, web logins, console commands.
 *
 * <p>The audit log is append-only by design: there is no delete path in the
 * repository, and the Control Room only reads it.</p>
 */
public final class AuditService {

    private final StaffRepository repository;

    public AuditService(StaffRepository repository) {
        this.repository = repository;
    }

    /** Records an action; {@code staff} may be {@code null} for console/system actors. */
    public CompletableFuture<Void> record(UUID staff, String action, String target, String detail) {
        String name = staff == null ? "CONSOLE" : nameOf(staff);
        return repository.audit(staff, name, action, target, detail);
    }

    public CompletableFuture<Void> record(CommandSender sender, String action, String target, String detail) {
        if (sender instanceof org.bukkit.entity.Player player) {
            return record(player.getUniqueId(), action, target, detail);
        }
        return record((UUID) null, action, target, detail);
    }

    public CompletableFuture<List<AuditEntry>> recent(int limit) {
        return repository.audit(limit);
    }

    private static String nameOf(UUID uuid) {
        var player = Bukkit.getPlayer(uuid);
        if (player != null) {
            return player.getName();
        }
        String offline = Bukkit.getOfflinePlayer(uuid).getName();
        return offline != null ? offline : io.craftrealm.core.CraftRealmCore.nameOf(uuid);
    }
}
