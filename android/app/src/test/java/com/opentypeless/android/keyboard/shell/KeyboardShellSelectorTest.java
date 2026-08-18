package com.opentypeless.android.keyboard.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class KeyboardShellSelectorTest {
    @Test
    public void routeAInvokesOnlyRouteAFactory() {
        AtomicInteger routeA = new AtomicInteger();
        AtomicInteger legacy = new AtomicInteger();

        String selected = KeyboardShellSelector.select(
                KeyboardShellRoute.ROUTE_A,
                () -> {
                    routeA.incrementAndGet();
                    return "route-a";
                },
                () -> {
                    legacy.incrementAndGet();
                    return "legacy";
                });

        assertEquals("route-a", selected);
        assertEquals(1, routeA.get());
        assertEquals(0, legacy.get());
    }

    @Test
    public void legacyInvokesOnlyLegacyFactory() {
        AtomicInteger routeA = new AtomicInteger();
        AtomicInteger legacy = new AtomicInteger();

        String selected = KeyboardShellSelector.select(
                KeyboardShellRoute.LEGACY_VOICE,
                () -> {
                    routeA.incrementAndGet();
                    return "route-a";
                },
                () -> {
                    legacy.incrementAndGet();
                    return "legacy";
                });

        assertEquals("legacy", selected);
        assertEquals(0, routeA.get());
        assertEquals(1, legacy.get());
    }

    @Test
    public void selectedFailureNeverFallsBack() {
        AtomicInteger legacy = new AtomicInteger();

        assertThrows(IllegalStateException.class, () -> KeyboardShellSelector.select(
                KeyboardShellRoute.ROUTE_A,
                () -> {
                    throw new IllegalStateException("route-a failed");
                },
                () -> {
                    legacy.incrementAndGet();
                    return "legacy";
                }));

        assertEquals(0, legacy.get());
    }

    @Test
    public void nullSelectedShellFailsClosed() {
        assertThrows(NullPointerException.class, () -> KeyboardShellSelector.select(
                KeyboardShellRoute.ROUTE_A,
                () -> null,
                () -> "legacy"));
    }
}
