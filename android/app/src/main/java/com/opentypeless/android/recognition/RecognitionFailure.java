package com.opentypeless.android.recognition;

public record RecognitionFailure(int errorCode, String message) {
    public RecognitionFailure {
        message = message == null || message.isBlank()
                ? "Speech recognition failed"
                : message.trim();
    }
}
