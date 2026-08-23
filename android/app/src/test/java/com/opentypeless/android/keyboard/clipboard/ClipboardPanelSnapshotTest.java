package com.opentypeless.android.keyboard.clipboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ClipboardPanelSnapshotTest {
    @Test
    public void plainTextKeepsExactBodyAndUsesCodePointSafePreview() {
        ClipboardPanelSnapshot snapshot = ClipboardPanelSnapshot.fromPrimaryText("你好🙂abc");

        assertEquals(ClipboardPanelSnapshot.State.TEXT, snapshot.state());
        assertEquals("你好🙂abc", snapshot.text());
        assertEquals("你好🙂…", snapshot.preview(3));
    }

    @Test
    public void unsupportedAndOversizedInputsNeverRetainPartialText() {
        ClipboardPanelSnapshot nullText = ClipboardPanelSnapshot.fromPrimaryText(null);
        ClipboardPanelSnapshot invalidControl =
                ClipboardPanelSnapshot.fromPrimaryText("safe\u0000secret");
        ClipboardPanelSnapshot malformed =
                ClipboardPanelSnapshot.fromPrimaryText("broken\ud800");
        ClipboardPanelSnapshot oversized = ClipboardPanelSnapshot.fromPrimaryText(
                "x".repeat(ClipboardPanelSnapshot.MAX_TEXT_CODE_POINTS + 1));

        assertEquals(ClipboardPanelSnapshot.State.UNSUPPORTED, nullText.state());
        assertEquals(ClipboardPanelSnapshot.State.UNSUPPORTED, invalidControl.state());
        assertEquals(ClipboardPanelSnapshot.State.UNSUPPORTED, malformed.state());
        assertEquals(ClipboardPanelSnapshot.State.TOO_LARGE, oversized.state());
        assertFalse(nullText.hasText());
        assertThrows(IllegalStateException.class, oversized::text);
    }

    @Test
    public void diagnosticsExposeOnlyStateAndLength() {
        String secret = "private clipboard body";
        String diagnostic = ClipboardPanelSnapshot.fromPrimaryText(secret).toString();

        assertTrue(diagnostic.contains("state=TEXT"));
        assertTrue(diagnostic.contains("textCodePoints=22"));
        assertFalse(diagnostic.contains(secret));
    }

    @Test
    public void emptyAndAllowedWhitespaceHaveDistinctStates() {
        assertEquals(
                ClipboardPanelSnapshot.State.EMPTY,
                ClipboardPanelSnapshot.fromPrimaryText("").state());
        assertEquals("\n\t", ClipboardPanelSnapshot.fromPrimaryText("\n\t").text());
    }
}
