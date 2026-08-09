package com.opentypeless.android.personalization;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.opentypeless.android.data.HistoryEntry;

import org.junit.Test;

public final class TeachCorrectionResolverTest {
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
}
