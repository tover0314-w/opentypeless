package com.opentypeless.android.speech.transform;

/** Determines which bounded transformations are legal for one segment revision. */
public enum TransformPhase {
    LIVE,
    SOFT_BOUNDARY,
    REFINED
}
