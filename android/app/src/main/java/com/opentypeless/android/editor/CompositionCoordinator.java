package com.opentypeless.android.editor;

import java.util.Objects;

/**
 * Process-local, text-free coordinator for the single active composition state.
 *
 * <p>Every mutation is an exact compare-and-set against an owner-issued {@link Observation}.
 * That opaque identity token protects even {@link CompositionState.Idle} from ABA: an async
 * request captured before an acquire/cancel cycle cannot acquire a later generation. Conflict
 * policy remains external; this class only supplies closed, fail-closed transition mechanics.
 */
public final class CompositionCoordinator {
    /** Closed acquisition vocabulary; requests contain neither editor text nor capabilities. */
    public sealed interface Acquisition permits
            Acquisition.Latin,
            Acquisition.Rime,
            Acquisition.Voice,
            Acquisition.Action {

        record Latin(long revision) implements Acquisition {
            public Latin {
                requirePositiveRevision(revision);
            }
        }

        record Rime(long revision) implements Acquisition {
            public Rime {
                requirePositiveRevision(revision);
            }
        }

        record Voice() implements Acquisition {}

        record Action() implements Acquisition {}

        private static void requirePositiveRevision(long revision) {
            if (revision <= 0L) {
                throw new IllegalArgumentException("revision must be positive");
            }
        }
    }

    /** Stable, content-free classification of one requested transition. */
    public enum Disposition {
        APPLIED,
        IGNORED_DUPLICATE,
        IGNORED_STALE,
        REJECTED_OBSERVATION,
        REJECTED_STATE,
        REJECTED_OWNER,
        REJECTED_REVISION,
        REJECTED_CONFLICT,
        REJECTED_RELEASE_DIRECTIVE,
        REJECTED_PREEMPTION_PENDING,
        REJECTED_PREEMPT_TICKET,
        GENERATION_EXHAUSTED,
        VERSION_EXHAUSTED,
        RELEASE_PROVEN_UNCHANGED,
        RELEASE_UNCERTAIN
    }

    /** Whether an observation is stable or a preemption release is being proven. */
    public enum ObservationPhase {
        STABLE,
        PREEMPT_PENDING
    }

    /** Explicit action selected by CMP-003 policy for the composition being released. */
    public enum ReleaseDirective {
        COMMIT_CURRENT,
        CANCEL_CURRENT
    }

    /** Proof supplied after the external release step of a two-phase preemption. */
    public enum ReleaseResolution {
        PROVEN_RELEASED,
        PROVEN_UNCHANGED,
        UNCERTAIN
    }

    /**
     * Opaque coordinator-issued compare-and-set token.
     *
     * <p>Equality intentionally remains object identity. State/version values are diagnostic and
     * must never be reconstructed into authority. A pending observation keeps the old logical
     * state visible for diagnosis but is not a writable active-owner authorization.
     */
    public static final class Observation {
        private final CompositionCoordinator owner;
        private final CompositionState state;
        private final long version;
        private final ObservationPhase phase;

        private Observation(
                CompositionCoordinator owner,
                CompositionState state,
                long version,
                ObservationPhase phase) {
            this.owner = owner;
            this.state = state;
            this.version = version;
            this.phase = phase;
        }

        public CompositionState state() {
            return state;
        }

        public long version() {
            return version;
        }

        public ObservationPhase phase() {
            return phase;
        }

        @Override
        public String toString() {
            return "CompositionObservation{state="
                    + state.getClass().getSimpleName()
                    + ", version="
                    + version
                    + ", phase="
                    + phase
                    + '}';
        }
    }

    /** Immutable, coordinator-issued before/after envelope. */
    public static final class Transition {
        private final Observation before;
        private final Observation after;
        private final Disposition disposition;

        private Transition(
                Observation before,
                Observation after,
                Disposition disposition) {
            this.before = before;
            this.after = after;
            this.disposition = disposition;
        }

        public Observation before() {
            return before;
        }

        public Observation after() {
            return after;
        }

        public Disposition disposition() {
            return disposition;
        }

        @Override
        public String toString() {
            return "CompositionTransition{before="
                    + before
                    + ", after="
                    + after
                    + ", disposition="
                    + disposition
                    + '}';
        }
    }

    /** Closed result of starting the two-phase preemption handshake. */
    public sealed interface PreemptStart permits PreemptPrepared, PreemptRejected {}

