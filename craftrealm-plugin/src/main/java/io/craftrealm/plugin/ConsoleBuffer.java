package io.craftrealm.plugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * A bounded ring buffer of the server log for the web console view.
 *
 * <p>Attaches a {@link Handler} to the root Minecraft logger and keeps the last N
 * formatted lines. Bounded on purpose: an unbounded buffer would leak a few hundred
 * megabytes over a long uptime.</p>
 */
public final class ConsoleBuffer extends Handler {

    private final int capacity;
    private final Deque<String> lines = new ArrayDeque<>();

    public ConsoleBuffer(int capacity) {
        this.capacity = Math.max(20, capacity);
    }

    /** Attaches a buffer to the server logger. */
    public static ConsoleBuffer attach(int capacity) {
        ConsoleBuffer buffer = new ConsoleBuffer(capacity);
        buffer.setFormatter(new java.util.logging.SimpleFormatter());
        Logger.getLogger("").addHandler(buffer);
        return buffer;
    }

    public static void detach(ConsoleBuffer buffer) {
        if (buffer != null) {
            Logger.getLogger("").removeHandler(buffer);
        }
    }

    @Override
    public synchronized void publish(LogRecord record) {
        if (record == null || record.getMessage() == null) {
            return;
        }
        String line = getFormatter() == null ? record.getMessage() : getFormatter().formatMessage(record);
        lines.addLast(line);
        while (lines.size() > capacity) {
            lines.removeFirst();
        }
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }

    /** The newest {@code limit} lines, oldest first. */
    public synchronized List<String> tail(int limit) {
        int wanted = Math.max(1, Math.min(limit, lines.size()));
        List<String> all = new ArrayList<>(lines);
        return all.subList(Math.max(0, all.size() - wanted), all.size());
    }
}
