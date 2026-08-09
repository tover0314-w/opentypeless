package com.opentypeless.android.recognition;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.speech.SpeechRecognizer;

import org.junit.Test;

public final class RecognitionModelDownloadPolicyTest {
    @Test
    public void fallsBackOnlyWhenLiveDownloadEventsAreUnavailable() {
        assertTrue(RecognitionModelDownloadPolicy.shouldFallbackWithoutEvents(
                SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS));
        assertFalse(RecognitionModelDownloadPolicy.shouldFallbackWithoutEvents(
                SpeechRecognizer.ERROR_CLIENT));
    }
}
