package com.opentypeless.android.editor;

import java.util.Objects;

/**
 * Immutable, process-memory evidence for one eligible applied text transaction.
 *
 * <p>The transaction host supplies a freshly generated opaque identifier; this model strictly
 * validates it and internally derives the committed-text fingerprint. A record is intentionally
 * not serializable: later history, learning, undo and raw restore policies must make their own
 * explicit eligibility decisions. In particular, {@link #learningAllowed()} may be false even
 * though this short-lived record exists.
 */
public final class CommitRecord {
    /** Explicit raw-transcript presence; absence is never represented by {@code null} or "". */
    public sealed interface RawTranscript permits RawTranscript.Absent, RawTranscript.Present {
        /** The applied transaction has no raw transcript. */
        record Absent() implements RawTranscript {}

        /** A non-empty, bounded raw voice transcript. */
        record Present(String text) implements RawTranscript {
            public Present {
                text = EditorOperationLimits.requireText(text, "rawTranscript", false);
            }

            @Override
            public String toString() {
                return "RawTranscript.Present{text=<redacted>}";
            }
        }
    }

    private final String commitId;
    private final OperationSource source;
    private final EditorSessionSnapshot originalSession;
    private final String insertedText;
    private final TextFingerprint insertedTextFingerprint;
    private final RawTranscript rawTranscript;

    private CommitRecord(
            String commitId,
            OperationSource source,
            EditorSessionSnapshot originalSession,
            String insertedText,
            TextFingerprint insertedTextFingerprint,
            RawTranscript rawTranscript) {
        this.commitId = commitId;
        this.source = source;
        this.originalSession = originalSession;
        this.insertedText = insertedText;
        this.insertedTextFingerprint = insertedTextFingerprint;
        this.rawTranscript = rawTranscript;
    }

    /**
     * Creates a record from the authoritative inputs of an already-applied transaction.
     *
     * <p>The transaction host, not an operation producer, supplies these values. The fingerprint
     * cannot be supplied by any caller.
     */
    public static CommitRecord create(
            String commitId,
            OperationSource source,
            EditorSessionSnapshot originalSession,
            String insertedText,
            RawTranscript rawTranscript) {
        String safeCommitId = requireCommitId(commitId);
        OperationSource safeSource = Objects.requireNonNull(source, "source");
        EditorSessionSnapshot safeSession =
                Objects.requireNonNull(originalSession, "originalSession");
        RawTranscript safeRaw = Objects.requireNonNull(rawTranscript, "rawTranscript");

        if (safeSource != OperationSource.VOICE && safeSource != OperationSource.ACTION) {
            throw new IllegalArgumentException(
                    "commit records are allowed only for voice and action sources");
        }
        if (!safeSession.selection().isKnown()) {
            throw new IllegalArgumentException("original selection must be known");
        }
        if (safeSession.sensitive()) {
            throw new IllegalArgumentException("sensitive sessions cannot create commit records");
        }
        if (safeRaw instanceof RawTranscript.Present && safeSource != OperationSource.VOICE) {
            throw new IllegalArgumentException("raw transcript is allowed only for voice commits");
        }

        String safeInsertedText =
                EditorOperationLimits.requireText(insertedText, "insertedText", true);
        TextFingerprint fingerprint =
                Sha256EditorTextHasher.INSTANCE.committedText(safeInsertedText);
        if (fingerprint.domain() != FingerprintDomain.COMMITTED_TEXT) {
            throw new IllegalStateException("committed-text fingerprint domain mismatch");
        }

        return new CommitRecord(
                safeCommitId,
                safeSource,
                safeSession,
                safeInsertedText,
                fingerprint,
                safeRaw);
    }

    private static String requireCommitId(String value) {
        String safe = Objects.requireNonNull(value, "commitId");
        int maximumCodePoints = EditorOperation.MAX_COMMIT_ID_CODE_POINTS;
        if (safe.length() > maximumCodePoints * 2) {
            throw new IllegalArgumentException("commitId exceeds its bound");
        }
        EditorSessionLimits.requireWellFormedUtf16(safe, "commitId");
        if (safe.codePointCount(0, safe.length()) > maximumCodePoints) {
            throw new IllegalArgumentException("commitId exceeds its bound");
        }
        if (safe.isBlank()) throw new IllegalArgumentException("commitId must not be blank");
        for (int offset = 0; offset < safe.length(); ) {
            int codePoint = safe.codePointAt(offset);
            if (Character.isISOControl(codePoint)) {
                throw new IllegalArgumentException("commitId contains a control character");
            }
            offset += Character.charCount(codePoint);
        }
        return safe;
    }

    public String commitId() {
        return commitId;
    }

    public OperationSource source() {
        return source;
    }

    public EditorSessionSnapshot originalSession() {
        return originalSession;
    }

    public String insertedText() {
        return insertedText;
    }

    public TextFingerprint insertedTextFingerprint() {
        return insertedTextFingerprint;
    }

    public RawTranscript rawTranscript() {
        return rawTranscript;
    }

    /** Whether later learning/history consumers may retain content from this record. */
    public boolean learningAllowed() {
        return originalSession.learningAllowed();
    }

    @Override
    public String toString() {
        return "CommitRecord{source=" + source
                + ", learningAllowed=" + learningAllowed()
                + ", rawTranscript="
                + (rawTranscript instanceof RawTranscript.Present ? "PRESENT" : "ABSENT")
                + ", details=<redacted>}";
    }
}
