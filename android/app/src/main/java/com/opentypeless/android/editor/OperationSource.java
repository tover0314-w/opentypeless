package com.opentypeless.android.editor;

/** Closed producer identity recorded by the content-free editor transaction audit envelope. */
public enum OperationSource {
    LATIN,
    RIME,
    VOICE,
    ACTION,
    UNDO,
    RAW_RESTORE
}
