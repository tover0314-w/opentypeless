package com.opentypeless.android.ime;

import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import java.util.Objects;

/** Two-stage host-only reader that preserves the legacy IME's getter and early-return order. */
final class EditorEvidenceReader {
    enum Failure {
        SENSITIVE,
        READ_FAILED
    }

    sealed interface SelectionResult permits SelectionEvidence, Rejected {}

    sealed interface SurroundingResult permits SurroundingEvidence, Rejected {}

    /** Ephemeral content is intentionally omitted from toString. */
    record SelectionEvidence(
            boolean selectedTextAvailable,
            String selectedText,
            boolean extractedTextAvailable,
            int extractedSelectionStart,
            int extractedSelectionEnd) implements SelectionResult {
        public SelectionEvidence {
            Objects.requireNonNull(selectedText, "selectedText");
        }

        @Override
        public String toString() {
            return "SelectionEvidence{<redacted>}";
        }
    }

    /** Ephemeral content is intentionally omitted from toString. */
    record SurroundingEvidence(
            boolean beforeTextAvailable,
            boolean afterTextAvailable,
            String beforeContext,
            String beforeFingerprint,
            String afterFingerprint,
            String precedingContext,
            String afterContext) implements SurroundingResult {
        public SurroundingEvidence {
            Objects.requireNonNull(beforeContext, "beforeContext");
            Objects.requireNonNull(beforeFingerprint, "beforeFingerprint");
            Objects.requireNonNull(afterFingerprint, "afterFingerprint");
            Objects.requireNonNull(precedingContext, "precedingContext");
            Objects.requireNonNull(afterContext, "afterContext");
        }

        @Override
        public String toString() {
            return "SurroundingEvidence{<redacted>}";
        }

        CharSequence shadowBeforeText() {
            return beforeTextAvailable ? beforeContext : null;
        }

        CharSequence shadowAfterText() {
            return afterTextAvailable ? afterContext : null;
        }
    }

    record Rejected(Failure reason) implements SelectionResult, SurroundingResult {
        public Rejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    private EditorEvidenceReader() {}

    /** Reads selected text and, only when requested, extracted text; sensitive means zero getters. */
    static SelectionResult readSelectionOnce(
            InputConnection connection, boolean sensitive, boolean requestExtractedText) {
        Objects.requireNonNull(connection, "connection");
        if (sensitive) return new Rejected(Failure.SENSITIVE);
        try {
            CharSequence selectedSequence = connection.getSelectedText(0);
            ExtractedText extracted = requestExtractedText
                    ? connection.getExtractedText(new ExtractedTextRequest(), 0)
                    : null;
            // Preserve legacy evaluation order: the caller used to read extracted coordinates as
            // method arguments before resolveSelectionEvidence materialized selected text.
            boolean extractedAvailable = extracted != null;
            int extractedStart = extractedAvailable ? extracted.selectionStart : -1;
            int extractedEnd = extractedAvailable ? extracted.selectionEnd : -1;
            String selected = selectedSequence == null ? "" : selectedSequence.toString();
            return new SelectionEvidence(
                    selectedSequence != null,
                    selected,
                    extractedAvailable,
                    extractedStart,
                    extractedEnd);
        } catch (RuntimeException editorFailure) {
            return new Rejected(Failure.READ_FAILED);
        }
    }

    /**
     * Reads surrounding context after selection acceptance, preserving legacy materialization order:
     * get-before, before fingerprint, get-after, after fingerprint, then full preceding context.
     */
    static SurroundingResult readSurroundingOnce(
            InputConnection connection,
            int contextUtf16Units,
            int fingerprintCodePoints) {
        Objects.requireNonNull(connection, "connection");
        if (contextUtf16Units < 0 || fingerprintCodePoints < 0) {
            throw new IllegalArgumentException("evidence limits must be >= 0");
        }
        try {
            CharSequence beforeSequence =
                    connection.getTextBeforeCursor(contextUtf16Units, 0);
            String beforeContext = beforeSequence == null ? "" : beforeSequence.toString();
            String beforeFingerprint = tailCodePoints(beforeContext, fingerprintCodePoints);

            CharSequence afterSequence =
                    connection.getTextAfterCursor(contextUtf16Units, 0);
            String afterContext = afterSequence == null ? "" : afterSequence.toString();
            String afterFingerprint = headCodePoints(afterContext, fingerprintCodePoints);

            String precedingContext = beforeSequence == null ? "" : beforeSequence.toString();
            return new SurroundingEvidence(
                    beforeSequence != null,
                    afterSequence != null,
                    beforeContext,
                    beforeFingerprint,
                    afterFingerprint,
                    precedingContext,
                    afterContext);
        } catch (RuntimeException editorFailure) {
            return new Rejected(Failure.READ_FAILED);
        }
    }

    private static String tailCodePoints(String text, int maximum) {
        int count = text.codePointCount(0, text.length());
        return count <= maximum
                ? text
                : text.substring(text.offsetByCodePoints(0, count - maximum));
    }

    private static String headCodePoints(String text, int maximum) {
        int count = text.codePointCount(0, text.length());
        return count <= maximum
                ? text
                : text.substring(0, text.offsetByCodePoints(0, maximum));
    }
}
