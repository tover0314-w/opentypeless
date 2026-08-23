package com.opentypeless.android.keyboard.shell;

import java.util.Objects;

/** Selects exactly one Shell factory. Rejection or failure never falls through to the other. */
public final class KeyboardShellSelector {
    @FunctionalInterface
    public interface Factory<T> {
        T create();
    }

    private KeyboardShellSelector() {}

    public static <T> T select(
            KeyboardShellRoute route,
            Factory<T> routeAFactory,
            Factory<T> legacyFactory) {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(routeAFactory, "routeAFactory");
        Objects.requireNonNull(legacyFactory, "legacyFactory");
        T selected = switch (route) {
            case ROUTE_A -> routeAFactory.create();
            case LEGACY_VOICE -> legacyFactory.create();
        };
        return Objects.requireNonNull(selected, "selected Shell factory returned null");
    }
}
