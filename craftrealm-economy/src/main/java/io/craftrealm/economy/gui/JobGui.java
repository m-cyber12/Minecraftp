package io.craftrealm.economy.gui;

import io.craftrealm.api.model.JobProgress;
import io.craftrealm.api.model.JobType;
import io.craftrealm.core.jobs.JobLevelCurve;
import io.craftrealm.core.ui.Gui;
import io.craftrealm.core.ui.ItemBuilder;
import io.craftrealm.economy.EconomyContext;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Job overview: level, XP bar, income multiplier and today's capped earnings.
 *
 * <p>Everything shown here comes from the in-memory job cache, so the menu opens
 * instantly and never blocks the tick thread.</p>
 */
public final class JobGui extends Gui {

    private static final JobType[] JOBS = JobType.values();
    private static final int[] SLOTS = {11, 13, 15, 20, 24};

    private final EconomyContext context;

    public JobGui(EconomyContext context, Player player) {
        super(player, context.theme, 5, context.messages.get("jobs.title"));
        this.context = context;
    }

    @Override
    public void render() {
        long xpBase = context.settings.jobs().xpBase();
        double growth = context.settings.jobs().xpGrowth();

        for (int index = 0; index < JOBS.length && index < SLOTS.length; index++) {
            JobType job = JOBS[index];
            JobProgress progress = context.jobs.cached(viewer.getUniqueId(), job);
            long needed = JobLevelCurve.xpForLevel(progress.level(), xpBase, growth);
            long remaining = Math.max(0L, needed - progress.xp());
            double multiplier = context.jobs.multiplier(progress.level());
            double earned = context.jobs.earnedToday(viewer.getUniqueId(), job);

            List<String> lore = new ArrayList<>(context.messages.bundle().lines("jobs.entry.lore",
                    "level", String.valueOf(progress.level()),
                    "xp", String.valueOf(progress.xp()),
                    "needed", String.valueOf(remaining),
                    "multiplier", String.format(java.util.Locale.ROOT, "%.2f", multiplier),
                    "earned", context.money(earned),
                    "cap", context.money(context.settings.jobs().dailyCap())));
            lore.add(0, context.messages.raw("jobs.entry.unit", "unit", job.unit()));
            lore.add(1, bar(progress.xp(), needed));

            set(SLOTS[index], ItemBuilder.of(jobMaterial(job))
                    .name(context.messages.raw("jobs." + job.key() + ".name"))
                    .lore(lore)
                    .glint(progress.level() >= 10)
                    .build());
        }

        set(31, ItemBuilder.of(Material.PAPER)
                .name(context.messages.raw("jobs.how.title"))
                .lore(context.messages.bundle().lines("jobs.how.lore"))
                .build());
        closeButton();
        fillEmpty();
    }

    /** A blocky progress bar, like the XP bar, e.g. {@code ▉▉▉▉▉░░░░░}. */
    static String bar(long value, long max) {
        int segments = 10;
        double ratio = max <= 0 ? 1.0 : Math.min(1.0, (double) value / (double) max);
        int filled = (int) Math.round(ratio * segments);
        StringBuilder builder = new StringBuilder("<#2fbf71>");
        builder.append("▉".repeat(filled));
        builder.append("<#36393d>");
        builder.append("▉".repeat(segments - filled));
        return builder.toString();
    }

    private Material jobMaterial(JobType job) {
        return switch (job) {
            case MINER -> Material.DIAMOND_PICKAXE;
            case FARMER -> Material.WHEAT;
            case HUNTER -> Material.IRON_SWORD;
            case FISHER -> Material.FISHING_ROD;
            case BUILDER -> Material.BRICKS;
        };
    }
}
