package com.opentypeless.android.recognition;

import android.os.Build;
import android.speech.SpeechRecognizer;

import com.opentypeless.android.config.RecognitionRoute;
import com.opentypeless.android.settings.RecognitionBackend;

public final class RecognitionErrors {
    private RecognitionErrors() {}

    public static RecognitionFailure busy() {
        return new RecognitionFailure(
                RecognitionRoute.FailureClass.RECOGNIZER_BUSY,
                RecognitionFailureMapper.stableMessage(
                        RecognitionRoute.FailureClass.RECOGNIZER_BUSY));
    }

    public static RecognitionFailure noMatch() {
        return new RecognitionFailure(
                RecognitionRoute.FailureClass.NO_MATCH,
                RecognitionFailureMapper.stableMessage(RecognitionRoute.FailureClass.NO_MATCH));
    }

    public static int rateLimitedCode() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? SpeechRecognizer.ERROR_TOO_MANY_REQUESTS
                : SpeechRecognizer.ERROR_RECOGNIZER_BUSY;
    }

    public static RecognitionFailure unsupportedBackend(RecognitionBackend backend) {
        String label = backend == null ? "unknown" : backend.label();
        return new RecognitionFailure(
                RecognitionRoute.FailureClass.UNAVAILABLE,
                "Android standard speech entry supports only the BYOK / OpenAI-compatible "
                        + "backend; current backend is " + label);
    }

    public static RecognitionFailure endpointNotConfigured() {
        return new RecognitionFailure(
                RecognitionRoute.FailureClass.AUTHENTICATION,
                SpeechRecognizer.ERROR_CLIENT,
                "Configure an OpenAI-compatible speech endpoint and model before using "
                        + "Android standard speech entry");
    }

    public static RecognitionFailure fromPipelineMessage(String message) {
        RecognitionRoute.FailureClass failureClass =
                RecognitionFailureMapper.fromLegacyPipelineMessage(message);
        return new RecognitionFailure(
                failureClass,
                RecognitionFailureMapper.stableMessage(failureClass));
    }
}
