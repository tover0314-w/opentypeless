package com.opentypeless.android.speech.delivery;

import java.util.Objects;

/** Current editor identity supplied by the IME lifecycle, independent from surrounding text. */
public record ProjectionContext(
        long editorEpoch,
        String packageName,
        int fieldId,
        int selectionStart,
        int selectionEnd,
        boolean sensitive) {
    public ProjectionContext {
        if (editorEpoch <= 0L) throw new IllegalArgumentException("editor epoch must be positive");
        packageName = Objects.requireNonNullElse(packageName, "");
        if (packageName.isBlank() || packageName.length() > 512) {
            throw new IllegalArgumentException("editor package is invalid");
        }
        if (selectionStart < -1 || selectionEnd < -1) {
            throw new IllegalArgumentException("editor selection is invalid");
        }
    }

    public boolean selectionKnown() {
        return selectionStart >= 0 && selectionEnd >= 0;
    }

    public boolean hasSelection() {
        return selectionKnown() && selectionStart != selectionEnd;
    }
}
