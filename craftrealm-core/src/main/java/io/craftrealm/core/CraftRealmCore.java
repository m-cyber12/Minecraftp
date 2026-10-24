package io.craftrealm.core;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Shared, static-free-ish plumbing for code that needs cross-cutting access
 * without dragging the plugin module into the dependency graph.
 *
 * <p>Two things live here: the plugin logger (so shared classes log through the
 * server log with the CraftRealm prefix) and a small display-name cache used when
 * writing denormalised names into the ledger, auctions and reports.</p>
 */
public final class CraftRealmCore {

    private static Logger logger = Logger.getLogger("CraftRealm");
    private static final Map<UUID, String> NAMES = new ConcurrentHashMap<>();

    private CraftRealmCore() {
    }

    public static Logger logger() {
        return logger;
    }

    public static void logger(Logger newLogger) {
        if (newLogger != null) {
            logger = newLogger;
        }
    }

    /** Remembered display names, populated on join. */
    public static Map<UUID, String> nameCache() {
        return NAMES;
    }

    public static void rememberName(UUID uuid, String name) {
        if (uuid != null && name != null && !name.isBlank()) {
            NAMES.put(uuid, name);
        }
    }

    /** Best-effort display name: cached name, otherwise the first 8 chars of the UUID. */
    public static String nameOf(UUID uuid) {
        if (uuid == null) {
            return "server";
        }
        String cached = NAMES.get(uuid);
        return cached != null ? cached : uuid.toString().substring(0, 8);
    }

    public static void forget(UUID uuid) {
        NAMES.remove(uuid);
    }
}
