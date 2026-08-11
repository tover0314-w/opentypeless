package com.opentypeless.android.speech.core;

/** Why capture stopped. A terminal capture does not prevent pending refinement or recovery. */
public enum TerminalReason {
    NONE,
    USER_FINISH,
    END_OF_AUDIO,
    DURATION_LIMIT,
    ENGINE_FAILURE,
    LIFECYCLE_DETACH,
    EMPTY_AUDIO,
    EXPLICIT_DISCARD
}
