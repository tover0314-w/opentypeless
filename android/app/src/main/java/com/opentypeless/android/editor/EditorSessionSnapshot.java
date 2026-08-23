package com.opentypeless.android.editor;

import com.opentypeless.android.context.FieldKind;
import java.util.Objects;

/**
 * Immutable evidence captured for one editor session.
 *
 * <p>Surrounding plaintext is accepted only transiently by {@link #capture}; it is never retained.
 * The selected text remains ephemeral to support explicit selection operations and is omitted from
 * {@link #toString()}.
 */
public final class EditorSessionSnapshot {
    private final long epoch;
    private final long connectionToken;
    private final String packageName;
    private final int fieldId;
    private final FieldKind fieldKind;
    private final int inputType;
    private final int imeOptions;
    private final TextRange selection;
    private final String selectedText;
    private final TextFingerprint selectedTextFingerprint;
    private final TextFingerprint beforeFingerprint;
    private final TextFingerprint afterFingerprint;
    private final TextFingerprint contextFingerprint;
    private final boolean learningAllowed;
    private final boolean sensitive;
    private final long capturedAtElapsedRealtimeMs;

    private EditorSessionSnapshot(
            long epoch,
            long connectionToken,
            String packageName,
            int fieldId,
            FieldKind fieldKind,
            int inputType,
            int imeOptions,
            TextRange selection,
            String selectedText,
            TextFingerprint selectedTextFingerprint,
            TextFingerprint beforeFingerprint,
            TextFingerprint afterFingerprint,
            TextFingerprint contextFingerprint,
            boolean learningAllowed,
            boolean sensitive,
            long capturedAtElapsedRealtimeMs) {
        this.epoch = epoch;
        this.connectionToken = connectionToken;
        this.packageName = packageName;
        this.fieldId = fieldId;
        this.fieldKind = fieldKind;
        this.inputType = inputType;
        this.imeOptions = imeOptions;
        this.selection = selection;
        this.selectedText = selectedText;
        this.selectedTextFingerprint = selectedTextFingerprint;
        this.beforeFingerprint = beforeFingerprint;
        this.afterFingerprint = afterFingerprint;
        this.contextFingerprint = contextFingerprint;
        this.learningAllowed = learningAllowed;
        this.sensitive = sensitive;
        this.capturedAtElapsedRealtimeMs = capturedAtElapsedRealtimeMs;
    }

    static EditorSessionSnapshot capture(
            long epoch,
            long connectionToken,
            String packageName,
            int fieldId,
            FieldKind fieldKind,
            int inputType,
            int imeOptions,
            TextRange selection,
            String selectedText,
            String beforeText,
            String afterText,
            boolean learningAllowed,
            boolean sensitive,
            long capturedAtElapsedRealtimeMs,
            EditorTextHasher hasher) {
        if (epoch <= 0) throw new IllegalArgumentException("epoch must be positive");
        if (connectionToken <= 0) {
            throw new IllegalArgumentException("connectionToken must be positive");
        }
        String safePackageName = EditorSessionLimits.requirePackageName(packageName);
        FieldKind safeFieldKind = Objects.requireNonNull(fieldKind, "fieldKind");
        TextRange safeSelection = Objects.requireNonNull(selection, "selection");
        String safeSelectedText = EditorSessionLimits.requireSelectedText(selectedText);
        EditorSessionLimits.requireSurroundingInput(beforeText, "beforeText");
        EditorSessionLimits.requireSurroundingInput(afterText, "afterText");
        EditorTextHasher safeHasher = Objects.requireNonNull(hasher, "hasher");
        if (capturedAtElapsedRealtimeMs < 0) {
            throw new IllegalArgumentException("capturedAtElapsedRealtimeMs must not be negative");
        }
        if (safeFieldKind == FieldKind.SENSITIVE && !sensitive) {
            throw new IllegalArgumentException("sensitive field kind must be marked sensitive");
        }
        if (sensitive) {
            if (learningAllowed) {
                throw new IllegalArgumentException("sensitive sessions must disable learning");
            }
            if (!safeSelectedText.isEmpty() || !beforeText.isEmpty() || !afterText.isEmpty()) {
                throw new IllegalArgumentException(
                        "sensitive sessions must capture only redacted empty text");
            }
        } else {
            validateSelectionText(safeSelection, safeSelectedText);
        }

        TextFingerprint selectedFingerprint = safeHasher.selectedText(safeSelectedText);
        TextFingerprint beforeFingerprint = safeHasher.beforeContext(beforeText);
        TextFingerprint afterFingerprint = safeHasher.afterContext(afterText);
        TextFingerprint contextFingerprint =
                safeHasher.context(beforeText, safeSelectedText, afterText);
        requireDomain(selectedFingerprint, FingerprintDomain.SELECTED_TEXT);
        requireDomain(beforeFingerprint, FingerprintDomain.BEFORE_CONTEXT);
        requireDomain(afterFingerprint, FingerprintDomain.AFTER_CONTEXT);
        requireDomain(contextFingerprint, FingerprintDomain.CONTEXT_V1);

        return new EditorSessionSnapshot(
                epoch,
                connectionToken,
                safePackageName,
                fieldId,
                safeFieldKind,
                inputType,
                imeOptions,
                safeSelection,
                safeSelectedText,
                selectedFingerprint,
                beforeFingerprint,
                afterFingerprint,
                contextFingerprint,
                learningAllowed,
                sensitive,
                capturedAtElapsedRealtimeMs);
    }