    /** Successful preflight. The directive must now be executed and proved by the caller. */
    public static final class PreemptPrepared implements PreemptStart {
        private final PreemptTicket ticket;
        private final ReleaseDirective directive;
        private final Observation observation;

        private PreemptPrepared(
                PreemptTicket ticket,
                ReleaseDirective directive,
                Observation observation) {
            this.ticket = ticket;
            this.directive = directive;
            this.observation = observation;
        }

        public PreemptTicket ticket() {
            return ticket;
        }

        public ReleaseDirective directive() {
            return directive;
        }

        public Observation observation() {
            return observation;
        }

        @Override
        public String toString() {
            return "PreemptPrepared{directive="
                    + directive
                    + ", observation="
                    + observation
                    + ", ticket=<redacted>}";
        }
    }

    /** Preemption preflight rejection; no external release may be attempted. */
    public static final class PreemptRejected implements PreemptStart {
        private final Transition transition;

        private PreemptRejected(Transition transition) {
            this.transition = transition;
        }

        public Transition transition() {
            return transition;
        }

        @Override
        public String toString() {
            return "PreemptRejected{transition=" + transition + '}';
        }
    }

    /** Opaque, coordinator-bound, one-pending-handshake ticket. */
    public static final class PreemptTicket {
        private final CompositionCoordinator owner;

        private PreemptTicket(CompositionCoordinator owner) {
            this.owner = owner;
        }

        @Override
        public String toString() {
            return "PreemptTicket{<redacted>}";
        }
    }

    private static final class PendingPreempt {
        private final PreemptTicket ticket;
        private final CompositionState oldState;
        private final CompositionState nextState;
        private final long reservedGeneration;

        private PendingPreempt(
                PreemptTicket ticket,
                CompositionState oldState,
                CompositionState nextState,
                long reservedGeneration) {
            this.ticket = ticket;
            this.oldState = oldState;
            this.nextState = nextState;
            this.reservedGeneration = reservedGeneration;
        }

        @Override
        public String toString() {
            return "PendingPreempt{<redacted>}";
        }
    }

    private Observation current;
    private long lastIssuedGeneration;
    private PendingPreempt pendingPreempt;

    public CompositionCoordinator() {
        this(0L, 0L);
    }

    /** Package-private seeds for deterministic generation/version boundary tests. */
    CompositionCoordinator(long generationSeed, long versionSeed) {
        if (generationSeed < 0L) {
            throw new IllegalArgumentException("generationSeed must not be negative");
        }
        if (versionSeed < 0L) {
            throw new IllegalArgumentException("versionSeed must not be negative");
        }
        lastIssuedGeneration = generationSeed;
        current = new Observation(
                this,
                new CompositionState.Idle(),
                versionSeed,
                ObservationPhase.STABLE);
    }

    /** Returns the exact immutable token at one synchronization point. */
    public synchronized Observation observe() {
        return current;
    }

