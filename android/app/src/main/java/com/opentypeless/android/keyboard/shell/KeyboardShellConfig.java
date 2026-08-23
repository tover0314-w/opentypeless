package com.opentypeless.android.keyboard.shell;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Objects;

/**
 * Process-local rollback flag for the KBD-001 Shell migration.
 *
 * <p>The canonical route defaults to Route A. A service instance freezes the returned value in
 * {@code onCreate}; changing the preference therefore cannot make one editor session dispatch to
 * two Shells. Persistence is synchronous so a requested rollback is durable before the caller
 * restarts the IME process.
 */
public final class KeyboardShellConfig {
    static final String STORE = "keyboard_shell_runtime";
    static final String ROUTE_A_ENABLED = "keyboard_shell_route_a";
    static final String LEGACY_ENABLED = "enabled";

    private KeyboardShellConfig() {}

    public static synchronized KeyboardShellRoute selectedRoute(Context context) {
        SharedPreferences preferences = preferences(context);
        boolean canonicalPresent = preferences.contains(ROUTE_A_ENABLED);
        boolean legacyPresent = preferences.contains(LEGACY_ENABLED);
        boolean enabled = canonicalPresent
                ? preferences.getBoolean(ROUTE_A_ENABLED, true)
                : legacyPresent
                        ? preferences.getBoolean(LEGACY_ENABLED, true)
                        : true;
        if (!canonicalPresent || legacyPresent) {
            SharedPreferences.Editor migration = preferences.edit()
                    .putBoolean(ROUTE_A_ENABLED, enabled)
                    .remove(LEGACY_ENABLED);
            if (!migration.commit()) {
                throw new IllegalStateException("keyboard Shell flag migration failed");
            }
        }
        return enabled ? KeyboardShellRoute.ROUTE_A : KeyboardShellRoute.LEGACY_VOICE;
    }

    public static synchronized void setRouteAEnabled(Context context, boolean enabled) {
        if (!preferences(context).edit()
                .putBoolean(ROUTE_A_ENABLED, enabled)
                .remove(LEGACY_ENABLED)
                .commit()) {
            throw new IllegalStateException("keyboard Shell flag update failed");
        }
    }

    private static SharedPreferences preferences(Context context) {
        return Objects.requireNonNull(context, "context")
                .getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }
}
