package com.opentypeless.android.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.text.InputType;
import com.opentypeless.android.context.FieldKind;
import org.junit.Test;

public final class SessionValidatorTest {
    @Test
    public void identicalEvidenceIsValidAndCaptureTimeIsIgnored() {
        EditorSessionSnapshot expected = snapshot(1, 2, "app", 3, FieldKind.GENERAL,
                InputType.TYPE_CLASS_TEXT, 0, new TextRange(2, 4), "ab", "left", "right",
                true, false, 10);
        EditorSessionSnapshot later = snapshot(1, 2, "app", 3, FieldKind.GENERAL,
                InputType.TYPE_CLASS_TEXT, 0, new TextRange(2, 4), "ab", "left", "right",
                true, false, Long.MAX_VALUE);

        assertEquals(new SessionValidationResult.Valid(),
                SessionValidator.validate(expected, later));
    }

    @Test
    public void lifecycleAndConnectionPrecedeEveryLaterMismatch() {
        EditorSessionSnapshot expected = baseline();
        EditorSessionSnapshot epochChanged = snapshot(2, 9, "other", 4, FieldKind.SENSITIVE,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, 99,
                new TextRange(9, 9), "", "", "", false, true, 2);
        assertInvalid(TargetChangeReason.EPOCH_CHANGED, expected, epochChanged);

        EditorSessionSnapshot connectionChanged = snapshot(1, 9, "other", 4,
                FieldKind.GENERAL, InputType.TYPE_CLASS_TEXT, 99, new TextRange(9, 9),
                "", "different", "different", false, false, 2);
        assertInvalid(TargetChangeReason.CONNECTION_CHANGED, expected, connectionChanged);
    }

    @Test
    public void securityPrecedesMetadataAndSelection() {
        EditorSessionSnapshot expected = baseline();
        EditorSessionSnapshot learningChanged = snapshot(1, 2, "other", 99,
                FieldKind.GENERAL, InputType.TYPE_CLASS_TEXT, 8, new TextRange(8, 8),
                "", "changed", "changed", false, false, 2);

        assertInvalid(TargetChangeReason.SECURITY_STATE_CHANGED, expected, learningChanged);
    }

    @Test
    public void matchingSensitiveRedactedSnapshotsRemainValidForLaterSourcePolicy() {
        int password = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
        EditorSessionSnapshot expected = snapshot(1, 2, "app", 3, FieldKind.SENSITIVE,
                password, 0, new TextRange(2, 2), "", "", "", false, true, 1);
        EditorSessionSnapshot current = snapshot(1, 2, "app", 3, FieldKind.SENSITIVE,
                password, 0, new TextRange(2, 2), "", "", "", false, true, 2);

        assertEquals(new SessionValidationResult.Valid(),
                SessionValidator.validate(expected, current));
    }

    @Test
    public void metadataFieldsMapToOneStableReason() {
        EditorSessionSnapshot expected = baseline();
        assertInvalid(TargetChangeReason.EDITOR_METADATA_CHANGED, expected,
                snapshot(1, 2, "other", 3, FieldKind.GENERAL, 1, 0,
                        new TextRange(2, 2), "", "left", "right", true, false, 2));
        assertInvalid(TargetChangeReason.EDITOR_METADATA_CHANGED, expected,
                snapshot(1, 2, "app", 4, FieldKind.GENERAL, 1, 0,
                        new TextRange(2, 2), "", "left", "right", true, false, 2));
        assertInvalid(TargetChangeReason.EDITOR_METADATA_CHANGED, expected,
                snapshot(1, 2, "app", 3, FieldKind.EMAIL_ADDRESS, 1, 0,
                        new TextRange(2, 2), "", "left", "right", true, false, 2));
        assertInvalid(TargetChangeReason.EDITOR_METADATA_CHANGED, expected,
                snapshot(1, 2, "app", 3, FieldKind.GENERAL, 2, 0,
                        new TextRange(2, 2), "", "left", "right", true, false, 2));
        assertInvalid(TargetChangeReason.EDITOR_METADATA_CHANGED, expected,
                snapshot(1, 2, "app", 3, FieldKind.GENERAL, 1, 7,
                        new TextRange(2, 2), "", "left", "right", true, false, 2));
    }

