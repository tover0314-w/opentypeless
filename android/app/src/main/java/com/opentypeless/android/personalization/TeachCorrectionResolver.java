package com.opentypeless.android.personalization;

import com.opentypeless.android.data.HistoryEntry;
import com.opentypeless.android.editor.CommitRecord;
import com.opentypeless.android.editor.OperationSource;

/** Resolves an explicit Teach action without letting stale history override the current edit. */
public final class TeachCorrectionResolver {
    private TeachCorrectionResolver() {}

    /** Returns whether an exact transaction record may seed an explicit Teach dialog. */
    public static boolean isEligible(CommitRecord record) {
        return record != null
                && record.source() == OperationSource.VOICE
                && record.learningAllowed()
                && record.rawTranscript() instanceof CommitRecord.RawTranscript.Present
                && !record.insertedText().isBlank();
    }

    /**
     * Resolves current transaction evidence over an optional stored history row.
     *
     * <p>The current record always owns raw/final/scope. Stored history contributes only stable
     * row metadata, so an asynchronous or stale history read cannot replace the exact commit that
     * opened Teach.
     */
    public static HistoryEntry resolve(HistoryEntry stored, CommitRecord record) {
        if (!isEligible(record)) return null;
        CommitRecord.RawTranscript.Present raw =
                (CommitRecord.RawTranscript.Present) record.rawTranscript();
        return new HistoryEntry(
                stored == null ? 0L : stored.id(),
                stored == null ? System.currentTimeMillis() : stored.createdAt(),
                record.originalSession().packageName(),
                record.originalSession().fieldKind().name(),
                stored == null ? "TEACH" : stored.mode(),
                stored == null ? "IME" : stored.backend(),
                raw.text(),
                record.insertedText(),
                stored == null ? 0L : stored.durationMs(),
                stored == null ? "" : stored.appliedRules());
    }

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
