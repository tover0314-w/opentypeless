package com.opentypeless.android.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.opentypeless.android.context.FieldKind;
import java.util.function.Supplier;
import org.junit.Test;

public final class EditorSessionSnapshotTest {
    @Test
    public void capturesImmutableBoundedEvidenceWithoutSurroundingPlaintext() {
        EditorSessionSnapshot snapshot = snapshot(
                new TextRange(4, 6), "专名", "before-secret", "after-secret", true, false);

        assertEquals(1, snapshot.epoch());
        assertEquals(2, snapshot.connectionToken());
        assertEquals("com.example", snapshot.packageName());
        assertEquals(-1, snapshot.fieldId());
        assertEquals(FieldKind.GENERAL, snapshot.fieldKind());
        assertEquals(new TextRange(4, 6), snapshot.selection());
        assertEquals("专名", snapshot.selectedText());
        assertTrue(snapshot.learningAllowed());
        assertFalse(snapshot.sensitive());
        assertFalse(snapshot.toString().contains("专名"));
        assertFalse(snapshot.toString().contains("before-secret"));
        assertFalse(snapshot.toString().contains("after-secret"));
        assertFalse(snapshot.toString().contains("com.example"));
        assertFalse(snapshot.toString().contains(snapshot.contextFingerprint().sha256Hex()));
        assertFalse(snapshot.toString().contains("fieldId"));
        assertFalse(snapshot.toString().contains("connectionToken"));

        EditorSessionSnapshot equal = snapshot(
                new TextRange(4, 6), "专名", "before-secret", "after-secret", true, false);
        assertEquals(snapshot, equal);
        assertEquals(snapshot.hashCode(), equal.hashCode());
        assertNotEquals(snapshot, snapshot(
                new TextRange(4, 6), "专名", "before-changed", "after-secret", true, false));
    }

    @Test
    public void rejectsInvalidIdentityAndNullInputs() {
        assertIllegal(() -> create(0, 2, "com.example", FieldKind.GENERAL,
                TextRange.UNKNOWN, "", "", "", true, false, 3));
        assertIllegal(() -> create(1, 0, "com.example", FieldKind.GENERAL,
                TextRange.UNKNOWN, "", "", "", true, false, 3));
        assertIllegal(() -> create(1, 2, " ", FieldKind.GENERAL,
                TextRange.UNKNOWN, "", "", "", true, false, 3));
        assertIllegal(() -> create(1, 2, "com.\nexample", FieldKind.GENERAL,
                TextRange.UNKNOWN, "", "", "", true, false, 3));
        assertIllegal(() -> create(1, 2, "a".repeat(513), FieldKind.GENERAL,
                TextRange.UNKNOWN, "", "", "", true, false, 3));
        assertIllegal(() -> create(1, 2, "com.example", FieldKind.GENERAL,
                TextRange.UNKNOWN, "", "", "", true, false, -1));

        assertNullRejected(() -> create(1, 2, null, FieldKind.GENERAL,
                TextRange.UNKNOWN, "", "", "", true, false, 3));
        assertNullRejected(() -> create(1, 2, "com.example", null,
                TextRange.UNKNOWN, "", "", "", true, false, 3));
        assertNullRejected(() -> create(1, 2, "com.example", FieldKind.GENERAL,
                null, "", "", "", true, false, 3));
        assertNullRejected(() -> create(1, 2, "com.example", FieldKind.GENERAL,
                TextRange.UNKNOWN, null, "", "", true, false, 3));
        assertNullRejected(() -> create(1, 2, "com.example", FieldKind.GENERAL,
                TextRange.UNKNOWN, "", null, "", true, false, 3));
        assertNullRejected(() -> create(1, 2, "com.example", FieldKind.GENERAL,
                TextRange.UNKNOWN, "", "", null, true, false, 3));
        assertNullRejected(() -> EditorSessionSnapshot.capture(
                1, 2, "com.example", 0, FieldKind.GENERAL, 0, 0,
                TextRange.UNKNOWN, "", "", "", false, false, 0, null));
    }

