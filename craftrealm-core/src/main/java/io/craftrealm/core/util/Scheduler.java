package io.craftrealm.core.util;

import io.craftrealm.core.CraftRealmCore;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * The bridge between the I/O pool and the tick thread.
 *
 * <p>Rule of thumb enforced across the codebase: anything that touches
 * {@code Player}, {@code World}, {@code Inventory} or the scheduler goes through
 * here so a database callback can never mutate game state off-thread.</p>
 */
public final class Scheduler {

    private static Plugin plugin;

    private Scheduler() {
    }

    /** Called once from the plugin's {@code onEnable}. */
    public static void init(Plugin owningPlugin) {
        plugin = owningPlugin;
    }

    public static Plugin plugin() {
        return plugin;
    }

    /**
     * Runs on the next tick. Silently drops the task when the server is shutting
     * down, which keeps asynchronous callbacks from throwing on disable.
     */
    public static void sync(Runnable task) {
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            safeRun(task);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> safeRun(task));
        }
    }

    /** Runs after {@code delayTicks}. */
    public static void syncLater(Runnable task, long delayTicks) {
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> safeRun(task), Math.max(1L, delayTicks));
    }

    /**
     * Runs on the tick thread and reports whether the task was accepted.
     * Used by the event bridge so a cancelled task can never leave a
     * {@link java.util.concurrent.CompletableFuture} hanging.
     */
    public static boolean trySync(Runnable task) {
        if (plugin == null || !plugin.isEnabled()) {
            return false;
        }
        if (Bukkit.isPrimaryThread()) {
            safeRun(task);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> safeRun(task));
        }
        return true;
    }

    /** Repeating synchronous task, in ticks. */
    public static int syncTimer(Runnable task, long delayTicks, long periodTicks) {
        if (plugin == null) {
            return -1;
        }
        return Bukkit.getScheduler()
                .runTaskTimer(plugin, () -> safeRun(task), Math.max(1L, delayTicks), Math.max(1L, periodTicks))
                .getTaskId();
    }

    /** Repeating asynchronous task, in ticks (20 ticks ≈ 1s). */
    public static int asyncTimer(Runnable task, long delayTicks, long periodTicks) {
        if (plugin == null) {
            return -1;
        }
        return Bukkit.getScheduler()
                .runTaskTimerAsynchronously(plugin, () -> safeRun(task), Math.max(1L, delayTicks), Math.max(1L, periodTicks))
                .getTaskId();
    }

    /** Runs once, off the main thread. */
    public static void async(Runnable task) {
        if (plugin == null) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> safeRun(task));
    }

    public static void cancel(int taskId) {
        if (taskId > 0) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
    }

    private static void safeRun(Runnable task) {
        try {
            task.run();
        } catch (Throwable throwable) {
            CraftRealmCore.logger().warning("Unhandled task error in CraftRealm: " + throwable.getMessage());
            throwable.printStackTrace();
        }
    }
}