    @Test
    public void unknownSelectionFailsClosedEvenWhenBothSnapshotsMatch() {
        EditorSessionSnapshot expected = snapshot(1, 2, "app", 3, FieldKind.GENERAL,
                1, 0, TextRange.UNKNOWN, "", "left", "right", true, false, 1);
        EditorSessionSnapshot current = snapshot(1, 2, "app", 3, FieldKind.GENERAL,
                1, 0, TextRange.UNKNOWN, "", "left", "right", true, false, 2);

        assertInvalid(TargetChangeReason.EVIDENCE_UNAVAILABLE, expected, current);
    }

    @Test
    public void selectionAndEachFingerprintHaveStableReasons() {
        EditorSessionSnapshot expected = baseline();
        assertInvalid(TargetChangeReason.SELECTION_CHANGED, expected,
                snapshot(1, 2, "app", 3, FieldKind.GENERAL, 1, 0,
                        new TextRange(3, 3), "", "left", "right", true, false, 2));

        EditorSessionSnapshot selectedExpected = snapshot(1, 2, "app", 3,
                FieldKind.GENERAL, 1, 0, new TextRange(2, 4), "ab", "left", "right",
                true, false, 1);
        assertInvalid(TargetChangeReason.SELECTED_TEXT_CHANGED, selectedExpected,
                snapshot(1, 2, "app", 3, FieldKind.GENERAL, 1, 0,
                        new TextRange(2, 4), "cd", "left", "right", true, false, 2));
        assertInvalid(TargetChangeReason.SURROUNDING_TEXT_CHANGED, expected,
                snapshot(1, 2, "app", 3, FieldKind.GENERAL, 1, 0,
                        new TextRange(2, 2), "", "changed", "right", true, false, 2));
        assertInvalid(TargetChangeReason.SURROUNDING_TEXT_CHANGED, expected,
                snapshot(1, 2, "app", 3, FieldKind.GENERAL, 1, 0,
                        new TextRange(2, 2), "", "left", "changed", true, false, 2));
    }

    @Test
    public void resultAndErrorsContainNoEditorPlaintext() {
        SessionValidationResult.Invalid invalid =
                new SessionValidationResult.Invalid(TargetChangeReason.SELECTED_TEXT_CHANGED);
        assertFalse(invalid.toString().contains("private"));
        assertThrows(NullPointerException.class,
                () -> new SessionValidationResult.Invalid(null));
        assertThrows(NullPointerException.class,
                () -> SessionValidator.validate(null, baseline()));
        assertThrows(NullPointerException.class,
                () -> SessionValidator.validate(baseline(), null));
        assertTrue(SessionValidationResult.Valid.class.getRecordComponents().length == 0);
    }

    private static EditorSessionSnapshot baseline() {
        return snapshot(1, 2, "app", 3, FieldKind.GENERAL, 1, 0,
                new TextRange(2, 2), "", "left", "right", true, false, 1);
    }

    private static void assertInvalid(
            TargetChangeReason reason,
            EditorSessionSnapshot expected,
            EditorSessionSnapshot current) {
        assertEquals(new SessionValidationResult.Invalid(reason),
                SessionValidator.validate(expected, current));
    }

    private static EditorSessionSnapshot snapshot(
            long epoch,
            long token,
            String packageName,
            int fieldId,
            FieldKind fieldKind,
            int inputType,
            int imeOptions,
            TextRange selection,
            String selected,
            String before,
            String after,
            boolean learningAllowed,
            boolean sensitive,
            long capturedAt) {
        return EditorSessionSnapshot.capture(epoch, token, packageName, fieldId, fieldKind,
                inputType, imeOptions, selection, selected, before, after, learningAllowed,
                sensitive, capturedAt);
    }
}
