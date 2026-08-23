package com.opentypeless.android.speech.runtime;

/** Android-free normalized thermal severity. */
public enum ThermalLevel {
    UNKNOWN,
    NONE,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
    EMERGENCY,
    SHUTDOWN;

    public boolean atLeast(ThermalLevel threshold) {
        if (this == UNKNOWN) return threshold == UNKNOWN;
        if (threshold == UNKNOWN) return false;
        return ordinal() >= threshold.ordinal();
    }
}
