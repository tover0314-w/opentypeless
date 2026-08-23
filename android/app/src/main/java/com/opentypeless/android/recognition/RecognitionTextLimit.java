package com.opentypeless.android.recognition;

/** Rejects untrusted, implausibly large text from an installed Android recognition provider. */
final class RecognitionTextLimit {
    static final int MAX_CODE_POINTS = 20_000;

    private RecognitionTextLimit() {}

    static String apply(String value) {
        if (value == null || value.isEmpty()) return "";
        int count = value.codePointCount(0, value.length());
        if (count <= MAX_CODE_POINTS) return value;
        throw new IllegalArgumentException(
                "Android speech recognition result exceeded the 20,000-character safety limit");
    }
}
