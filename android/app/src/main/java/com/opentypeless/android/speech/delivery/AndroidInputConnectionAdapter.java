package com.opentypeless.android.speech.delivery;

import android.view.inputmethod.InputConnection;
import java.util.Objects;

/** The sole v2 adapter that invokes Android {@link InputConnection}. */
public final class AndroidInputConnectionAdapter implements ProjectionConnection {
    private final InputConnection connection;
    private final ProjectionMetadataProvider metadata;

    public AndroidInputConnectionAdapter(
            InputConnection connection,
            ProjectionMetadataProvider metadata) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.metadata = Objects.requireNonNull(metadata, "metadata");
    }

    @Override
    public Object identity() {
        return connection;
    }

    @Override
    public ProjectionSnapshot snapshot(int maximumBeforeUtf16, int maximumAfterUtf16) {
        if (maximumBeforeUtf16 < 0
                || maximumAfterUtf16 < 0
                || maximumBeforeUtf16 > EditorProjectionLimits.MAX_SNAPSHOT_UTF16
                || maximumAfterUtf16 > EditorProjectionLimits.MAX_SNAPSHOT_UTF16) {
            throw new IllegalArgumentException("snapshot request exceeds bound");
        }
        ProjectionContext context = Objects.requireNonNull(metadata.current(), "projection context");
        CharSequence before = connection.getTextBeforeCursor(maximumBeforeUtf16, 0);
        CharSequence after = connection.getTextAfterCursor(maximumAfterUtf16, 0);
        if (before == null || after == null) {
            throw new IllegalStateException("editor did not expose surrounding text");
        }
        return new ProjectionSnapshot(
                connection, context, before.toString(), after.toString());
    }

    @Override
    public boolean beginBatchEdit() {
        return connection.beginBatchEdit();
    }

    @Override
    public boolean endBatchEdit() {
        return connection.endBatchEdit();
    }

    @Override
    public boolean setComposingText(String text) {
        return connection.setComposingText(text, 1);
    }

    @Override
    public boolean finishComposingText() {
        return connection.finishComposingText();
    }

    @Override
    public boolean deleteSurroundingTextInCodePoints(
            int beforeCodePoints,
            int afterCodePoints) {
        return connection.deleteSurroundingTextInCodePoints(beforeCodePoints, afterCodePoints);
    }
}
