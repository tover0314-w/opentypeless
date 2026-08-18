package com.opentypeless.android.keyboard.rime;

import java.util.List;
import java.util.Objects;

/** Closed, bounded Rime Schema and option selection for one engine session. */
public record RimeRuntimeConfig(
        String schemaId,
        boolean simplifiedOutput,
        boolean asciiPunctuation,
        boolean fullShape) {
    public static final String OPTION_SIMPLIFICATION = "simplification";
    public static final String OPTION_ASCII_PUNCTUATION = "ascii_punct";
    public static final String OPTION_FULL_SHAPE = "full_shape";

    public RimeRuntimeConfig {
        schemaId = requireSchemaId(schemaId);
        if (fullShape && asciiPunctuation) {
            throw new IllegalArgumentException(
                    "full-shape and ASCII punctuation cannot be enabled together");
        }
    }

    public static RimeRuntimeConfig defaults(String schemaId) {
        return new RimeRuntimeConfig(schemaId, true, true, false);
    }

    public static RimeRuntimeConfig resolved(
            List<String> availableSchemas,
            String requestedSchema,
            boolean simplifiedOutput,
            boolean asciiPunctuation,
            boolean fullShape) {
        List<String> schemas = List.copyOf(Objects.requireNonNull(
                availableSchemas, "availableSchemas"));
        if (schemas.isEmpty()) {
            throw new IllegalArgumentException("availableSchemas must not be empty");
        }
        for (String schema : schemas) requireSchemaId(schema);
        String selected = requestedSchema != null && schemas.contains(requestedSchema)
                ? requestedSchema : schemas.get(0);
        boolean safeAsciiPunctuation = fullShape ? false : asciiPunctuation;
        return new RimeRuntimeConfig(
                selected, simplifiedOutput, safeAsciiPunctuation, fullShape);
    }

    public boolean optionValue(String optionName) {
        return switch (Objects.requireNonNull(optionName, "optionName")) {
            case OPTION_SIMPLIFICATION -> simplifiedOutput;
            case OPTION_ASCII_PUNCTUATION -> asciiPunctuation;
            case OPTION_FULL_SHAPE -> fullShape;
            default -> throw new IllegalArgumentException("unsupported Rime option");
        };
    }

    public static List<String> supportedOptions() {
        return List.of(
                OPTION_SIMPLIFICATION,
                OPTION_ASCII_PUNCTUATION,
                OPTION_FULL_SHAPE);
    }

    private static String requireSchemaId(String value) {
        String safe = Objects.requireNonNull(value, "schemaId");
        if (safe.isEmpty() || safe.length() > 256 || safe.codePointCount(0, safe.length()) > 128) {
            throw new IllegalArgumentException("Rime schema id exceeded its bound");
        }
        for (int index = 0; index < safe.length(); index++) {
            char character = safe.charAt(index);
            if (!((character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '.' || character == '_' || character == '-')) {
                throw new IllegalArgumentException("Rime schema id is invalid");
            }
        }
        return safe;
    }

    @Override
    public String toString() {
        return "RimeRuntimeConfig{schemaId=" + schemaId
                + ", simplifiedOutput=" + simplifiedOutput
                + ", asciiPunctuation=" + asciiPunctuation
                + ", fullShape=" + fullShape + '}';
    }
}
