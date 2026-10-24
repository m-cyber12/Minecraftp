package io.craftrealm.web;

import java.util.List;

/**
 * The dashboard's own settings record.
 *
 * <p>Duplicated deliberately from {@code core.config.WebSettings}: the web module
 * has no Bukkit dependency, so it cannot import a class from a module that does.
 * The plugin maps one to the other at startup, which also keeps the web module
 * unit-testable without a server.</p>
 */
public record WebPanelSettings(boolean enabled, String host, int port, List<String> tokens,
                               int sessionMinutes, boolean allowConsole, int maxConsoleLines) {

    public static WebPanelSettings disabled() {
        return new WebPanelSettings(false, "0.0.0.0", 8080, List.of(), 720, false, 200);
    }
}
