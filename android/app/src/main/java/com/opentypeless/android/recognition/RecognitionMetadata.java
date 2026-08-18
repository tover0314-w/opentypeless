package com.opentypeless.android.recognition;

import java.util.IllformedLocaleException;
import java.util.Locale;

/** Bounded, content-free metadata attached to one final recognition event. */
public record RecognitionMetadata(
        String detectedLanguageTag,
        Float confidence,
        Long audioDurationMs) {
    public static final int MAX_LANGUAGE_TAG_CODE_POINTS = 63;

    public RecognitionMetadata {
        detectedLanguageTag = validatedLanguageTag(detectedLanguageTag);
        if (confidence != null
                && (!Float.isFinite(confidence) || confidence < 0f || confidence > 1f)) {
            throw new IllegalArgumentException("recognition confidence must be between zero and one");
        }
        if (audioDurationMs != null
                && (audioDurationMs <= 0L
                        || audioDurationMs > ProviderCapabilities.APP_CAPTURE_LIMIT_MS)) {
            throw new IllegalArgumentException("recognition duration is outside the app capture bound");
        }
    }

    public static RecognitionMetadata empty() {
        return new RecognitionMetadata(null, null, null);
    }

    @Override
    public String toString() {
        return "RecognitionMetadata{languageDeclared=" + (detectedLanguageTag != null)
                + ", confidenceDeclared=" + (confidence != null)
                + ", durationDeclared=" + (audioDurationMs != null) + "}";
    }

    private static String validatedLanguageTag(String value) {
        if (value == null) return null;
        if (value.isEmpty()
                || !value.equals(value.strip())
                || value.codePointCount(0, value.length()) > MAX_LANGUAGE_TAG_CODE_POINTS) {
            throw new IllegalArgumentException("detected language tag is outside its bound");
        }
        try {
            return new Locale.Builder().setLanguageTag(value).build().toLanguageTag();
        } catch (IllformedLocaleException error) {
            throw new IllegalArgumentException("detected language tag is invalid");
        }
    }
}
