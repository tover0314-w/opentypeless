package com.opentypeless.android.personalization;

import com.opentypeless.android.data.HistoryEntry;

/** Resolves an explicit Teach action without letting stale history override the current edit. */
public final class TeachCorrectionResolver {
    private TeachCorrectionResolver() {}

    public static HistoryEntry resolve(
            HistoryEntry stored,
            String rawExtra,
            String finalExtra,
            String requestedScope) {
        String raw = clean(rawExtra);
        String result = clean(finalExtra);
        String scope = clean(requestedScope);

        if (!raw.isBlank() && !result.isBlank()) {
            return new HistoryEntry(
                    stored == null ? 0L : stored.id(),
                    stored == null ? System.currentTimeMillis() : stored.createdAt(),
                    scope.isBlank() && stored != null ? stored.appPackage() : scope,
                    stored == null ? "GENERAL" : stored.fieldKind(),
                    stored == null ? "TEACH" : stored.mode(),
                    stored == null ? "IME" : stored.backend(),
                    raw,
                    result,
                    stored == null ? 0L : stored.durationMs());
        }
        if (stored == null) return null;
        if (scope.isBlank() || scope.equals(stored.appPackage())) return stored;
        return new HistoryEntry(
                stored.id(),
                stored.createdAt(),
                scope,
                stored.fieldKind(),
                stored.mode(),
                stored.backend(),
                stored.rawText(),
                stored.finalText(),
                stored.durationMs());
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
