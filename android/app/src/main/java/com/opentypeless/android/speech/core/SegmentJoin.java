package com.opentypeless.android.speech.core;

/** Explicit document separator before a segment; adapters must not guess it in the reducer. */
public enum SegmentJoin {
    NONE(""),
    SPACE(" "),
    NEWLINE("\n");

    private final String delimiter;

    SegmentJoin(String delimiter) {
        this.delimiter = delimiter;
    }

    public String delimiter() {
        return delimiter;
    }
}
