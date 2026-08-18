package com.opentypeless.android.personalization;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.context.FieldKind;
import com.opentypeless.android.data.HistoryEntry;
import com.opentypeless.android.editor.CommitRecord;
import com.opentypeless.android.editor.EditorSessionSnapshot;
import com.opentypeless.android.editor.OperationSource;
import com.opentypeless.android.editor.TextRange;

import org.junit.Test;

public final class TeachCorrectionResolverTest {
    @Test
    public void exactCommitRecordOverridesStaleHistoryTextAndScope() {
        HistoryEntry stored = new HistoryEntry(
                42L, 100L, "old.app", "LONG_TEXT", "SMART", "SYSTEM_ON_DEVICE",
                "stale raw", "stale final", 900L, "old rule");
        CommitRecord record = voiceRecord(
                true,
                new CommitRecord.RawTranscript.Present("exact raw"),
                "exact final");

        HistoryEntry resolved = TeachCorrectionResolver.resolve(stored, record);

        assertEquals(42L, resolved.id());
        assertEquals(100L, resolved.createdAt());
        assertEquals("current.app", resolved.appPackage());
        assertEquals("GENERAL", resolved.fieldKind());
        assertEquals("exact raw", resolved.rawText());
        assertEquals("exact final", resolved.finalText());
        assertEquals("old rule", resolved.appliedRules());
        assertTrue(TeachCorrectionResolver.isEligible(record));
    }

    @Test
    public void noLearningAndMissingRawRecordsCannotTeach() {
        CommitRecord noLearning = voiceRecord(
                false,
                new CommitRecord.RawTranscript.Present("private raw"),
                "private final");
        CommitRecord missingRaw = voiceRecord(
                true,
                new CommitRecord.RawTranscript.Absent(),
                "final");

        assertFalse(TeachCorrectionResolver.isEligible(noLearning));
        assertFalse(TeachCorrectionResolver.isEligible(missingRaw));
        assertNull(TeachCorrectionResolver.resolve(null, noLearning));
        assertNull(TeachCorrectionResolver.resolve(null, missingRaw));
    }

    @Test
    public void explicitCurrentTextOverridesStalePolishedHistory() {
        HistoryEntry stored = new HistoryEntry(
                42L, 100L, "old.app", "LONG_TEXT", "SMART", "SYSTEM_ON_DEVICE",
                "wrong name", "unsafe polished name", 900L);

        HistoryEntry resolved = TeachCorrectionResolver.resolve(
                stored, "wrong name", "wrong name", "current.app");

        assertEquals("wrong name", resolved.rawText());
        assertEquals("wrong name", resolved.finalText());
        assertEquals("current.app", resolved.appPackage());
        assertEquals(42L, resolved.id());
    }

    @Test
    public void equalRawAndFinalCanStartAFirstCorrection() {
        HistoryEntry resolved = TeachCorrectionResolver.resolve(
                null, "Open type less", "Open type less", "chat.app");

        assertEquals("Open type less", resolved.rawText());
        assertEquals("Open type less", resolved.finalText());
        assertEquals("chat.app", resolved.appPackage());
    }

    @Test
    public void missingHistoryAndMissingExtrasCannotTeach() {
        assertNull(TeachCorrectionResolver.resolve(null, "", "", ""));
    }

    private static CommitRecord voiceRecord(
            boolean learningAllowed,
            CommitRecord.RawTranscript raw,
            String insertedText) {
        EditorSessionSnapshot origin = EditorSessionSnapshot.capture(
                1L,
                2L,
                "current.app",
                3,
                FieldKind.GENERAL,
                0,
                0,
                new TextRange(4, 4),
                "",
                "before",
                "after",
                learningAllowed,
                false,
                5L);
        return CommitRecord.create(
                "commit-id",
                OperationSource.VOICE,
                origin,
                insertedText,
                raw);
    }
}