    @Test
    public void enforcesSelectionEvidenceWithoutNormalizingDirection() {
        assertEquals(TextRange.UNKNOWN, new TextRange(-1, -1));
        assertFalse(TextRange.UNKNOWN.isKnown());
        assertTrue(new TextRange(2, 2).isCollapsed());
        assertTrue(new TextRange(8, 3).hasSelection());
        assertEquals(new TextRange(8, 3), snapshot(
                new TextRange(8, 3), "value", "", "", false, false).selection());
        assertEquals(new TextRange(3, 8), snapshot(
                new TextRange(3, 8), "value", "", "", false, false).selection());
        assertEquals(new TextRange(4, 2), snapshot(
                new TextRange(4, 2), "\uD83D\uDE00", "", "", false, false).selection());

        assertIllegal(() -> new TextRange(-1, 0));
        assertIllegal(() -> new TextRange(-2, -2));
        assertIllegal(() -> snapshot(TextRange.UNKNOWN, "x", "", "", false, false));
        assertIllegal(() -> snapshot(new TextRange(1, 1), "x", "", "", false, false));
        assertIllegal(() -> snapshot(new TextRange(1, 2), "", "", "", false, false));
        assertIllegal(() -> snapshot(new TextRange(0, 100), "x", "", "", false, false));
        assertIllegal(() -> snapshot(
                new TextRange(Integer.MAX_VALUE, 0), "x", "", "", false, false));
    }

    @Test
    public void selectedTextLimitCountsUnicodeCodePoints() {
        String emoji = "\uD83D\uDE00";
        String accepted = emoji.repeat(EditorSessionLimits.MAX_SELECTED_TEXT_CODE_POINTS);
        EditorSessionSnapshot snapshot = snapshot(
                new TextRange(0, accepted.length()), accepted, "", "", false, false);
        assertEquals(EditorSessionLimits.MAX_SELECTED_TEXT_CODE_POINTS,
                snapshot.selectedText().codePointCount(0, snapshot.selectedText().length()));

        String rejected = accepted + emoji;
        assertIllegal(() -> snapshot(
                new TextRange(0, rejected.length()), rejected, "", "", false, false));
        assertIllegal(() -> snapshot(new TextRange(0, 1), "\uD83D", "", "", false, false));
    }

    @Test
    public void surroundingInputIsBoundedBeforeHasherInjection() {
        String asciiLimit = "a".repeat(EditorSessionLimits.MAX_SURROUNDING_INPUT_UTF16_UNITS);
        snapshot(TextRange.UNKNOWN, "", asciiLimit, asciiLimit, false, false);
        assertIllegal(() -> snapshot(
                TextRange.UNKNOWN, "", asciiLimit + "a", "", false, false));

        String emoji = "\uD83D\uDE00";
        String emojiLimit = emoji.repeat(EditorSessionLimits.MAX_SURROUNDING_INPUT_UTF16_UNITS / 2);
        snapshot(TextRange.UNKNOWN, "", emojiLimit, emojiLimit, false, false);
        assertIllegal(() -> snapshot(
                TextRange.UNKNOWN, "", emojiLimit + emoji, "", false, false));

        EditorTextHasher permissive = new EditorTextHasher() {
            @Override public TextFingerprint selectedText(String value) {
                return Sha256EditorTextHasher.INSTANCE.selectedText("");
            }
            @Override public TextFingerprint beforeContext(String value) {
                return Sha256EditorTextHasher.INSTANCE.beforeContext("");
            }
            @Override public TextFingerprint afterContext(String value) {
                return Sha256EditorTextHasher.INSTANCE.afterContext("");
            }
            @Override public TextFingerprint context(String a, String b, String c) {
                return Sha256EditorTextHasher.INSTANCE.context("", "", "");
            }
            @Override public TextFingerprint committedText(String value) {
                return Sha256EditorTextHasher.INSTANCE.committedText("");
            }
        };
        assertIllegal(() -> EditorSessionSnapshot.capture(
                1, 2, "com.example", 0, FieldKind.GENERAL, 0, 0,
                TextRange.UNKNOWN, "", asciiLimit + "x", "", false, false, 0, permissive));
    }

