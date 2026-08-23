package com.opentypeless.android.speech.core;

/** Lifecycle of microphone capture. Recognition and editor delivery have separate states. */
public enum CaptureState {
    IDLE,
    PREPARING,
    LISTENING,
    STOPPING,
    ENDED,
    FAILED,
    DISCARDED
}
