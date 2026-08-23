package com.opentypeless.android.editor;

import java.util.Objects;

/** Pure, deterministic comparison of immutable editor-session evidence. */
public final class SessionValidator {
    private SessionValidator() {}

    /**
     * Validates current evidence against the original target using stable failure precedence.
     * Capture time and plaintext are deliberately ignored; text is compared only by typed hashes.
     */
    public static SessionValidationResult validate(
            EditorSessionSnapshot expected, EditorSessionSnapshot current) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(current, "current");

        if (expected.epoch() != current.epoch()) {
            return invalid(TargetChangeReason.EPOCH_CHANGED);
        }
        if (expected.connectionToken() != current.connectionToken()) {
            return invalid(TargetChangeReason.CONNECTION_CHANGED);
        }
        if (expected.sensitive() != current.sensitive()
                || expected.learningAllowed() != current.learningAllowed()) {
            return invalid(TargetChangeReason.SECURITY_STATE_CHANGED);
        }
        if (!expected.packageName().equals(current.packageName())
                || expected.fieldId() != current.fieldId()
                || expected.fieldKind() != current.fieldKind()
                || expected.inputType() != current.inputType()
                || expected.imeOptions() != current.imeOptions()) {
            return invalid(TargetChangeReason.EDITOR_METADATA_CHANGED);
        }
        if (!expected.selection().isKnown() || !current.selection().isKnown()) {
            return invalid(TargetChangeReason.EVIDENCE_UNAVAILABLE);
        }
        if (!expected.selection().equals(current.selection())) {
            return invalid(TargetChangeReason.SELECTION_CHANGED);
        }
        if (!expected.selectedTextFingerprint()
                .securelyMatches(current.selectedTextFingerprint())) {
            return invalid(TargetChangeReason.SELECTED_TEXT_CHANGED);
        }
        if (!expected.beforeFingerprint().securelyMatches(current.beforeFingerprint())
                || !expected.afterFingerprint().securelyMatches(current.afterFingerprint())
                || !expected.contextFingerprint().securelyMatches(current.contextFingerprint())) {
            return invalid(TargetChangeReason.SURROUNDING_TEXT_CHANGED);
        }
        return new SessionValidationResult.Valid();
    }

    private static SessionValidationResult.Invalid invalid(TargetChangeReason reason) {
        return new SessionValidationResult.Invalid(reason);
    }
}
