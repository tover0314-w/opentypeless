package com.opentypeless.android.recognition;

import android.annotation.SuppressLint;
import android.speech.SpeechRecognizer;

/** Compatibility decisions for optional model-download event delivery. */
final class RecognitionModelDownloadPolicy {
    private RecognitionModelDownloadPolicy() {}

    @SuppressLint("InlinedApi") // Called only by the API 34 implementation; the value is inlined.
    static boolean shouldFallbackWithoutEvents(int errorCode) {
        return errorCode == SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS;
    }
}