    /** Acquires a new generation only from the exact observed Idle state. */
    public synchronized Transition acquire(
            Observation expected, Acquisition acquisition) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(acquisition, "acquisition");
        Transition mismatch = rejectObservation(expected);
        if (mismatch != null) return mismatch;
        if (!(current.state instanceof CompositionState.Idle)) {
            return unchanged(Disposition.REJECTED_CONFLICT);
        }
        return acquireNewGeneration(acquisition);
    }

    /** Advances a Latin or Rime revision within the exact observation. */
    public synchronized Transition update(Observation expected, long nextRevision) {
        Objects.requireNonNull(expected, "expected");
        Transition mismatch = rejectObservation(expected);
        if (mismatch != null) return mismatch;
        CompositionState before = current.state;
        if (nextRevision <= 0L) {
            return unchanged(Disposition.REJECTED_REVISION);
        }
        if (before instanceof CompositionState.LatinComposing latin) {
            if (nextRevision == latin.revision()) {
                return unchanged(Disposition.IGNORED_DUPLICATE);
            }
            if (nextRevision < latin.revision()) {
                return unchanged(Disposition.IGNORED_STALE);
            }
            return applyStable(new CompositionState.LatinComposing(
                    latin.coordinationGeneration(), nextRevision));
        }
        if (before instanceof CompositionState.RimeComposing rime) {
            if (nextRevision == rime.revision()) {
                return unchanged(Disposition.IGNORED_DUPLICATE);
            }
            if (nextRevision < rime.revision()) {
                return unchanged(Disposition.IGNORED_STALE);
            }
            return applyStable(new CompositionState.RimeComposing(
                    rime.coordinationGeneration(), nextRevision));
        }
        return rejectedForDifferentOwner();
    }

    /** Marks the exact voice preparation generation ready to listen. */
    public synchronized Transition voiceReady(Observation expected) {
        Objects.requireNonNull(expected, "expected");
        Transition mismatch = rejectObservation(expected);
        if (mismatch != null) return mismatch;
        CompositionState before = current.state;
        if (before instanceof CompositionState.VoicePreparing preparing) {
            return applyStable(new CompositionState.VoiceListening(
                    preparing.coordinationGeneration()));
        }
        if (before instanceof CompositionState.VoiceListening) {
            return unchanged(Disposition.IGNORED_DUPLICATE);
        }
        if (before instanceof CompositionState.VoicePartial
                || before instanceof CompositionState.VoiceFinalizing) {
            return unchanged(Disposition.IGNORED_STALE);
        }
        return rejectedForOwner(CompositionOwner.VOICE);
    }

    /** Applies a strictly newer voice partial revision to the exact voice observation. */
    public synchronized Transition voicePartial(Observation expected, long revision) {
        Objects.requireNonNull(expected, "expected");
        Transition mismatch = rejectObservation(expected);
        if (mismatch != null) return mismatch;
        CompositionState before = current.state;
        if (revision <= 0L) {
            return unchanged(Disposition.REJECTED_REVISION);
        }
        if (before instanceof CompositionState.VoiceListening listening) {
            return applyStable(new CompositionState.VoicePartial(
                    listening.coordinationGeneration(), revision));
        }
        if (before instanceof CompositionState.VoicePartial partial) {
            if (revision == partial.revision()) {
                return unchanged(Disposition.IGNORED_DUPLICATE);
            }
            if (revision < partial.revision()) {
                return unchanged(Disposition.IGNORED_STALE);
            }
            return applyStable(new CompositionState.VoicePartial(
                    partial.coordinationGeneration(), revision));
        }
        if (before instanceof CompositionState.VoiceFinalizing) {
            return unchanged(Disposition.IGNORED_STALE);
        }
        if (before instanceof CompositionState.VoicePreparing) {
            return unchanged(Disposition.REJECTED_STATE);
        }
        return rejectedForOwner(CompositionOwner.VOICE);
    }

    /** Freezes the current voice revision while final output is being prepared. */
    public synchronized Transition beginVoiceFinalizing(Observation expected) {
        Objects.requireNonNull(expected, "expected");
        Transition mismatch = rejectObservation(expected);
        if (mismatch != null) return mismatch;
        CompositionState before = current.state;
        if (before instanceof CompositionState.VoiceListening listening) {
            return applyStable(new CompositionState.VoiceFinalizing(
                    listening.coordinationGeneration(), 0L));
        }
        if (before instanceof CompositionState.VoicePartial partial) {
            return applyStable(new CompositionState.VoiceFinalizing(
                    partial.coordinationGeneration(), partial.revision()));
        }
        if (before instanceof CompositionState.VoiceFinalizing) {
            return unchanged(Disposition.IGNORED_DUPLICATE);
        }
        if (before instanceof CompositionState.VoicePreparing) {
            return unchanged(Disposition.REJECTED_STATE);
        }
        return rejectedForOwner(CompositionOwner.VOICE);
    }

    /** Promotes an exact running action to the writable action-preview state. */
    public synchronized Transition showActionPreview(Observation expected) {
        Objects.requireNonNull(expected, "expected");
        Transition mismatch = rejectObservation(expected);
        if (mismatch != null) return mismatch;
        CompositionState before = current.state;
        if (before instanceof CompositionState.ActionRunning running) {
            return applyStable(new CompositionState.ActionPreview(
                    running.coordinationGeneration()));
        }
        if (before instanceof CompositionState.ActionPreview) {
            return unchanged(Disposition.IGNORED_DUPLICATE);
        }
        return rejectedForOwner(CompositionOwner.ACTION_PREVIEW);
    }

    /**
     * Confirms that the exact Latin/Rime revision was committed externally and releases it.
     */
    public synchronized Transition commit(
            Observation expected, long expectedRevision) {
        Objects.requireNonNull(expected, "expected");
        Transition mismatch = rejectObservation(expected);
        if (mismatch != null) return mismatch;
        CompositionState before = current.state;
        if (expectedRevision <= 0L) {
            return unchanged(Disposition.REJECTED_REVISION);
        }
        long currentRevision;
        if (before instanceof CompositionState.LatinComposing latin) {
            currentRevision = latin.revision();
        } else if (before instanceof CompositionState.RimeComposing rime) {
            currentRevision = rime.revision();
        } else {
            return rejectedForDifferentOwner();
        }
        if (expectedRevision != currentRevision) {
            return unchanged(Disposition.REJECTED_REVISION);
        }
        return applyStable(new CompositionState.Idle());
    }

    /** Confirms completion of an exact voice-finalizing or action generation. */
    public synchronized Transition complete(Observation expected) {
        Objects.requireNonNull(expected, "expected");
        Transition mismatch = rejectObservation(expected);
        if (mismatch != null) return mismatch;
        CompositionState before = current.state;
        if (before instanceof CompositionState.VoiceFinalizing
                || before instanceof CompositionState.ActionRunning
                || before instanceof CompositionState.ActionPreview) {
            return applyStable(new CompositionState.Idle());
        }
        if (before instanceof CompositionState.LatinComposing
                || before instanceof CompositionState.RimeComposing) {
            return unchanged(Disposition.REJECTED_OWNER);
        }
        return unchanged(Disposition.REJECTED_STATE);
    }

    /** Confirms cancellation of any exact active generation; exact Idle is idempotent. */
    public synchronized Transition cancel(Observation expected) {
        Objects.requireNonNull(expected, "expected");
        Transition mismatch = rejectObservation(expected);
        if (mismatch != null) return mismatch;
        if (current.state instanceof CompositionState.Idle) {
            return unchanged(Disposition.IGNORED_DUPLICATE);
        }
        return applyStable(new CompositionState.Idle());
    }

    /**
     * Starts a two-phase preemption without releasing or replacing the current owner.
     *
     * <p>CMP-003 selects the directive. This method validates its mechanism-level safety,
     * reserves capacity, and enters a fail-closed pending observation. The caller must then
     * perform the external release and report proof through {@link #finishPreempt}. Physical
     * release and logical acquisition are deliberately not described as one atomic operation.
     */
    public synchronized PreemptStart beginPreempt(
            Observation expected,
            ReleaseDirective directive,
            Acquisition acquisition) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(directive, "directive");
        Objects.requireNonNull(acquisition, "acquisition");
        Transition mismatch = rejectObservation(expected);
        if (mismatch != null) return new PreemptRejected(mismatch);
        CompositionState oldState = current.state;
        if (oldState instanceof CompositionState.Idle) {
            return rejectedPreempt(Disposition.REJECTED_STATE);
        }
        if (!releaseAllowed(oldState, directive)) {
            return rejectedPreempt(Disposition.REJECTED_RELEASE_DIRECTIVE);
        }
        if (lastIssuedGeneration == Long.MAX_VALUE) {
            return rejectedPreempt(Disposition.GENERATION_EXHAUSTED);
        }
        if (current.version > Long.MAX_VALUE - 2L) {
            return rejectedPreempt(Disposition.VERSION_EXHAUSTED);
        }

        long reservedGeneration = lastIssuedGeneration + 1L;
        CompositionState nextState = stateFor(acquisition, reservedGeneration);
        PreemptTicket ticket = new PreemptTicket(this);
        Observation pendingObservation = new Observation(
                this,
                oldState,
                current.version + 1L,
                ObservationPhase.PREEMPT_PENDING);
        pendingPreempt = new PendingPreempt(
                ticket, oldState, nextState, reservedGeneration);
        current = pendingObservation;
        return new PreemptPrepared(ticket, directive, pendingObservation);
    }

    /**
     * Resolves the exact pending preemption from proof about the external release.
     *
     * <p>{@link ReleaseResolution#UNCERTAIN} intentionally keeps the same ticket and pending
     * observation live, so every normal transition remains fail closed until the caller later
     * supplies exact proof with that same ticket.
     */
    public synchronized Transition finishPreempt(
            PreemptTicket ticket, ReleaseResolution resolution) {
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(resolution, "resolution");
        if (ticket.owner != this
                || pendingPreempt == null
                || pendingPreempt.ticket != ticket) {
            return unchanged(Disposition.REJECTED_PREEMPT_TICKET);
        }
        if (resolution == ReleaseResolution.UNCERTAIN) {
            return unchanged(Disposition.RELEASE_UNCERTAIN);
        }

        Observation before = current;
        PendingPreempt pending = pendingPreempt;
        pendingPreempt = null;
        CompositionState afterState;
        Disposition disposition;
        if (resolution == ReleaseResolution.PROVEN_RELEASED) {
            lastIssuedGeneration = pending.reservedGeneration;
            afterState = pending.nextState;
            disposition = Disposition.APPLIED;
        } else {
            afterState = pending.oldState;
            disposition = Disposition.RELEASE_PROVEN_UNCHANGED;
        }
        Observation after = new Observation(
                this,
                afterState,
                before.version + 1L,
                ObservationPhase.STABLE);
        current = after;
        return new Transition(before, after, disposition);
    }

    private Transition rejectObservation(Observation expected) {
        if (expected.owner != this) {
            return unchanged(Disposition.REJECTED_OBSERVATION);
        }
        if (expected != current) {
            return unchanged(Disposition.IGNORED_STALE);
        }
        if (current.phase == ObservationPhase.PREEMPT_PENDING) {
            return unchanged(Disposition.REJECTED_PREEMPTION_PENDING);
        }
        return null;
    }

    private Transition acquireNewGeneration(Acquisition acquisition) {
        if (lastIssuedGeneration == Long.MAX_VALUE) {
            return unchanged(Disposition.GENERATION_EXHAUSTED);
        }
        if (current.version == Long.MAX_VALUE) {
            return unchanged(Disposition.VERSION_EXHAUSTED);
        }
        long generation = lastIssuedGeneration + 1L;
        CompositionState afterState = stateFor(acquisition, generation);
        Transition transition = applyStable(afterState);
        if (transition.disposition == Disposition.APPLIED) {
            lastIssuedGeneration = generation;
        }
        return transition;
    }

    private Transition applyStable(CompositionState afterState) {
        if (current.version == Long.MAX_VALUE) {
            return unchanged(Disposition.VERSION_EXHAUSTED);
        }
        Observation before = current;
        Observation after = new Observation(
                this,
                afterState,
                before.version + 1L,
                ObservationPhase.STABLE);
        current = after;
        return new Transition(before, after, Disposition.APPLIED);
    }

    private PreemptRejected rejectedPreempt(Disposition disposition) {
        return new PreemptRejected(unchanged(disposition));
    }

    private static boolean releaseAllowed(
            CompositionState state, ReleaseDirective directive) {
        return switch (directive) {
            case CANCEL_CURRENT -> !(state instanceof CompositionState.Idle);
            case COMMIT_CURRENT -> state instanceof CompositionState.LatinComposing
                    || state instanceof CompositionState.RimeComposing
                    || state instanceof CompositionState.VoicePartial
                    || (state instanceof CompositionState.VoiceFinalizing finalizing
                            && finalizing.latestRevision() > 0L)
                    || state instanceof CompositionState.ActionPreview;
        };
    }

    private static CompositionState stateFor(
            Acquisition acquisition, long generation) {
        if (acquisition instanceof Acquisition.Latin latin) {
            return new CompositionState.LatinComposing(generation, latin.revision());
        }
        if (acquisition instanceof Acquisition.Rime rime) {
            return new CompositionState.RimeComposing(generation, rime.revision());
        }
        if (acquisition instanceof Acquisition.Voice) {
            return new CompositionState.VoicePreparing(generation);
        }
        if (acquisition instanceof Acquisition.Action) {
            return new CompositionState.ActionRunning(generation);
        }
        throw new AssertionError("unhandled acquisition variant");
    }

    private Transition rejectedForDifferentOwner() {
        return current.state instanceof CompositionState.Idle
                ? unchanged(Disposition.REJECTED_STATE)
                : unchanged(Disposition.REJECTED_OWNER);
    }

    private Transition rejectedForOwner(CompositionOwner owner) {
        if (current.state instanceof CompositionState.Idle) {
            return unchanged(Disposition.REJECTED_STATE);
        }
        return current.state.owner() == owner
                ? unchanged(Disposition.REJECTED_STATE)
                : unchanged(Disposition.REJECTED_OWNER);
    }

    private Transition unchanged(Disposition disposition) {
        return new Transition(current, current, disposition);
    }
}
