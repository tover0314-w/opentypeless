package com.opentypeless.android.keyboard.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class KeyboardShellConfigInstrumentedTest {
    @Test
    public void routeDefaultsOnAndPersistsMutuallyExclusiveRollback() {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences preferences = context.getSharedPreferences(
                KeyboardShellConfig.STORE, Context.MODE_PRIVATE);
        boolean canonicalExisted = preferences.contains(KeyboardShellConfig.ROUTE_A_ENABLED);
        boolean canonicalOriginal = preferences.getBoolean(
                KeyboardShellConfig.ROUTE_A_ENABLED, true);
        boolean legacyExisted = preferences.contains(KeyboardShellConfig.LEGACY_ENABLED);
        boolean legacyOriginal = preferences.getBoolean(KeyboardShellConfig.LEGACY_ENABLED, true);
        try {
            assertTrue(preferences.edit()
                    .remove(KeyboardShellConfig.ROUTE_A_ENABLED)
                    .remove(KeyboardShellConfig.LEGACY_ENABLED)
                    .commit());
            assertEquals(KeyboardShellRoute.ROUTE_A, KeyboardShellConfig.selectedRoute(context));

            KeyboardShellConfig.setRouteAEnabled(context, false);
            assertEquals(
                    KeyboardShellRoute.LEGACY_VOICE,
                    KeyboardShellConfig.selectedRoute(context));
            assertFalse(preferences.getBoolean(KeyboardShellConfig.ROUTE_A_ENABLED, true));
            assertFalse(preferences.contains(KeyboardShellConfig.LEGACY_ENABLED));

            KeyboardShellConfig.setRouteAEnabled(context, true);
            assertEquals(KeyboardShellRoute.ROUTE_A, KeyboardShellConfig.selectedRoute(context));
            assertTrue(preferences.getBoolean(KeyboardShellConfig.ROUTE_A_ENABLED, false));
        } finally {
            SharedPreferences.Editor restore = preferences.edit();
            if (canonicalExisted) {
                restore.putBoolean(KeyboardShellConfig.ROUTE_A_ENABLED, canonicalOriginal);
            } else {
                restore.remove(KeyboardShellConfig.ROUTE_A_ENABLED);
            }
            if (legacyExisted) {
                restore.putBoolean(KeyboardShellConfig.LEGACY_ENABLED, legacyOriginal);
            } else {
                restore.remove(KeyboardShellConfig.LEGACY_ENABLED);
            }
            assertTrue(restore.commit());
        }
    }
}
