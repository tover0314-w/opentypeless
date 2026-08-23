package com.opentypeless.android.editor;

import java.util.Objects;

/** Package-private construction boundary shared by operations and committed-text hashing. */
final class EditorOperationLimits {
    static final int MAX_TEXT_CODE_POINTS = 40_000;
    static final int MAX_TEXT_UTF16_UNITS = MAX_TEXT_CODE_POINTS * 2;

    private EditorOperationLimits() {}

    static String requireText(String value, String name, boolean emptyAllowed) {
        String safe = Objects.requireNonNull(value, name);
        if (safe.length() > MAX_TEXT_UTF16_UNITS) {
            throw new IllegalArgumentException(name + " exceeds the operation text bound");
        }
        EditorSessionLimits.requireWellFormedUtf16(safe, name);
        if (safe.codePointCount(0, safe.length()) > MAX_TEXT_CODE_POINTS) {
            throw new IllegalArgumentException(name + " exceeds the operation text bound");
        }
        if (!emptyAllowed && safe.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        for (int offset = 0; offset < safe.length(); ) {
            int codePoint = safe.codePointAt(offset);
            if (Character.isISOControl(codePoint)
                    && codePoint != '\t'
                    && codePoint != '\n'
                    && codePoint != '\r') {
                throw new IllegalArgumentException(name + " contains a forbidden control character");
            }
            offset += Character.charCount(codePoint);
        }
        return safe;
    }
}
