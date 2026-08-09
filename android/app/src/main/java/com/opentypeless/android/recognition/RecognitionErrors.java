package com.opentypeless.android.recognition;

import android.os.Build;
import android.speech.SpeechRecognizer;

import com.opentypeless.android.settings.RecognitionBackend;

import java.util.Locale;

public final class RecognitionErrors {
    private RecognitionErrors() {}

    public static RecognitionFailure busy() {
        return new RecognitionFailure(
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                "Another speech recognition session is already active");
    }

    public static RecognitionFailure noMatch() {
        return new RecognitionFailure(
                SpeechRecognizer.ERROR_NO_MATCH,
                "Speech recognition returned no text");
    }

    public static int rateLimitedCode() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? SpeechRecognizer.ERROR_TOO_MANY_REQUESTS
                : SpeechRecognizer.ERROR_RECOGNIZER_BUSY;
    }

    public static RecognitionFailure unsupportedBackend(RecognitionBackend backend) {
        String label = backend == null ? "unknown" : backend.label();
        return new RecognitionFailure(
                SpeechRecognizer.ERROR_CLIENT,
                "Android standard speech entry supports only the BYOK / OpenAI-compatible "
                        + "backend; current backend is " + label);
    }

    public static RecognitionFailure endpointNotConfigured() {
        return new RecognitionFailure(
                SpeechRecognizer.ERROR_CLIENT,
                "Configure an OpenAI-compatible speech endpoint and model before using "
                        + "Android standard speech entry");
    }

    public static RecognitionFailure fromPipelineMessage(String message) {
        String clean = message == null || message.isBlank()
                ? "Speech recognition failed"
                : message.trim();
        String lower = clean.toLowerCase(Locale.ROOT);
        int code;
        if (lower.contains("permission")) {
            code = SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS;
        } else if (lower.contains("no speech")
                || lower.contains("too short")
                || lower.contains("no text")) {
            code = SpeechRecognizer.ERROR_NO_MATCH;
        } else if (lower.contains("timed out") || lower.contains("timeout")) {
            code = SpeechRecognizer.ERROR_NETWORK_TIMEOUT;
        } else if (lower.contains("network")
                || lower.contains("endpoint")
                || lower.contains("redirect")) {
            code = SpeechRecognizer.ERROR_NETWORK;
        } else if (lower.contains("cancel")) {
            code = SpeechRecognizer.ERROR_CLIENT;
        } else if (lower.contains("microphone") || lower.contains("audio")) {
            code = SpeechRecognizer.ERROR_AUDIO;
        } else {
            code = SpeechRecognizer.ERROR_SERVER;
        }
        return new RecognitionFailure(code, clean);
    }
}
