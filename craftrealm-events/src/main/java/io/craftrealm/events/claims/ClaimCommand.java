package io.craftrealm.events.claims;

import io.craftrealm.api.model.Claim;
import io.craftrealm.api.service.ClaimService;
import io.craftrealm.core.command.BaseCommand;
import io.craftrealm.core.text.Messages;
import io.craftrealm.core.util.Scheduler;
import io.craftrealm.core.util.TimeUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /claim} — buy, list and remove land.
 *
 * <p>Selection is deliberately simple: two corners from the block you are standing
 * on, so there is no wand to lose and no left-click conflict with mining.</p>
 */
public final class ClaimCommand extends BaseCommand {

    private final ClaimService claims;
    private final ClaimServiceImpl impl;
    private final io.craftrealm.core.ui.GuiTheme theme;

    public ClaimCommand(Messages messages, io.craftrealm.core.ui.GuiTheme theme,
                        ClaimService claims, ClaimServiceImpl impl) {
        super(messages);
        this.theme = theme;
        this.claims = claims;
        this.impl = impl;
    }

    @Override
    public String name() {
        return "claim";
    }

    @Override
    public List<String> aliases() {
        return List.of("claims", "land");
    }

    @Override
    public String permission() {
        return "craftrealm.claims";
    }

    @Override
    public String usage() {
        return "/claim <create|list|info|remove>";
    }

    @Override
    public String description() {
        return "Buy and manage land claims";
    }

    @Override
    public boolean playerOnly() {
        return true;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length == 0) {
            new io.craftrealm.events.gui.ClaimsGui(impl, messages, theme, player).open();
            return;
        }
        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "create", "buy" -> create(player, args);
            case "list" -> list(player);
            case "info" -> info(player);
            case "remove", "delete", "abandon" -> remove(player, args);
            default -> messages.send(player, "command.usage", "usage", usage());
        }
    }

    private void create(Player player, String[] args) {
        if (args.length < 3) {
            messages.send(player, "command.usage", "usage", "/claim create <radius> [name]");
            return;
        }
        int radius;
        try {
            radius = Math.max(1, Math.min(64, Integer.parseInt(args[1])));
        } catch (NumberFormatException exception) {
            messages.send(player, "command.invalid-number", "input", args[1]);
            return;
        }
        int centerX = player.getLocation().getBlockX();
        int centerZ = player.getLocation().getBlockZ();
        String world = player.getWorld().getName();
        int area = (radius * 2 + 1) * (radius * 2 + 1);
        double price = claims.price(area);
        messages.send(player, "claims.buying", "area", String.valueOf(area),
                "price", String.format(java.util.Locale.ROOT, "%,.2f", price));

        claims.create(player.getUniqueId(), world, centerX - radius, centerZ - radius,
                        centerX + radius, centerZ + radius)
                .thenAccept(claim -> Scheduler.sync(() -> {
                    messages.send(player, "claims.created",
                            "area", String.valueOf(claim.area()),
                            "upkeep", String.valueOf(claims.dailyUpkeep(claim)));
                }))
                .exceptionally(throwable -> {
                    String reason = throwable.getCause() == null ? "" : throwable.getCause().getMessage();
                    Scheduler.sync(() -> messages.send(player, "claims.failed", "reason",
                            reason == null ? "" : reason));
                    return null;
                });
    }

    private void list(Player player) {
        claims.claims(player.getUniqueId()).thenAccept(list -> Scheduler.sync(() -> {
            messages.send(player, "claims.list-header", "count", String.valueOf(list.size()));
            for (Claim claim : list) {
                messages.send(player, "claims.list-entry",
                        "id", String.valueOf(claim.id()),
                        "world", claim.world(),
                        "x", String.valueOf(claim.minX()),
                        "z", String.valueOf(claim.minZ()),
                        "area", String.valueOf(claim.area()),
                        "stage", String.valueOf(claims.decayStage(claim)),
                        "paid", TimeUtil.formatDate(claim.paidUntil()));
            }
        }));
    }

    private void info(Player player) {
        int x = player.getLocation().getBlockX();
        int z = player.getLocation().getBlockZ();
        claims.at(player.getWorld().getName(), x, z).thenAccept(claim -> Scheduler.sync(() -> {
            if (claim == null) {
                messages.send(player, "claims.free");
                return;
            }
            messages.send(player, "claims.info",
                    "owner", io.craftrealm.core.CraftRealmCore.nameOf(claim.owner()),
                    "area", String.valueOf(claim.area()),
                    "upkeep", String.valueOf(claims.dailyUpkeep(claim)),
                    "paid", TimeUtil.formatDate(claim.paidUntil()));
        }));
    }

    private void remove(Player player, String[] args) {
        long id;
        try {
            id = args.length > 1 ? Long.parseLong(args[1]) : -1L;
        } catch (NumberFormatException exception) {
            messages.send(player, "command.invalid-number", "input", args[1]);
            return;
        }
        claims.delete(player.getUniqueId(), id).thenAccept(deleted -> Scheduler.sync(() ->
                messages.send(player, deleted ? "claims.removed" : "claims.not-found")));
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return filter(List.of("create", "list", "info", "remove"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("create")) {
            return filter(new ArrayList<>(List.of("2", "4", "8")), args[1]);
        }
        return List.of();
    }
}
