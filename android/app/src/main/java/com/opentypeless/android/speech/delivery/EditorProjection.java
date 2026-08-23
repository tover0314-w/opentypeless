package com.opentypeless.android.speech.delivery;

import java.util.Objects;
import java.util.Optional;

/**
 * The only v2 stateful layer allowed to mutate an ordinary dictation editor.
 *
 * <p>It never handles selected text. Every operation validates target identity before mutation and
 * reads the editor back afterwards. An unacknowledged or unverifiable mutation makes the complete
 * desired draft recoverable and permanently stops automatic writes for this projection.
 */
public final class EditorProjection {
    private final ProjectionConnection connection;
    private final ProjectionTarget target;
    private final ProjectionMode mode;
    private ProjectionState state = ProjectionState.ACTIVE;
    private String committedPrefix = "";
    private String composingTail = "";
    private String projectedFullText = "";
    private String recoverableText;
    private boolean ownsComposition;
    private boolean mutationUncertain;
    private SessionUndoLedger undoLedger;

    private EditorProjection(
            ProjectionConnection connection,
            ProjectionTarget target,
            ProjectionMode mode) {
        this.connection = connection;
        this.target = target;
        this.mode = mode;
    }

    public static EditorProjection capture(
            ProjectionConnection connection,
            ProjectionMode mode) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(mode, "mode");
        ProjectionSnapshot snapshot = connection.snapshot(
                EditorProjectionLimits.CONTEXT_CODE_POINTS,
                EditorProjectionLimits.CONTEXT_CODE_POINTS);
        if (snapshot.connectionIdentity() != connection.identity()) {
            throw new IllegalArgumentException("snapshot identity does not match connection");
        }
        return new EditorProjection(connection, ProjectionTarget.capture(snapshot), mode);
    }

    public synchronized ProjectionResult project(ProjectionDocument document) {
        Objects.requireNonNull(document, "document");
        if (state != ProjectionState.ACTIVE) {
            return result(ProjectionOutcome.REJECTED_STATE, "projection is no longer active");
        }
        if (mode == ProjectionMode.SHORT_DICTATION && !document.sealedPrefix().isEmpty()) {
            return result(
                    ProjectionOutcome.REJECTED_STATE,
                    "short dictation cannot expose a committed prefix");
        }
        if (!document.sealedPrefix().startsWith(committedPrefix)) {
            return makeRecoverable(
                    document.fullText(),
                    false,
                    ProjectionOutcome.RECOVERY_REQUIRED,
                    "sealed prefix is not monotonic");
        }
        if (document.fullText().equals(projectedFullText)
                && document.sealedPrefix().equals(committedPrefix)
                && document.composingTail().equals(composingTail)) {
            return result(ProjectionOutcome.UNCHANGED, "document is already projected");
        }
        ProjectionTarget.TargetValidation before = validate(projectedFullText);
        if (!before.valid()) {
            return makeRecoverable(
                    document.fullText(),
                    false,
                    ProjectionOutcome.REJECTED_TARGET,
                    before.reason());
        }
        return mode == ProjectionMode.SHORT_DICTATION
                ? projectShort(document)
                : projectLong(document);
    }

    /** Projects the final document and acknowledges it as one logical editor insertion. */
    public synchronized ProjectionResult finish(ProjectionDocument finalDocument) {
        ProjectionResult projected = project(finalDocument);
        if (projected.state() != ProjectionState.ACTIVE) return projected;
        if (projected.outcome() != ProjectionOutcome.APPLIED
                && projected.outcome() != ProjectionOutcome.UNCHANGED) {
            return projected;
        }
        if (ownsComposition) {
            MutationCheck finish = finishComposition(projectedFullText);
            if (!finish.acknowledged || !finish.verified) {
                return makeRecoverable(
                        projectedFullText,
                        finish.uncertain,
                        finish.uncertain
                                ? ProjectionOutcome.MUTATION_UNCERTAIN
                                : ProjectionOutcome.RECOVERY_REQUIRED,
                        "editor did not acknowledge final composition");
            }
            ownsComposition = false;
        } else {
            ProjectionTarget.TargetValidation validation = validate(projectedFullText);
            if (!validation.valid()) {
                return makeRecoverable(
                        projectedFullText,
                        false,
                        ProjectionOutcome.REJECTED_TARGET,
                        validation.reason());
            }
        }
        state = ProjectionState.COMMITTED;
        recoverableText = null;
        mutationUncertain = false;
        if (!projectedFullText.isEmpty()) {
            undoLedger = new SessionUndoLedger(target, projectedFullText);
        }
        return result(ProjectionOutcome.COMMITTED, "editor acknowledged final voice document");
    }

    /** Lifecycle/cursor detach: retain editor text if safely owned, otherwise expose recovery. */
    public synchronized ProjectionResult freeze() {
        if (state != ProjectionState.ACTIVE) {
            return result(ProjectionOutcome.REJECTED_STATE, "projection is no longer active");
        }
        ProjectionTarget.TargetValidation validation = validate(projectedFullText);
        if (!validation.valid()) {
            return makeRecoverable(
                    projectedFullText,
                    false,
                    ProjectionOutcome.REJECTED_TARGET,
                    validation.reason());
        }
        if (ownsComposition) {
            MutationCheck finish = finishComposition(projectedFullText);
            if (!finish.acknowledged || !finish.verified) {
                return makeRecoverable(
                        projectedFullText,
                        finish.uncertain,
                        finish.uncertain
                                ? ProjectionOutcome.MUTATION_UNCERTAIN
                                : ProjectionOutcome.RECOVERY_REQUIRED,
                        "editor did not acknowledge frozen composition");
            }
            ownsComposition = false;
        }
        state = ProjectionState.FROZEN;
        return result(ProjectionOutcome.FROZEN, "voice projection frozen in original editor");
    }

    /** Explicit confirmed discard. It never deletes when the exact target/suffix is unproven. */
    public synchronized ProjectionResult discardConfirmed() {
        if (state == ProjectionState.DISCARDED) {
            return result(ProjectionOutcome.DISCARDED, "projection was already discarded");
        }
        if (state == ProjectionState.RECOVERABLE) {
            clearInternalAfterDiscard();
            return result(ProjectionOutcome.DISCARDED, "recoverable draft discarded");
        }
        ProjectionTarget.TargetValidation validation = validate(projectedFullText);
        if (!validation.valid()) {
            clearInternalAfterDiscard();
            return result(
                    ProjectionOutcome.DISCARD_EDITOR_RETAINED,
                    "target changed; existing editor text was not touched");
        }
        if (projectedFullText.isEmpty()) {
            clearInternalAfterDiscard();
            return result(ProjectionOutcome.DISCARDED, "empty projection discarded");
        }
        boolean acknowledged = false;
        boolean began = false;
        try {
            began = connection.beginBatchEdit();
            if (ownsComposition) {
                connection.finishComposingText();
                ownsComposition = false;
            }
            acknowledged = connection.deleteSurroundingTextInCodePoints(
                    projectedFullText.codePointCount(0, projectedFullText.length()), 0);
        } catch (RuntimeException ignored) {
            // Readback below is authoritative.
        } finally {
            if (began) {
                try {
                    connection.endBatchEdit();
                } catch (RuntimeException ignored) {
                    acknowledged = false;
                }
            }
        }
        boolean removed = validate("").valid();
        clearInternalAfterDiscard();
        return result(
                removed && acknowledged
                        ? ProjectionOutcome.DISCARDED
                        : ProjectionOutcome.DISCARD_EDITOR_RETAINED,
                removed
                        ? "voice projection removed"
                        : "editor did not prove removal; no second destructive attempt was made");
    }

    public synchronized ProjectionState state() {
        return state;
    }

    public synchronized String projectedFullText() {
        return projectedFullText;
    }

    public synchronized boolean ownsComposition() {
        return ownsComposition;
    }

    /** True only for cursor moves explained by this projection's current owned suffix. */
    public synchronized boolean acceptsSelection(
            int selectionStart,
            int selectionEnd,
            int candidatesStart,
            int candidatesEnd) {
        if (state != ProjectionState.ACTIVE || selectionStart != selectionEnd) return false;
        long expected = (long) target.initialSelection() + projectedFullText.length();
        if (expected > Integer.MAX_VALUE || selectionEnd != (int) expected) return false;
        boolean rangeOmitted = candidatesStart < 0 && candidatesEnd < 0;
        boolean ownedRange = candidatesStart >= target.initialSelection()
                && candidatesStart <= selectionEnd
                && candidatesEnd == selectionEnd;
        return rangeOmitted || ownedRange;
    }

    /** Performs the same bounded target readback used before every automatic mutation. */
    public synchronized boolean targetStillValid() {
        return state == ProjectionState.ACTIVE && validate(projectedFullText).valid();
    }

    public synchronized Optional<String> recoverableText() {
        return Optional.ofNullable(recoverableText);
    }

    public synchronized Optional<SessionUndoLedger> undoLedger() {
        return Optional.ofNullable(undoLedger);
    }

    private ProjectionResult projectShort(ProjectionDocument document) {
        MutationCheck mutation = setComposition(document.fullText(), document.fullText());
        if (!mutation.acknowledged || !mutation.verified) {
            return makeRecoverable(
                    document.fullText(),
                    mutation.uncertain,
                    mutation.uncertain
                            ? ProjectionOutcome.MUTATION_UNCERTAIN
                            : ProjectionOutcome.RECOVERY_REQUIRED,
                    "editor rejected or failed to verify composing text");
        }
        committedPrefix = "";
        composingTail = document.composingTail();
        projectedFullText = document.fullText();
        ownsComposition = !composingTail.isEmpty();
        return result(ProjectionOutcome.APPLIED, "short draft projected as composition");
    }

    private ProjectionResult projectLong(ProjectionDocument document) {
        boolean began = false;
        try {
            began = connection.beginBatchEdit();
            if (!document.sealedPrefix().equals(committedPrefix)) {
                String sealedDelta =
                        document.sealedPrefix().substring(committedPrefix.length());
                String expectedPrefix = committedPrefix + sealedDelta;
                MutationCheck prefix = setComposition(sealedDelta, expectedPrefix);
                if (!prefix.acknowledged || !prefix.verified) {
                    return makeRecoverable(
                            document.fullText(),
                            prefix.uncertain,
                            prefix.uncertain
                                    ? ProjectionOutcome.MUTATION_UNCERTAIN
                                    : ProjectionOutcome.RECOVERY_REQUIRED,
                            "editor rejected sealed-prefix projection");
                }
                ownsComposition = !sealedDelta.isEmpty();
                MutationCheck sealed = finishComposition(expectedPrefix);
                if (!sealed.acknowledged || !sealed.verified) {
                    return makeRecoverable(
                            document.fullText(),
                            sealed.uncertain,
                            sealed.uncertain
                                    ? ProjectionOutcome.MUTATION_UNCERTAIN
                                    : ProjectionOutcome.RECOVERY_REQUIRED,
                            "editor rejected sealed-prefix commit");
                }
                committedPrefix = document.sealedPrefix();
                composingTail = "";
                projectedFullText = committedPrefix;
                ownsComposition = false;
            }
            if (!document.composingTail().equals(composingTail)) {
                MutationCheck tail = setComposition(
                        document.composingTail(), document.fullText());
                if (!tail.acknowledged || !tail.verified) {
                    return makeRecoverable(
                            document.fullText(),
                            tail.uncertain,
                            tail.uncertain
                                    ? ProjectionOutcome.MUTATION_UNCERTAIN
                                    : ProjectionOutcome.RECOVERY_REQUIRED,
                            "editor rejected composing tail");
                }
            }
            committedPrefix = document.sealedPrefix();
            composingTail = document.composingTail();
            projectedFullText = document.fullText();
            ownsComposition = !composingTail.isEmpty();
            return result(ProjectionOutcome.APPLIED, "long draft prefix/tail projected");
        } catch (RuntimeException failure) {
            return makeRecoverable(
                    document.fullText(),
                    true,
                    ProjectionOutcome.MUTATION_UNCERTAIN,
                    "editor batch mutation failed");
        } finally {
            if (began) {
                try {
                    connection.endBatchEdit();
                } catch (RuntimeException ignored) {
                    // Batch edit is only a redraw/notification hint. Every semantic mutation above
                    // has already been read back; an endBatchEdit failure cannot invalidate that
                    // proof or justify a second write.
                }
            }
        }
    }

    private MutationCheck setComposition(String text, String expectedFull) {
        boolean acknowledged = false;
        boolean threw = false;
        try {
            acknowledged = connection.setComposingText(text);
        } catch (RuntimeException failure) {
            threw = true;
        }
        ProjectionTarget.TargetValidation expected = validate(expectedFull);
        if (expected.valid()) {
            return new MutationCheck(acknowledged, true, threw || !acknowledged);
        }
        ProjectionTarget.TargetValidation unchanged = validate(projectedFullText);
        return new MutationCheck(
                acknowledged,
                false,
                !unchanged.valid());
    }

    private MutationCheck finishComposition(String expectedFull) {
        boolean acknowledged = false;
        boolean threw = false;
        try {
            acknowledged = connection.finishComposingText();
        } catch (RuntimeException failure) {
            threw = true;
        }
        boolean verified = validate(expectedFull).valid();
        return new MutationCheck(
                acknowledged,
                verified,
                threw || !acknowledged || !verified);
    }

    private ProjectionTarget.TargetValidation validate(String insertedText) {
        try {
            ProjectionSnapshot snapshot = connection.snapshot(
                    target.requiredBeforeUtf16(insertedText), target.requiredAfterUtf16());
            return target.validate(snapshot, insertedText);
        } catch (RuntimeException failure) {
            return ProjectionTarget.TargetValidation.invalid("editor readback failed");
        }
    }

    private ProjectionResult makeRecoverable(
            String desired,
            boolean uncertain,
            ProjectionOutcome outcome,
            String detail) {
        state = ProjectionState.RECOVERABLE;
        recoverableText = Objects.requireNonNullElse(desired, "");
        mutationUncertain = uncertain;
        return result(outcome, detail);
    }

    private void clearInternalAfterDiscard() {
        state = ProjectionState.DISCARDED;
        committedPrefix = "";
        composingTail = "";
        projectedFullText = "";
        recoverableText = null;
        mutationUncertain = false;
        ownsComposition = false;
        undoLedger = null;
    }

    private ProjectionResult result(ProjectionOutcome outcome, String detail) {
        return new ProjectionResult(
                state,
                outcome,
                Optional.ofNullable(recoverableText),
                mutationUncertain,
                detail);
    }

    private record MutationCheck(boolean acknowledged, boolean verified, boolean uncertain) {}
}
