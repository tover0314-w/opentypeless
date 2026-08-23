package com.opentypeless.android.recognition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.speech.SpeechRecognizer;

import com.opentypeless.android.config.RecognitionRoute;
import com.opentypeless.android.settings.RecognitionBackend;

import org.junit.Test;

public final class RecognitionErrorsTest {
    @Test
    public void rejectsSystemBackendsWithActionableMessage() {
        RecognitionFailure failure = RecognitionErrors.unsupportedBackend(
                RecognitionBackend.SYSTEM_DEFAULT);

        assertEquals(SpeechRecognizer.ERROR_CLIENT, failure.errorCode());
        assertEquals(RecognitionRoute.FailureClass.UNAVAILABLE, failure.failureClass());
        assertTrue(failure.message().contains("only the BYOK / OpenAI-compatible backend"));
        assertTrue(failure.message().contains("Android system service"));

        RecognitionFailure missingEndpoint = RecognitionErrors.endpointNotConfigured();
        assertEquals(RecognitionRoute.FailureClass.AUTHENTICATION, missingEndpoint.failureClass());
        assertEquals(SpeechRecognizer.ERROR_CLIENT, missingEndpoint.errorCode());
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
                SpeechRecognizer.ERROR_CLIENT,
                RecognitionErrors.fromPipelineMessage("Provider redirect was rejected")
                        .errorCode());
        assertEquals(
                SpeechRecognizer.ERROR_CLIENT,
                RecognitionErrors.fromPipelineMessage("Unexpected provider response")
                        .errorCode());
        RecognitionFailure redacted = RecognitionErrors.fromPipelineMessage(
                "Unexpected provider response provider-secret");
        assertEquals(RecognitionRoute.FailureClass.PROTOCOL_ERROR, redacted.failureClass());
        assertTrue(!redacted.message().contains("provider-secret"));
        assertTrue(!redacted.toString().contains("provider-secret"));
    }
}
