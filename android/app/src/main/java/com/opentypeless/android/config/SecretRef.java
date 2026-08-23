package com.opentypeless.android.config;

import java.util.Objects;

/**
 * Opaque, non-secret identity for credentials owned by {@code SecretStore}.
 *
 * <p>The identifier is intentionally not a credential, bearer token, API key, alias supplied by
 * an external service, or persistence mechanism. A validated reference is resolved only by the
 * bounded SecretStore callback boundary; this value object prevents raw secret material from
 * entering provider config.
 */
public record SecretRef(Kind kind, String opaqueId) {
    public static final int MIN_OPAQUE_ID_CODE_POINTS = 20;
    public static final int MAX_OPAQUE_ID_CODE_POINTS = 128;
    private static final String PREFIX = "sec_";

    /** The provider family allowed to resolve this reference. */
    public enum Kind {
        ASR,
        LLM,
        CONNECTOR
    }

    public SecretRef {
        kind = Objects.requireNonNull(kind, "kind");
        opaqueId = requireOpaqueId(opaqueId);
    }

    private static String requireOpaqueId(String value) {
        String safe = Objects.requireNonNull(value, "opaqueId");
        if (safe.length() < MIN_OPAQUE_ID_CODE_POINTS
                || safe.length() > MAX_OPAQUE_ID_CODE_POINTS
                || !safe.startsWith(PREFIX)) {
            throw new IllegalArgumentException("opaque secret reference id has an invalid shape");
        }
        for (int index = PREFIX.length(); index < safe.length(); index++) {
            char character = safe.charAt(index);
            boolean allowed = (character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9')
                    || character == '_'
                    || character == '-';
            if (!allowed) {
                throw new IllegalArgumentException("opaque secret reference id has an invalid shape");
            }
        }
        return safe;
    }

    @Override
    public String toString() {
        return "SecretRef{kind=" + kind + ", opaqueId=<redacted>}";
    }
}
