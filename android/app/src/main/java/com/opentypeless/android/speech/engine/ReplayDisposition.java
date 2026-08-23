package com.opentypeless.android.speech.engine;

/** Outcome of validating and reducing one normalized engine event. */
public enum ReplayDisposition {
    APPLIED,
    IGNORED,
    REJECTED_CORE,
    REJECTED_CAPABILITY,
    REJECTED_SOURCE_ORDER,
    REJECTED_ENGINE
}
