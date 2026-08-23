package com.opentypeless.android.speech.delivery;

/** Narrow mutation/readback surface; the Android adapter is the only implementation using InputConnection. */
public interface ProjectionConnection {
    Object identity();

    ProjectionSnapshot snapshot(int maximumBeforeUtf16, int maximumAfterUtf16);

    boolean beginBatchEdit();

    boolean endBatchEdit();

    boolean setComposingText(String text);

    boolean finishComposingText();

    boolean deleteSurroundingTextInCodePoints(int beforeCodePoints, int afterCodePoints);
}
