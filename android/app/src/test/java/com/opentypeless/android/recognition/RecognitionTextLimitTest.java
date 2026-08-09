package com.opentypeless.android.recognition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class RecognitionTextLimitTest {
    @Test
    public void preservesShortText() {
        assertEquals("hello 世界", RecognitionTextLimit.apply("hello 世界"));
    }

    @Test
    public void rejectsOversizedProviderResultInsteadOfInsertingTruncatedText() {
        String value = "a".repeat(RecognitionTextLimit.MAX_CODE_POINTS - 1) + "😀tail";

        assertThrows(IllegalArgumentException.class, () -> RecognitionTextLimit.apply(value));
    }
}
