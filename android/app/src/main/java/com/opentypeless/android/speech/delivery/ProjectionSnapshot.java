package com.opentypeless.android.speech.delivery;

import java.util.Objects;

/** Bounded readback used before and after every editor mutation. */
public record ProjectionSnapshot(
        Object connectionIdentity,
        ProjectionContext context,
        String textBeforeCursor,
        String textAfterCursor) {
    public ProjectionSnapshot {
        Objects.requireNonNull(connectionIdentity, "connectionIdentity");
        Objects.requireNonNull(context, "context");
        textBeforeCursor = Objects.requireNonNullElse(textBeforeCursor, "");
        textAfterCursor = Objects.requireNonNullElse(textAfterCursor, "");
        if (textBeforeCursor.length() > EditorProjectionLimits.MAX_SNAPSHOT_UTF16
                || textAfterCursor.length() > EditorProjectionLimits.MAX_SNAPSHOT_UTF16) {
            throw new IllegalArgumentException("editor snapshot exceeds bound");
        }
    }
}
