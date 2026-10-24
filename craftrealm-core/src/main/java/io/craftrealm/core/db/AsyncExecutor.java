package io.craftrealm.core.db;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * The plugin's dedicated I/O pool.
 *
 * <p>Deliberately separate from the Bukkit async scheduler: work queued here
 * survives a reload and can be drained deterministically on disable, which makes
 * shutdown ordering predictable and keeps database threads out of the tick
 * scheduler's accounting.</p>
 */
public final class AsyncExecutor implements AutoCloseable {

    private final ExecutorService pool;
    private final String name;
    private volatile boolean shuttingDown;

    public AsyncExecutor(String name, int threads) {
        this.name = name;
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, name + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((t, error) ->
                    System.err.println("[" + name + "] uncaught error on " + t.getName() + ": " + error));
            return thread;
        };
        this.pool = Executors.newFixedThreadPool(Math.max(1, threads), factory);
    }

    /** Queues work; the returned future completes on an I/O thread. */
    public <T> CompletableFuture<T> supply(Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (shuttingDown) {
            future.completeExceptionally(new IllegalStateException("storage pool is shut down"));
            return future;
        }
        pool.execute(() -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable throwable) {
                future.completeExceptionally(unwrap(throwable));
            }
        });
        return future;
    }

    /** Queues work and ignores the result. */
    public CompletableFuture<Void> run(Runnable runnable) {
        return supply(() -> {
            runnable.run();
            return null;
        });
    }

    /** Runs on the calling thread — used only during plugin startup. */
    public <T> T supplyBlocking(Supplier<T> supplier) {
        return supplier.get();
    }

    public boolean isShuttingDown() {
        return shuttingDown;
    }

    /**
     * Waits up to {@code timeoutSeconds} for queued work, then shuts the pool down.
     * Called from {@code onDisable} so pending balance writes are not lost.
     */
    public void shutdown(long timeoutSeconds) {
        shuttingDown = true;
        pool.shutdown();
        try {
            if (!pool.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        shutdown(10);
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
    }
}