    public static EditorSessionSnapshot capture(
            long epoch,
            long connectionToken,
            String packageName,
            int fieldId,
            FieldKind fieldKind,
            int inputType,
            int imeOptions,
            TextRange selection,
            String selectedText,
            String beforeText,
            String afterText,
            boolean learningAllowed,
            boolean sensitive,
            long capturedAtElapsedRealtimeMs) {
        return capture(
                epoch,
                connectionToken,
                packageName,
                fieldId,
                fieldKind,
                inputType,
                imeOptions,
                selection,
                selectedText,
                beforeText,
                afterText,
                learningAllowed,
                sensitive,
                capturedAtElapsedRealtimeMs,
                Sha256EditorTextHasher.INSTANCE);
    }

    private static void validateSelectionText(TextRange selection, String selectedText) {
        if (!selection.isKnown() && !selectedText.isEmpty()) {
            throw new IllegalArgumentException("unknown selection cannot retain selected text");
        }
        if (selection.isCollapsed() && !selectedText.isEmpty()) {
            throw new IllegalArgumentException("collapsed selection must have empty selected text");
        }
        if (selection.hasSelection() && selectedText.isEmpty()) {
            throw new IllegalArgumentException("non-collapsed selection requires selected text");
        }
        if (selection.hasSelection()) {
            long selectionUtf16Units = Math.abs((long) selection.end() - selection.start());
            if (selectionUtf16Units != selectedText.length()) {
                throw new IllegalArgumentException(
                        "selection span must equal selected text UTF-16 length");
            }
        }
    }

    private static void requireDomain(
            TextFingerprint fingerprint, FingerprintDomain expectedDomain) {
        Objects.requireNonNull(fingerprint, expectedDomain + " fingerprint");
        if (fingerprint.domain() != expectedDomain) {
            throw new IllegalArgumentException(
                    "expected " + expectedDomain + " fingerprint, got " + fingerprint.domain());
        }
    }

    public long epoch() { return epoch; }

    public long connectionToken() { return connectionToken; }

    public String packageName() { return packageName; }

    public int fieldId() { return fieldId; }

    public FieldKind fieldKind() { return fieldKind; }

    public int inputType() { return inputType; }

    public int imeOptions() { return imeOptions; }

    public TextRange selection() { return selection; }

    public String selectedText() { return selectedText; }

    public TextFingerprint selectedTextFingerprint() { return selectedTextFingerprint; }

    public TextFingerprint beforeFingerprint() { return beforeFingerprint; }

    public TextFingerprint afterFingerprint() { return afterFingerprint; }

    public TextFingerprint contextFingerprint() { return contextFingerprint; }

    public boolean learningAllowed() { return learningAllowed; }

    public boolean sensitive() { return sensitive; }

    public long capturedAtElapsedRealtimeMs() { return capturedAtElapsedRealtimeMs; }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof EditorSessionSnapshot other)) return false;
        return epoch == other.epoch
                && connectionToken == other.connectionToken
                && fieldId == other.fieldId
                && inputType == other.inputType
                && imeOptions == other.imeOptions
                && learningAllowed == other.learningAllowed
                && sensitive == other.sensitive
                && capturedAtElapsedRealtimeMs == other.capturedAtElapsedRealtimeMs
                && packageName.equals(other.packageName)
                && fieldKind == other.fieldKind
                && selection.equals(other.selection)
                && selectedText.equals(other.selectedText)
                && selectedTextFingerprint.equals(other.selectedTextFingerprint)
                && beforeFingerprint.equals(other.beforeFingerprint)
                && afterFingerprint.equals(other.afterFingerprint)
                && contextFingerprint.equals(other.contextFingerprint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                epoch,
                connectionToken,
                packageName,
                fieldId,
                fieldKind,
                inputType,
                imeOptions,
                selection,
                selectedText,
                selectedTextFingerprint,
                beforeFingerprint,
                afterFingerprint,
                contextFingerprint,
                learningAllowed,
                sensitive,
                capturedAtElapsedRealtimeMs);
    }

    @Override
    public String toString() {
        return "EditorSessionSnapshot{"
                + "epoch=" + epoch
                + ", selectedTextCodePoints="
                + selectedText.codePointCount(0, selectedText.length())
                + ", learningAllowed=" + learningAllowed
                + ", sensitive=" + sensitive
                + '}';
    }
}
