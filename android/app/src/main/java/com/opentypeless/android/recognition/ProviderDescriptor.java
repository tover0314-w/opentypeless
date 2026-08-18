package com.opentypeless.android.recognition;

import com.opentypeless.android.settings.RecognitionBackend;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Stable, content-free identity plus the reviewed capability declaration for one provider.
 *
 * <p>A descriptor is neither registration nor execution authority. Endpoints and credentials stay
 * in the configuration/SecretRef boundary; probe state belongs to REC-003.
 */
public record ProviderDescriptor(
        String id,
        String displayName,
        ProviderCapabilities capabilities) {
    public static final int MAX_ID_CODE_POINTS = 128;
    public static final int MAX_DISPLAY_NAME_CODE_POINTS = 80;

    private static final Pattern ID_PATTERN =
            Pattern.compile("[a-z][a-z0-9._-]{0,127}");

    public ProviderDescriptor {
        id = requireId(id);
        displayName = requireDisplayName(displayName);
        capabilities = Objects.requireNonNull(capabilities, "capabilities");
    }

    /** Explicit legacy-backend bridge; capability values never derive from labels or IDs. */
    public static ProviderDescriptor declaredForBackend(RecognitionBackend backend) {
        Objects.requireNonNull(backend, "backend");
        String id = switch (backend) {
            case OPENAI_COMPATIBLE -> "builtin.openai-compatible";
            case LOCAL_OFFLINE -> "builtin.local-offline";
            case DASHSCOPE_STREAMING -> "builtin.dashscope-streaming";
            case SYSTEM_ON_DEVICE -> "builtin.system-on-device";
            case SYSTEM_DEFAULT -> "builtin.system-default";
        };
        return new ProviderDescriptor(
                id,
                backend.label(),
                ProviderCapabilities.declaredForBackend(backend));
    }

    @Override
    public String toString() {
        return "ProviderDescriptor{id=<redacted>, displayName=<redacted>, privacyClass="
                + capabilities.privacyClass() + "}";
    }

    private static String requireId(String value) {
        String safe = requireText(value, "provider id", MAX_ID_CODE_POINTS);
        if (!ID_PATTERN.matcher(safe).matches()) {
            throw new IllegalArgumentException("provider id has invalid syntax");
        }
        return safe;
    }

    private static String requireDisplayName(String value) {
        return requireText(value, "display name", MAX_DISPLAY_NAME_CODE_POINTS);
    }

    private static String requireText(String value, String label, int maximumCodePoints) {
        String safe = Objects.requireNonNull(value, label);
        if (safe.isEmpty()
                || !safe.equals(safe.strip())
                || safe.codePointCount(0, safe.length()) > maximumCodePoints) {
            throw new IllegalArgumentException(label + " is outside its bound");
        }
        for (int index = 0; index < safe.length(); ) {
            char unit = safe.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (index + 1 >= safe.length()
                        || !Character.isLowSurrogate(safe.charAt(index + 1))) {
                    throw new IllegalArgumentException(label + " must be well-formed UTF-16");
                }
                index += 2;
                continue;
            }
            if (Character.isLowSurrogate(unit)) {
                throw new IllegalArgumentException(label + " must be well-formed UTF-16");
            }
            int codePoint = safe.codePointAt(index);
            if (Character.isISOControl(codePoint)) {
                throw new IllegalArgumentException(label + " contains a control character");
            }
            index += Character.charCount(codePoint);
        }
        return safe;
    }
}
