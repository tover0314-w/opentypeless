package com.opentypeless.android.recognition;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class RecognitionSupportPlatformPolicyTest {
    @Test
    public void api26Through32NeverClaimsLanguageWasPreflighted() {
        assertEquals(
                RecognitionSupportPlatformPolicy.Decision.LEGACY_NOT_VERIFIABLE,
                RecognitionSupportPlatformPolicy.decide(26, true));
        assertEquals(
                RecognitionSupportPlatformPolicy.Decision.LEGACY_NOT_VERIFIABLE,
                RecognitionSupportPlatformPolicy.decide(32, true));
    }

    @Test
    public void api33ChecksSupportAndMissingServiceAlwaysWins() {
        assertEquals(
                RecognitionSupportPlatformPolicy.Decision.CHECK_SUPPORT_API,
                RecognitionSupportPlatformPolicy.decide(33, true));
        assertEquals(
                RecognitionSupportPlatformPolicy.Decision.SERVICE_UNAVAILABLE,
                RecognitionSupportPlatformPolicy.decide(35, false));
    }
}
