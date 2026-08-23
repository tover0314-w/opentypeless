package com.opentypeless.android;

import com.opentypeless.android.diagnostics.RecognitionDiagnostics;
import com.opentypeless.android.settings.RecognitionBackend;

import java.util.Locale;

/** Pure setup-completion rules shared by the first-run UI and regression tests. */
final class SetupChecklist {
    private SetupChecklist() {}

    static boolean successfulTestMatches(
            RecognitionBackend savedBackend,
            String savedLanguage,
            RecognitionDiagnostics.Snapshot latest) {
        if (savedBackend == null
                || latest == null
                || latest.status() != RecognitionDiagnostics.Status.SUCCEEDED
                || latest.finalCodePointCount() <= 0
                || latest.route().selectedBackend() != savedBackend) {
            return false;
        }
        return canonicalLanguage(savedLanguage).equals(latest.languageTag());
    }

    private static String canonicalLanguage(String value) {
        String normalized = value == null ? "" : value.trim().replace('_', '-');
        if (normalized.isEmpty()) return "und";
        String canonical = Locale.forLanguageTag(normalized).toLanguageTag();
        return canonical.isBlank() ? "und" : canonical;
    }
}
