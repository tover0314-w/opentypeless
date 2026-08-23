package com.opentypeless.android.recognition;

import com.opentypeless.android.config.RecognitionRoute.FailureClass;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded, process-local health gate for canonical provider descriptors.
 *
 * <p>The breaker owns no Provider, Android object, endpoint, credential, audio, transcript,
 * callback, executor, persistence, or editor capability. Callers must present the exact opaque
 * permit returned for an attempt; stale and foreign observations never change health state.
 */
final class ProviderCircuitBreaker {
    static final int FAILURE_THRESHOLD = 3;
    static final long OPEN_INTERVAL_MILLIS = 30_000L;
    static final int MAX_PROVIDERS = ProviderRegistry.MAX_PROVIDERS;

    private final MonotonicClock clock;
    private final Map<ProviderDescriptor, Entry> entries = new IdentityHashMap<>();
    private long lastNowMillis = -1L;

    ProviderCircuitBreaker(MonotonicClock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    synchronized AcquireResult acquire(ProviderDescriptor descriptor) {
        ProviderDescriptor provider = Objects.requireNonNull(descriptor, "descriptor");
        Entry entry = entries.get(provider);
        if (entry != null && entry.permanentRejection != null) {
            return new PermitRejected(entry.permanentRejection);
        }

        long now = readNow();
        if (now < 0L) return new PermitRejected(RejectionReason.CLOCK_INVALID);
        if (entry == null) {
            if (entries.size() >= MAX_PROVIDERS) {
                return new PermitRejected(RejectionReason.CAPACITY_EXCEEDED);
            }
            entry = new Entry();
            entries.put(provider, entry);
        }
        if (entry.state == State.OPEN) {
            if (now < entry.reopenAtMillis) {
                return new PermitRejected(RejectionReason.OPEN);
            }
            if (!advanceEpoch(entry)) {
                return new PermitRejected(RejectionReason.GENERATION_EXHAUSTED);
            }
            entry.state = State.HALF_OPEN;
            Permit permit = new Permit(this, entry, entry.epoch, true);
            entry.halfOpenPermit = permit;
            return new PermitGranted(permit);
        }
        if (entry.state == State.HALF_OPEN) {
            return new PermitRejected(RejectionReason.HALF_OPEN_BUSY);
        }
        return new PermitGranted(new Permit(this, entry, entry.epoch, false));
    }

    synchronized Disposition onSuccess(Permit expected) {
        Permit permit = currentPermit(expected);
        if (permit == null) return Disposition.IGNORED_STALE;
        permit.consumed = true;
        if (!advanceEpoch(permit.entry)) return Disposition.GENERATION_EXHAUSTED;
        closeHealthy(permit.entry);
        return Disposition.RECOVERED;
    }

    synchronized Disposition onFailure(Permit expected, FailureClass failureClass) {
        Permit permit = currentPermit(expected);
        FailureClass failure = Objects.requireNonNull(failureClass, "failureClass");
        if (permit == null) return Disposition.IGNORED_STALE;
        permit.consumed = true;

        if (!isHealthFailure(failure)) {
            if (isRecoveryEvidence(failure)) {
                if (!advanceEpoch(permit.entry)) return Disposition.GENERATION_EXHAUSTED;
                closeHealthy(permit.entry);
                return Disposition.RECOVERED;
            }
            if (permit.halfOpen) {
                return reopen(permit.entry, false);
            }
            return Disposition.IGNORED_NON_HEALTH;
        }

        if (permit.halfOpen) {
            return reopen(permit.entry, false);
        }
        if (permit.entry.consecutiveFailures < FAILURE_THRESHOLD) {
            permit.entry.consecutiveFailures++;
        }
        if (permit.entry.consecutiveFailures < FAILURE_THRESHOLD) {
            return Disposition.RECORDED;
        }
        return reopen(permit.entry, true);
    }

    synchronized Disposition abandon(Permit expected) {
        Permit permit = currentPermit(expected);
        if (permit == null) return Disposition.IGNORED_STALE;
        permit.consumed = true;
        if (!permit.halfOpen) return Disposition.IGNORED_NON_HEALTH;
        return reopen(permit.entry, false);
    }

    synchronized int size() {
        return entries.size();
    }

    @Override
    public synchronized String toString() {
        int open = 0;
        int halfOpen = 0;
        for (Entry entry : entries.values()) {
            if (entry.state == State.OPEN) open++;
            if (entry.state == State.HALF_OPEN) halfOpen++;
        }
        return "ProviderCircuitBreaker{providerCount=" + entries.size()
                + ", openCount=" + open
                + ", halfOpenCount=" + halfOpen
                + ", identities=<redacted>}";
    }

    private Disposition reopen(Entry entry, boolean newlyOpened) {
        long now = readNow();
        if (now < 0L || now > Long.MAX_VALUE - OPEN_INTERVAL_MILLIS) {
            failClosed(entry, RejectionReason.CLOCK_INVALID);
            return Disposition.CLOCK_INVALID;
        }
        if (!advanceEpoch(entry)) return Disposition.GENERATION_EXHAUSTED;
        entry.state = State.OPEN;
        entry.consecutiveFailures = FAILURE_THRESHOLD;
        entry.reopenAtMillis = now + OPEN_INTERVAL_MILLIS;
        entry.halfOpenPermit = null;
        return newlyOpened ? Disposition.OPENED : Disposition.REOPENED;
    }

    private Permit currentPermit(Permit permit) {
        if (permit == null || permit.owner != this || permit.consumed) return null;
        Entry entry = permit.entry;
        if (entry.permanentRejection != null || permit.epoch != entry.epoch) return null;
        if (permit.halfOpen) {
            return entry.state == State.HALF_OPEN && entry.halfOpenPermit == permit
                    ? permit
                    : null;
        }
        return entry.state == State.CLOSED ? permit : null;
    }

    private boolean advanceEpoch(Entry entry) {
        if (entry.epoch == Long.MAX_VALUE) {
            failClosed(entry, RejectionReason.GENERATION_EXHAUSTED);
            return false;
        }
        entry.epoch++;
        return true;
    }

    private void closeHealthy(Entry entry) {
        entry.state = State.CLOSED;
        entry.consecutiveFailures = 0;
        entry.reopenAtMillis = 0L;
        entry.halfOpenPermit = null;
    }

    private void failClosed(Entry entry, RejectionReason reason) {
        entry.state = State.OPEN;
        entry.consecutiveFailures = FAILURE_THRESHOLD;
        entry.reopenAtMillis = Long.MAX_VALUE;
        entry.halfOpenPermit = null;
        entry.permanentRejection = reason;
    }

    private long readNow() {
        final long observed;
        try {
            observed = clock.nowMillis();
        } catch (RuntimeException ignored) {
            return -1L;
        }
        if (observed < 0L || observed < lastNowMillis) return -1L;
        lastNowMillis = observed;
        return observed;
    }

    private static boolean isHealthFailure(FailureClass failure) {
        return switch (failure) {
            case UNAVAILABLE,
                    MODEL_MISSING,
                    OEM_MIC_BLOCKED,
                    AUDIO_ERROR,
                    NETWORK_UNAVAILABLE,
                    NETWORK_TIMEOUT,
                    AUTHENTICATION,
                    QUOTA_EXCEEDED,
                    RATE_LIMITED,
                    SERVER_ERROR,
                    PROTOCOL_ERROR,
                    RECOGNIZER_BUSY,
                    INTERNAL_ERROR -> true;
            case PERMISSION_DENIED,
                    NO_MATCH,
                    SPEECH_TIMEOUT,
                    UNSUPPORTED_LANGUAGE,
                    CANCELLED,
                    TARGET_CHANGED -> false;
        };
    }

    private static boolean isRecoveryEvidence(FailureClass failure) {
        return failure == FailureClass.NO_MATCH || failure == FailureClass.SPEECH_TIMEOUT;
    }

    @FunctionalInterface
    interface MonotonicClock {
        long nowMillis();
    }

    sealed interface AcquireResult permits PermitGranted, PermitRejected {}

    record PermitGranted(Permit permit) implements AcquireResult {
        PermitGranted {
            permit = Objects.requireNonNull(permit, "permit");
        }

        @Override
        public String toString() {
            return "PermitGranted{permit=<redacted>}";
        }
    }

    record PermitRejected(RejectionReason reason) implements AcquireResult {
        PermitRejected {
            reason = Objects.requireNonNull(reason, "reason");
        }
    }

    static final class Permit {
        private final ProviderCircuitBreaker owner;
        private final Entry entry;
        private final long epoch;
        private final boolean halfOpen;
        private boolean consumed;

        private Permit(
                ProviderCircuitBreaker owner,
                Entry entry,
                long epoch,
                boolean halfOpen) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.entry = Objects.requireNonNull(entry, "entry");
            this.epoch = epoch;
            this.halfOpen = halfOpen;
        }

        @Override
        public String toString() {
            return "Permit{identity=<redacted>}";
        }
    }

    enum RejectionReason {
        OPEN,
        HALF_OPEN_BUSY,
        CAPACITY_EXCEEDED,
        CLOCK_INVALID,
        GENERATION_EXHAUSTED
    }

    enum Disposition {
        RECORDED,
        OPENED,
        RECOVERED,
        REOPENED,
        IGNORED_NON_HEALTH,
        IGNORED_STALE,
        CLOCK_INVALID,
        GENERATION_EXHAUSTED
    }

    private enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private static final class Entry {
        private State state = State.CLOSED;
        private int consecutiveFailures;
        private long reopenAtMillis;
        private long epoch = 1L;
        private Permit halfOpenPermit;
        private RejectionReason permanentRejection;
    }
}
