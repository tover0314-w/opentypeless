package com.opentypeless.android.speech.core;

/** Model-owned lifecycle of one ordered speech segment. */
public enum SegmentStage {
    OPEN,
    SOFT_BOUNDARY,
    REFINING,
    SEALED
}
