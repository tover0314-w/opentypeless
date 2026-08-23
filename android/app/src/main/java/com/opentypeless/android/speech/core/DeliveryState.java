package com.opentypeless.android.speech.core;

/** Android editor projection state. It never implies recognition finality. */
public enum DeliveryState {
    NOT_PROJECTED,
    COMPOSING,
    FROZEN,
    COMMITTED,
    RECOVERABLE
}
