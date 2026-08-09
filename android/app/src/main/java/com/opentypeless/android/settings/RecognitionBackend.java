package com.opentypeless.android.settings;

public enum RecognitionBackend {
    OPENAI_COMPATIBLE("BYOK / self-hosted"),
    SYSTEM_ON_DEVICE("Android on-device"),
    SYSTEM_DEFAULT("Android system service");

    private final String label;

    RecognitionBackend(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static RecognitionBackend fromStored(String value) {
        if (value == null) return OPENAI_COMPATIBLE;
        try {
            return valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return OPENAI_COMPATIBLE;
        }
    }
}
