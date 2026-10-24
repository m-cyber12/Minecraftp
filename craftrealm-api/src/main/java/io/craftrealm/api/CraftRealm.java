package io.craftrealm.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Static entry point for other plugins.
 *
 * <p>CraftRealm registers itself here during {@code onEnable} and clears the
 * reference on {@code onDisable}, so a reload of either plugin never leaves a
 * dangling implementation behind.</p>
 */
public final class CraftRealm {

    private static volatile CraftRealmAPI api;

    private CraftRealm() {
    }

    /**
     * @return the API
     * @throws IllegalStateException when CraftRealm is not enabled
     */
    @NotNull
    public static CraftRealmAPI get() {
        CraftRealmAPI current = api;
        if (current == null) {
            throw new IllegalStateException("CraftRealm is not enabled - check your plugin.yml (soft)depend.");
        }
        return current;
    }

    /** @return the API, or {@code null} when CraftRealm is not enabled */
    @Nullable
    public static CraftRealmAPI getOrNull() {
        return api;
    }

    public static boolean isAvailable() {
        return api != null;
    }

    /**
     * Internal: called by the CraftRealm plugin. Public only so the plugin module
     * (a different Gradle project) can reach it.
     */
    public static void register(@NotNull CraftRealmAPI implementation) {
        api = implementation;
    }

    /** Internal: called on disable. */
    public static void unregister(@Nullable CraftRealmAPI expected) {
        if (api == expected) {
            api = null;
        }
    }
}
