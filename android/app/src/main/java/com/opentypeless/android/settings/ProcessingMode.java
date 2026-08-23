package com.opentypeless.android.settings;

public enum ProcessingMode {
    AUTO("Auto"),
    VERBATIM("Exact"),
    SMART("Smart"),
    TRANSLATE("Translate");

    private final String label;

    ProcessingMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static ProcessingMode fromStored(String value) {
        if (value == null) return AUTO;
        try {
            return valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return AUTO;
        }
    }
}
