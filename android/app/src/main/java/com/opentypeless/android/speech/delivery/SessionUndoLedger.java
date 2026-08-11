package com.opentypeless.android.speech.delivery;

import java.util.Objects;

/** One logical undo for the complete voice insertion; no selected text is handled here. */
public final class SessionUndoLedger {
    private final ProjectionTarget target;
    private final String insertedText;
    private boolean consumed;

    SessionUndoLedger(ProjectionTarget target, String insertedText) {
        this.target = Objects.requireNonNull(target, "target");
        this.insertedText = Objects.requireNonNull(insertedText, "insertedText");
    }

    public synchronized UndoResult undo(ProjectionConnection connection) {
        Objects.requireNonNull(connection, "connection");
        if (consumed) {
            return new UndoResult(UndoDisposition.IGNORED_CONSUMED, "undo was already consumed");
        }
        ProjectionSnapshot before;
        try {
            before = connection.snapshot(
                    target.requiredBeforeUtf16(insertedText), target.requiredAfterUtf16());
        } catch (RuntimeException failure) {
            return new UndoResult(UndoDisposition.REJECTED_TARGET, "unable to read editor target");
        }
        ProjectionTarget.TargetValidation validation = target.validate(before, insertedText);
        if (!validation.valid()) {
            return new UndoResult(UndoDisposition.REJECTED_TARGET, validation.reason());
        }
        boolean acknowledged = false;
        boolean began = false;
        try {
            began = connection.beginBatchEdit();
            acknowledged = connection.deleteSurroundingTextInCodePoints(
                    insertedText.codePointCount(0, insertedText.length()), 0);
        } catch (RuntimeException ignored) {
            // Readback below decides whether the deletion reached the editor.
        } finally {
            if (began) {
                try {
                    connection.endBatchEdit();
                } catch (RuntimeException ignored) {
                    acknowledged = false;
                }
            }
        }
        ProjectionTarget.TargetValidation after = readValidation(connection, target, "");
        if (after.valid()) {
            consumed = true;
            return new UndoResult(
                    acknowledged ? UndoDisposition.APPLIED : UndoDisposition.APPLIED_UNCERTAIN,
                    acknowledged ? "voice insertion removed" : "editor removed text without acknowledgement");
        }
        if (!acknowledged) {
            ProjectionTarget.TargetValidation unchanged = readValidation(connection, target, insertedText);
            if (unchanged.valid()) {
                return new UndoResult(UndoDisposition.REJECTED_MUTATION, "editor rejected undo");
            }
        }
        consumed = true;
        return new UndoResult(
                UndoDisposition.REJECTED_MUTATION,
                "undo result is uncertain; no second destructive attempt is allowed");
    }

    public synchronized boolean consumed() {
        return consumed;
    }

    private static ProjectionTarget.TargetValidation readValidation(
            ProjectionConnection connection,
            ProjectionTarget target,
            String inserted) {
        try {
            return target.validate(
                    connection.snapshot(
                            target.requiredBeforeUtf16(inserted), target.requiredAfterUtf16()),
                    inserted);
        } catch (RuntimeException failure) {
            return ProjectionTarget.TargetValidation.invalid("editor readback failed");
        }
    }
}
