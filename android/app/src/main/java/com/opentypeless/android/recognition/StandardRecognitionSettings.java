package com.opentypeless.android.recognition;

import android.content.Context;
import android.content.SharedPreferences;

import com.opentypeless.android.settings.AppSettings;
import com.opentypeless.android.settings.RecognitionBackend;

import java.util.Set;
import java.util.TreeSet;

/** Explicit user authorization for external apps to spend the user's BYOK speech quota. */
public final class StandardRecognitionSettings {
    private static final String STORE = "opentypeless_standard_recognition";
    private static final String ENABLED = "enabled";
    private static final String ALLOWED_PACKAGES = "allowed_packages";

    public record Snapshot(boolean enabled, Set<String> allowedPackages) {
        public Snapshot {
            allowedPackages = allowedPackages == null ? Set.of() : Set.copyOf(allowedPackages);
        }

        public String packagesAsText() {
            return String.join("\n", new TreeSet<>(allowedPackages));
        }

        public boolean allows(String packageName) {
            return packageName != null
                    && allowedPackages.contains(packageName.trim().toLowerCase(java.util.Locale.ROOT));
        }
    }

    private final SharedPreferences preferences;

    public StandardRecognitionSettings(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }

    public Snapshot load() {
        return new Snapshot(
                preferences.getBoolean(ENABLED, false),
                preferences.getStringSet(ALLOWED_PACKAGES, Set.of()));
    }

    public Snapshot validate(boolean enabled, String packagesText) {
        Set<String> packages = CallerPackageAllowlist.parse(packagesText);
        if (enabled && packages.isEmpty()) {
            throw new IllegalArgumentException(
                    "Add at least one caller package before enabling Android standard speech entry");
        }
        return new Snapshot(enabled, packages);
    }

    public static boolean isSupportedRoute(Snapshot snapshot, AppSettings settings) {
        if (snapshot == null || !snapshot.enabled()) return true;
        return settings != null
                && (settings.recognitionBackend() == RecognitionBackend.OPENAI_COMPATIBLE
                        || settings.recognitionBackend() == RecognitionBackend.DASHSCOPE_STREAMING)
                && settings.isReady();
    }

    public void save(Snapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("Standard speech settings are required");
        preferences.edit()
                .putBoolean(ENABLED, snapshot.enabled())
                .putStringSet(ALLOWED_PACKAGES, new TreeSet<>(snapshot.allowedPackages()))
                .apply();
    }
}
