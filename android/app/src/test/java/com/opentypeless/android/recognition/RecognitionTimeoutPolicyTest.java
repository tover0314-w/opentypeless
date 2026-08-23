package com.opentypeless.android.recognition;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class RecognitionTimeoutPolicyTest {
    @Test
    public void clampsAndConvertsConfiguredSeconds() {
        assertEquals(5_000L, RecognitionTimeoutPolicy.milliseconds(1));
        assertEquals(180_000L, RecognitionTimeoutPolicy.milliseconds(180));
        assertEquals(540_000L, RecognitionTimeoutPolicy.milliseconds(999));
    }
}
