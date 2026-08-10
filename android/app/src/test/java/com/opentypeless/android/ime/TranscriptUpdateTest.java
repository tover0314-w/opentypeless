package com.opentypeless.android.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class TranscriptUpdateTest {
    @Test
    public void combinesStableAndReplaceableTextWithoutGuessingBoundaries() {
        TranscriptUpdate update = new TranscriptUpdate(
                7,
                "已经稳定，",
                "正在修改",
                false,
                TranscriptUpdate.Source.DASHSCOPE_PARAFORMER);

        assertEquals("已经稳定，正在修改", update.text());
    }

    @Test
    public void rejectsInvalidFinalAndNonMonotonicShapes() {
        assertThrows(IllegalArgumentException.class, () -> TranscriptUpdate.unstable(
                0, "text", TranscriptUpdate.Source.ANDROID_SYSTEM));
        assertThrows(IllegalArgumentException.class, () -> new TranscriptUpdate(
                1,
                "final",
                "not final",
                true,
                TranscriptUpdate.Source.FUNASR));
    }
}
