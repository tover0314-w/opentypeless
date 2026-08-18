package com.opentypeless.android.editor;

import java.util.Objects;

/** Bounded, code-point-safe limits shared by editor snapshot capture and validation. */
public final class EditorSessionLimits {
    public static final int MAX_PACKAGE_NAME_UTF16_UNITS = 512;
    public static final int MAX_SELECTED_TEXT_CODE_POINTS = 4_000;
    public static final int MAX_SURROUNDING_INPUT_UTF16_UNITS = 800;
    public static final int SURROUNDING_CONTEXT_CODE_POINTS = 64;
    public static final int SHA256_HEX_LENGTH = 64;

    private EditorSessionLimits() {}

    public static String requirePackageName(String value) {
        Objects.requireNonNull(value, "packageName");
        if (value.length() > MAX_PACKAGE_NAME_UTF16_UNITS) {
            throw new IllegalArgumentException("packageName exceeds UTF-16 limit");
        }
        requireWellFormedUtf16(value, "packageName");
        if (value.isBlank()) {
            throw new IllegalArgumentException("packageName must not be blank");
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("packageName must not contain control characters");
        }
        return value;
    }

    public static String requireSelectedText(String value) {
        Objects.requireNonNull(value, "selectedText");
        if (value.length() > MAX_SELECTED_TEXT_CODE_POINTS * 2) {
            throw new IllegalArgumentException("selectedText exceeds code-point limit");
        }
        requireWellFormedUtf16(value, "selectedText");
        if (value.codePointCount(0, value.length()) > MAX_SELECTED_TEXT_CODE_POINTS) {
            throw new IllegalArgumentException("selectedText exceeds code-point limit");
        }
        return value;
    }

    public static String requireSurroundingInput(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.length() > MAX_SURROUNDING_INPUT_UTF16_UNITS) {
            throw new IllegalArgumentException(name + " exceeds UTF-16 input limit");
        }
        requireWellFormedUtf16(value, name);
        return value;
    }

    public static String boundedBeforeTail(String value) {
        return tailCodePoints(value, SURROUNDING_CONTEXT_CODE_POINTS, "beforeText");
    }

    public static String boundedAfterHead(String value) {
        return headCodePoints(value, SURROUNDING_CONTEXT_CODE_POINTS, "afterText");
    }

    public static void requireWellFormedUtf16(String value, String name) {
        Objects.requireNonNull(value, name);
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(name + " contains an unpaired surrogate");
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException(name + " contains an unpaired surrogate");
            }
        }
    }

    private static String headCodePoints(String value, int limit, String name) {
        requireSurroundingInput(value, name);
        int count = value.codePointCount(0, value.length());
        if (count <= limit) return value;
        return value.substring(0, value.offsetByCodePoints(0, limit));
    }

    private static String tailCodePoints(String value, int limit, String name) {
        requireSurroundingInput(value, name);
        int count = value.codePointCount(0, value.length());
        if (count <= limit) return value;
        return value.substring(value.offsetByCodePoints(0, count - limit));
    }
}
