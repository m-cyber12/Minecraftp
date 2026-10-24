package io.craftrealm.core.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Duration parsing and human readable time formatting. Pure code, unit tested.
 */
public final class TimeUtil {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private TimeUtil() {
    }

    /**
     * Parses {@code 1d2h30m}, {@code 45m}, {@code 30s} or plain seconds.
     *
     * @return the duration in milliseconds
     * @throws IllegalArgumentException when the format is not understood
     */
    public static long parseDuration(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("empty duration");
        }
        String raw = input.trim().toLowerCase(Locale.ROOT);
        if (raw.matches("\\d+")) {
            return Long.parseLong(raw) * 1000L;
        }
        long total = 0L;
        int index = 0;
        boolean matchedAny = false;
        while (index < raw.length()) {
            int start = index;
            while (index < raw.length() && Character.isDigit(raw.charAt(index))) {
                index++;
            }
            if (start == index) {
                throw new IllegalArgumentException("expected a number at " + index + " in " + input);
            }
            long value = Long.parseLong(raw.substring(start, index));
            if (index >= raw.length()) {
                throw new IllegalArgumentException("missing unit in " + input);
            }
            char unit = raw.charAt(index++);
            long factor = switch (unit) {
                case 's' -> 1_000L;
                case 'm' -> 60_000L;
                case 'h' -> 3_600_000L;
                case 'd' -> 86_400_000L;
                case 'w' -> 604_800_000L;
                default -> throw new IllegalArgumentException("unknown unit '" + unit + "' in " + input);
            };
            total += value * factor;
            matchedAny = true;
        }
        if (!matchedAny) {
            throw new IllegalArgumentException("invalid duration " + input);
        }
        return total;
    }

    /** {@code 90061000} → {@code "1d 1h"}; {@code 0} → {@code "0s"}. */
    public static String formatDuration(long millis) {
        if (millis <= 0) {
            return "0s";
        }
        long seconds = millis / 1000L;
        long days = seconds / 86_400L;
        long hours = (seconds % 86_400L) / 3_600L;
        long minutes = (seconds % 3_600L) / 60L;
        long secs = seconds % 60L;
        StringBuilder out = new StringBuilder();
        if (days > 0) {
            out.append(days).append('d');
        }
        if (hours > 0) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(hours).append('h');
        }
        if (minutes > 0 && days == 0) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(minutes).append('m');
        }
        if (out.isEmpty()) {
            out.append(secs).append('s');
        }
        return out.toString();
    }

    /** Absolute timestamp, e.g. {@code 2026-10-24 18:04}. */
    public static String formatDate(long epochMillis) {
        return DATE.format(Instant.ofEpochMilli(epochMillis));
    }

    /** {@code "in 3d 4h"} or {@code "3d 4h ago"}. */
    public static String relative(long epochMillis) {
        long diff = epochMillis - System.currentTimeMillis();
        String text = formatDuration(Math.abs(diff));
        return diff >= 0 ? "in " + text : text + " ago";
    }

    /** Whole days between now and a timestamp (negative when in the past). */
    public static long daysUntil(long epochMillis) {
        return (epochMillis - System.currentTimeMillis()) / 86_400_000L;
    }

    /** Unix epoch seconds — handy for web APIs. */
    public static long epochSeconds() {
        return System.currentTimeMillis() / 1000L;
    }
}