    @Test
    public void sensitiveSnapshotsAreRedactedAndLearningIsDisabled() {
        EditorSessionSnapshot sensitive = create(
                1,
                2,
                "com.example",
                FieldKind.SENSITIVE,
                new TextRange(1, 4),
                "",
                "",
                "",
                false,
                true,
                3);
        assertTrue(sensitive.sensitive());
        assertFalse(sensitive.learningAllowed());
        assertEquals("", sensitive.selectedText());
        assertEquals(
                Sha256EditorTextHasher.INSTANCE.selectedText(""),
                sensitive.selectedTextFingerprint());
        assertFalse(sensitive.toString().contains("com.example"));
        assertFalse(sensitive.toString().contains("fieldId"));
        assertFalse(sensitive.toString().contains("selection"));
        assertFalse(sensitive.toString().contains(sensitive.contextFingerprint().sha256Hex()));

        assertIllegal(() -> create(1, 2, "com.example", FieldKind.SENSITIVE,
                TextRange.UNKNOWN, "", "", "", false, false, 3));
        assertIllegal(() -> create(1, 2, "com.example", FieldKind.GENERAL,
                TextRange.UNKNOWN, "", "", "", true, true, 3));
        assertIllegal(() -> create(1, 2, "com.example", FieldKind.SENSITIVE,
                new TextRange(1, 4), "secret", "", "", false, true, 3));
        assertIllegal(() -> create(1, 2, "com.example", FieldKind.SENSITIVE,
                new TextRange(1, 4), "", "secret", "", false, true, 3));

        EditorSessionSnapshot noLearning = snapshot(
                TextRange.UNKNOWN, "", "", "", false, false);
        assertFalse(noLearning.learningAllowed());
        assertFalse(noLearning.sensitive());
    }

    @Test
    public void rejectsHasherThatReturnsWrongDomainOrNull() {
        EditorTextHasher wrongDomain = new EditorTextHasher() {
            @Override public TextFingerprint selectedText(String value) {
                return Sha256EditorTextHasher.INSTANCE.beforeContext(value);
            }
            @Override public TextFingerprint beforeContext(String value) {
                return Sha256EditorTextHasher.INSTANCE.beforeContext(value);
            }
            @Override public TextFingerprint afterContext(String value) {
                return Sha256EditorTextHasher.INSTANCE.afterContext(value);
            }
            @Override public TextFingerprint context(String a, String b, String c) {
                return Sha256EditorTextHasher.INSTANCE.context(a, b, c);
            }
            @Override public TextFingerprint committedText(String value) {
                return Sha256EditorTextHasher.INSTANCE.committedText(value);
            }
        };
        assertIllegal(() -> EditorSessionSnapshot.capture(
                1, 2, "com.example", 0, FieldKind.GENERAL, 0, 0,
                TextRange.UNKNOWN, "", "", "", false, false, 0, wrongDomain));

        EditorTextHasher nullHasher = new EditorTextHasher() {
            @Override public TextFingerprint selectedText(String value) { return null; }
            @Override public TextFingerprint beforeContext(String value) { return null; }
            @Override public TextFingerprint afterContext(String value) { return null; }
            @Override public TextFingerprint context(String a, String b, String c) { return null; }
            @Override public TextFingerprint committedText(String value) { return null; }
        };
        assertNullRejected(() -> EditorSessionSnapshot.capture(
                1, 2, "com.example", 0, FieldKind.GENERAL, 0, 0,
                TextRange.UNKNOWN, "", "", "", false, false, 0, nullHasher));
    }

    private static EditorSessionSnapshot snapshot(
            TextRange selection,
            String selected,
            String before,
            String after,
            boolean learningAllowed,
            boolean sensitive) {
        return create(1, 2, "com.example", FieldKind.GENERAL, selection, selected,
                before, after, learningAllowed, sensitive, 3);
    }

    private static EditorSessionSnapshot create(
            long epoch,
            long token,
            String packageName,
            FieldKind fieldKind,
            TextRange selection,
            String selected,
            String before,
            String after,
            boolean learningAllowed,
            boolean sensitive,
            long capturedAt) {
        return EditorSessionSnapshot.capture(
                epoch, token, packageName, -1, fieldKind, -7, -9, selection,
                selected, before, after, learningAllowed, sensitive, capturedAt);
    }

    private static void assertIllegal(Runnable action) {
        try {
            action.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void assertNullRejected(Runnable action) {
        try {
            action.run();
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // Expected.
        }
    }
}
