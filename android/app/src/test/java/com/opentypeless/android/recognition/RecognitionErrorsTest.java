package com.opentypeless.android.recognition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.speech.SpeechRecognizer;

import com.opentypeless.android.settings.RecognitionBackend;

import org.junit.Test;

public final class RecognitionErrorsTest {
    @Test
    public void rejectsSystemBackendsWithActionableMessage() {
        RecognitionFailure failure = RecognitionErrors.unsupportedBackend(
                RecognitionBackend.SYSTEM_DEFAULT);

        assertEquals(SpeechRecognizer.ERROR_CLIENT, failure.errorCode());
        assertTrue(failure.message().contains("only the BYOK / OpenAI-compatible backend"));
        assertTrue(failure.message().contains("Android system service"));
    }

    @Test
    public void mapsPipelineFailuresToAndroidErrorCodes() {
        assertEquals(
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                RecognitionErrors.fromPipelineMessage("Microphone permission is required")
                        .errorCode());
        assertEquals(
                SpeechRecognizer.ERROR_NO_MATCH,
                RecognitionErrors.fromPipelineMessage("Recording was too short").errorCode());
        assertEquals(
                SpeechRecognizer.ERROR_NETWORK,
                RecognitionErrors.fromPipelineMessage("Provider redirect was rejected")
                        .errorCode());
        assertEquals(
                SpeechRecognizer.ERROR_SERVER,
                RecognitionErrors.fromPipelineMessage("Unexpected provider response")
                        .errorCode());
    }
}
