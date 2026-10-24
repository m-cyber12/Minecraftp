package io.craftrealm.core.config;

import java.util.List;

/**
 * Embedded dashboard configuration.
 *
 * @param enabled          start the Javalin server
 * @param host             bind address ({@code 0.0.0.0} to expose it)
 * @param port             bind port
 * @param tokens           admin tokens accepted at login
 * @param sessionMinutes   session lifetime
 * @param allowConsole     allow command execution from the browser
 * @param maxConsoleLines  console ring buffer size
 */
public record WebSettings(boolean enabled, String host, int port, List<String> tokens,
                          int sessionMinutes, boolean allowConsole, int maxConsoleLines) {

    public static WebSettings defaults() {
        return new WebSettings(false, "0.0.0.0", 8080, List.of(), 720, true, 200);
    }
}
