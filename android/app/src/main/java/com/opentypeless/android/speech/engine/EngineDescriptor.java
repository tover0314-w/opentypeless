package com.opentypeless.android.speech.engine;

import java.util.Objects;

/** Redacted engine identity and actual route; it must contain no key, URL query or user text. */
public record EngineDescriptor(
        String engineId,
        String displayName,
        String modelRevision,
        ProcessingLocation processingLocation,
        EngineCapabilities capabilities) {

    public static final int MAX_ID_CODE_POINTS = 128;
    public static final int MAX_LABEL_CODE_POINTS = 256;

    public EngineDescriptor {
        engineId = requireSafeText(engineId, "engineId", MAX_ID_CODE_POINTS);
        displayName = requireSafeText(displayName, "displayName", MAX_LABEL_CODE_POINTS);
        modelRevision = requireSafeText(modelRevision, "modelRevision", MAX_LABEL_CODE_POINTS);
        Objects.requireNonNull(processingLocation, "processingLocation");
        Objects.requireNonNull(capabilities, "capabilities");
        if (processingLocation == ProcessingLocation.NETWORK
                && capabilities.supports(EngineCapability.ON_DEVICE)) {
            throw new IllegalArgumentException("network route cannot claim on-device execution");
        }
        if (processingLocation == ProcessingLocation.ON_DEVICE
                && !capabilities.supports(EngineCapability.ON_DEVICE)) {
            throw new IllegalArgumentException("on-device route must declare on-device execution");
        }
    }

    static String requireSafeText(String value, String label, int maxCodePoints) {
        Objects.requireNonNull(value, label);
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        if (value.codePointCount(0, value.length()) > maxCodePoints) {
            throw new IllegalArgumentException(label + " is too long");
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(label + " contains control characters");
        }
        return value;
    }
}
