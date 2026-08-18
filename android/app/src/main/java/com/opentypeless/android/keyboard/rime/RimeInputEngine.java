package com.opentypeless.android.keyboard.rime;

import com.opentypeless.android.keyboard.candidate.CandidatePage;
import java.util.Objects;

/**
 * Capability-free boundary between the keyboard domain and a future Rime runtime adapter.
 *
 * <p>Implementations are invoked on an engine worker, never the IME main thread. They return only
 * bounded values; an owning controller maps those values through the composition coordinator and
 * editor transaction authority. Implementations must not receive an editor connection.
 */
public interface RimeInputEngine extends AutoCloseable {
    String PRODUCER_ID = "rime";

    enum LearningMode {
        DISABLED,
        ENABLED
    }

    enum DeactivationReason {
        TARGET_FINISHED,
        ENGINE_SWITCHED,
        SENSITIVE_FIELD,
        VOICE_PREEMPTED,
        SERVICE_DESTROYED,
        FAILURE
    }

    enum KeyKind {
        PRINTABLE,
        BACKSPACE,
        ENTER,
        ESCAPE
    }

    enum FailureKind {
        CLOSED,
        INACTIVE,
        ALREADY_ACTIVE,
        STALE_EDITOR_GENERATION,
        STALE_COORDINATION_GENERATION,
        POLICY_DENIED,
        INVALID_OUTPUT,
        ENGINE_UNAVAILABLE,
        ENGINE_FAILURE
    }

    record Activation(
            long editorGeneration,
            long coordinationGeneration,
            long initialRevision,
            LearningMode learningMode) {
        public Activation {
            RimeEngineSnapshot.requirePositive(editorGeneration, "editorGeneration");
            RimeEngineSnapshot.requirePositive(coordinationGeneration, "coordinationGeneration");
            RimeEngineSnapshot.requirePositive(initialRevision, "initialRevision");
            Objects.requireNonNull(learningMode, "learningMode");
        }

        public Activation(
                long editorGeneration,
                long coordinationGeneration,
                LearningMode learningMode) {
            this(editorGeneration, coordinationGeneration, 1L, learningMode);
        }
    }

    record Deactivation(
            long editorGeneration,
            long coordinationGeneration,
            DeactivationReason reason) {
        public Deactivation {
            RimeEngineSnapshot.requirePositive(editorGeneration, "editorGeneration");
            RimeEngineSnapshot.requirePositive(coordinationGeneration, "coordinationGeneration");
            Objects.requireNonNull(reason, "reason");
        }
    }

    record Key(KeyKind kind, int codePoint) {
        public Key {
            Objects.requireNonNull(kind, "kind");
            if (kind == KeyKind.PRINTABLE) {
                if (!Character.isValidCodePoint(codePoint)
                        || (codePoint >= Character.MIN_SURROGATE
                                && codePoint <= Character.MAX_SURROGATE)
                        || Character.isISOControl(codePoint)
                        || RimeEngineSnapshot.isBidiControl(codePoint)) {
                    throw new IllegalArgumentException("printable key must be a Unicode scalar");
                }
            } else if (codePoint != 0) {
                throw new IllegalArgumentException("non-printable key must not carry text");
            }
        }

        public static Key printable(int codePoint) {
            return new Key(KeyKind.PRINTABLE, codePoint);
        }

        public static Key backspace() {
            return new Key(KeyKind.BACKSPACE, 0);
        }

        public static Key enter() {
            return new Key(KeyKind.ENTER, 0);
        }

        public static Key escape() {
            return new Key(KeyKind.ESCAPE, 0);
        }

        @Override
        public String toString() {
            return "RimeKey{kind=" + kind + ", codePoint=<redacted>}";
        }
    }

    record ProcessRequest(long editorGeneration, long coordinationGeneration, Key key) {
        public ProcessRequest {
            RimeEngineSnapshot.requirePositive(editorGeneration, "editorGeneration");
            RimeEngineSnapshot.requirePositive(coordinationGeneration, "coordinationGeneration");
            Objects.requireNonNull(key, "key");
        }
    }

    record CandidateSelectionRequest(
            long editorGeneration, CandidatePage.Selection selection) {
        public CandidateSelectionRequest {
            RimeEngineSnapshot.requirePositive(editorGeneration, "editorGeneration");
            Objects.requireNonNull(selection, "selection");
            if (!PRODUCER_ID.equals(selection.producerId())) {
                throw new IllegalArgumentException("candidate selection is not owned by Rime");
            }
        }
    }

    record CandidatePageRequest(
            long editorGeneration, CandidatePage.PageRequest request) {
        public CandidatePageRequest {
            RimeEngineSnapshot.requirePositive(editorGeneration, "editorGeneration");
            Objects.requireNonNull(request, "request");
            if (!PRODUCER_ID.equals(request.producerId())) {
                throw new IllegalArgumentException("candidate page request is not owned by Rime");
            }
        }
    }

    record Commit(
            long editorGeneration,
            long coordinationGeneration,
            long revision,
            String text) {
        public Commit {
            RimeEngineSnapshot.requirePositive(editorGeneration, "editorGeneration");
            RimeEngineSnapshot.requirePositive(coordinationGeneration, "coordinationGeneration");
            RimeEngineSnapshot.requirePositive(revision, "revision");
            text = RimeEngineSnapshot.requireBoundedText(text, false, "commit text");
        }

        @Override
        public String toString() {
            return "RimeCommit{editorGeneration=" + editorGeneration
                    + ", coordinationGeneration=" + coordinationGeneration
                    + ", revision=" + revision + ", text=<redacted>}";
        }
    }

    sealed interface LifecycleResult permits LifecycleApplied, Rejected {}

    record LifecycleApplied(RimeEngineSnapshot snapshot) implements LifecycleResult {
        public LifecycleApplied {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    sealed interface ProcessResult permits StateReady, CommitReady, Rejected {}

    record StateReady(RimeEngineSnapshot snapshot) implements ProcessResult {
        public StateReady {
            Objects.requireNonNull(snapshot, "snapshot");
            if (snapshot.phase() != RimeEngineSnapshot.Phase.ACTIVE) {
                throw new IllegalArgumentException("process state must be active");
            }
        }
    }

    record CommitReady(Commit commit, RimeEngineSnapshot snapshot) implements ProcessResult {
        public CommitReady {
            Objects.requireNonNull(commit, "commit");
            Objects.requireNonNull(snapshot, "snapshot");
            if (snapshot.phase() != RimeEngineSnapshot.Phase.ACTIVE
                    || snapshot.editorGeneration() != commit.editorGeneration()
                    || snapshot.coordinationGeneration() != commit.coordinationGeneration()
                    || snapshot.revision() != commit.revision()) {
                throw new IllegalArgumentException("commit and snapshot identity must match");
            }
        }
    }

    sealed interface SnapshotResult permits SnapshotReady, Rejected {}

    record SnapshotReady(RimeEngineSnapshot snapshot) implements SnapshotResult {
        public SnapshotReady {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    record Rejected(FailureKind failure)
            implements LifecycleResult, ProcessResult, SnapshotResult {
        public Rejected {
            Objects.requireNonNull(failure, "failure");
        }
    }

    LifecycleResult activate(Activation request);

    LifecycleResult deactivate(Deactivation request);

    ProcessResult process(ProcessRequest request);

    ProcessResult selectCandidate(CandidateSelectionRequest request);

    ProcessResult requestCandidatePage(CandidatePageRequest request);

    SnapshotResult snapshot();

    @Override
    void close();
}
