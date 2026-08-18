package com.opentypeless.android.recognition;

import com.opentypeless.android.config.RecognitionRoute;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Bounded process-local registry for reviewed provider descriptors and their probe capability.
 *
 * <p>The registry owns no Android object, endpoint, credential, executor, session, audio, or
 * transcript. Probe execution happens outside the registry monitor and is accepted only if the
 * exact registration generation remains enabled when the callback returns.
 */
final class ProviderRegistry {
    static final int MAX_PROVIDERS = 32;

    private static final Pattern LOOKUP_ID_PATTERN =
            Pattern.compile("[a-z][a-z0-9._-]{0,127}");

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private long generation;

    synchronized RegistrationResult register(
            ProviderDescriptor descriptor,
            ProviderProbe probe,
            boolean enabled) {
        ProviderDescriptor safeDescriptor = Objects.requireNonNull(descriptor, "descriptor");
        ProviderProbe safeProbe = Objects.requireNonNull(probe, "probe");
        if (entries.containsKey(safeDescriptor.id())) {
            return RegistrationResult.DUPLICATE_ID;
        }
        if (entries.size() >= MAX_PROVIDERS) {
            return RegistrationResult.CAPACITY_EXCEEDED;
        }
        long entryGeneration = nextGeneration();
        entries.put(
                safeDescriptor.id(),
                new Entry(safeDescriptor, safeProbe, enabled, entryGeneration));
        return RegistrationResult.REGISTERED;
    }

    synchronized EnableResult setEnabled(String providerId, boolean enabled) {
        Entry entry = find(providerId);
        if (entry == null) return EnableResult.UNKNOWN_PROVIDER;
        if (entry.enabled == enabled) return EnableResult.UNCHANGED;
        long entryGeneration = nextGeneration();
        entry.enabled = enabled;
        entry.generation = entryGeneration;
        return EnableResult.UPDATED;
    }

    synchronized LookupResult lookup(String providerId) {
        Entry entry = find(providerId);
        if (entry == null) {
            return new LookupRejected(AccessFailure.UNKNOWN_PROVIDER);
        }
        if (!entry.enabled) {
            return new LookupRejected(AccessFailure.PROVIDER_DISABLED);
        }
        return new LookupFound(entry.descriptor);
    }

    synchronized RouteLookupResult routeLease(String providerId) {
        Entry entry = find(providerId);
        if (entry == null) {
            return new RouteLeaseRejected(AccessFailure.UNKNOWN_PROVIDER);
        }
        if (!entry.enabled) {
            return new RouteLeaseRejected(AccessFailure.PROVIDER_DISABLED);
        }
        return new RouteLeaseFound(new RouteLease(this, entry, entry.generation));
    }

    synchronized boolean isCurrent(RouteLease lease) {
        if (lease == null || lease.owner != this) return false;
        Entry current = entries.get(lease.entry.descriptor.id());
        return current == lease.entry
                && current.enabled
                && current.generation == lease.generation;
    }

    ProbeResult probe(String providerId) {
        ProbeLease lease;
        synchronized (this) {
            Entry entry = find(providerId);
            if (entry == null) {
                return new ProbeRejected(AccessFailure.UNKNOWN_PROVIDER);
            }
            if (!entry.enabled) {
                return new ProbeRejected(AccessFailure.PROVIDER_DISABLED);
            }
            lease = new ProbeLease(entry, entry.generation);
        }

        ProbeObservation observation;
        try {
            observation = lease.entry.probe.probe();
        } catch (RuntimeException ignored) {
            observation = null;
        }

        synchronized (this) {
            Entry current = entries.get(lease.entry.descriptor.id());
            if (current != lease.entry || current.generation != lease.generation) {
                if (current == lease.entry && !current.enabled) {
                    return new ProbeRejected(AccessFailure.PROVIDER_DISABLED);
                }
                return new ProbeRejected(AccessFailure.PROVIDER_CHANGED);
            }
            if (!current.enabled) {
                return new ProbeRejected(AccessFailure.PROVIDER_DISABLED);
            }
            if (observation == null) {
                return new ProbeRejected(AccessFailure.PROBE_FAILED);
            }
            if (observation instanceof ObservedAvailable available) {
                if (!current.descriptor.capabilities().equals(available.capabilities())) {
                    return new ProbeRejected(AccessFailure.CAPABILITY_MISMATCH);
                }
                return new ProbeAvailable(current.descriptor);
            }
            ObservedUnavailable unavailable = (ObservedUnavailable) observation;
            return new ProbeUnavailable(unavailable.failureClass());
        }
    }

    synchronized int size() {
        return entries.size();
    }

    synchronized int enabledCount() {
        int count = 0;
        for (Entry entry : entries.values()) {
            if (entry.enabled) count++;
        }
        return count;
    }

    @Override
    public synchronized String toString() {
        return "ProviderRegistry{providerCount=" + entries.size()
                + ", enabledCount=" + enabledCount() + ", identities=<redacted>}";
    }

    private Entry find(String providerId) {
        if (providerId == null
                || providerId.length() > ProviderDescriptor.MAX_ID_CODE_POINTS
                || !LOOKUP_ID_PATTERN.matcher(providerId).matches()) {
            return null;
        }
        return entries.get(providerId);
    }

    private long nextGeneration() {
        if (generation == Long.MAX_VALUE) {
            throw new IllegalStateException("provider registry generation exhausted");
        }
        return ++generation;
    }

    @FunctionalInterface
    interface ProviderProbe {
        ProbeObservation probe();
    }

    sealed interface ProbeObservation permits ObservedAvailable, ObservedUnavailable {}

    record ObservedAvailable(ProviderCapabilities capabilities) implements ProbeObservation {
        ObservedAvailable {
            capabilities = Objects.requireNonNull(capabilities, "capabilities");
        }

        @Override
        public String toString() {
            return "ObservedAvailable{capabilities=<redacted>}";
        }
    }

    record ObservedUnavailable(RecognitionRoute.FailureClass failureClass)
            implements ProbeObservation {
        ObservedUnavailable {
            failureClass = Objects.requireNonNull(failureClass, "failureClass");
            if (failureClass == RecognitionRoute.FailureClass.NO_MATCH
                    || failureClass == RecognitionRoute.FailureClass.SPEECH_TIMEOUT
                    || failureClass == RecognitionRoute.FailureClass.CANCELLED
                    || failureClass == RecognitionRoute.FailureClass.TARGET_CHANGED) {
                throw new IllegalArgumentException(
                        "session-only failure cannot describe provider availability");
            }
        }

        @Override
        public String toString() {
            return "ObservedUnavailable{failureClass=" + failureClass + "}";
        }
    }

    sealed interface LookupResult permits LookupFound, LookupRejected {}

    record LookupFound(ProviderDescriptor descriptor) implements LookupResult {
        LookupFound {
            descriptor = Objects.requireNonNull(descriptor, "descriptor");
        }

        @Override
        public String toString() {
            return "LookupFound{descriptor=<redacted>}";
        }
    }

    record LookupRejected(AccessFailure failure) implements LookupResult {
        LookupRejected {
            failure = Objects.requireNonNull(failure, "failure");
        }
    }

    sealed interface RouteLookupResult permits RouteLeaseFound, RouteLeaseRejected {}

    record RouteLeaseFound(RouteLease lease) implements RouteLookupResult {
        RouteLeaseFound {
            lease = Objects.requireNonNull(lease, "lease");
        }

        @Override
        public String toString() {
            return "RouteLeaseFound{lease=<redacted>}";
        }
    }

    record RouteLeaseRejected(AccessFailure failure) implements RouteLookupResult {
        RouteLeaseRejected {
            failure = Objects.requireNonNull(failure, "failure");
        }
    }

    static final class RouteLease {
        private final ProviderRegistry owner;
        private final Entry entry;
        private final long generation;

        private RouteLease(ProviderRegistry owner, Entry entry, long generation) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.entry = Objects.requireNonNull(entry, "entry");
            this.generation = generation;
        }

        ProviderDescriptor descriptor() {
            return entry.descriptor;
        }

        @Override
        public String toString() {
            return "RouteLease{provider=<redacted>, generation=<redacted>}";
        }
    }

    sealed interface ProbeResult permits ProbeAvailable, ProbeUnavailable, ProbeRejected {}

    record ProbeAvailable(ProviderDescriptor descriptor) implements ProbeResult {
        ProbeAvailable {
            descriptor = Objects.requireNonNull(descriptor, "descriptor");
        }

        @Override
        public String toString() {
            return "ProbeAvailable{descriptor=<redacted>}";
        }
    }

    record ProbeUnavailable(RecognitionRoute.FailureClass failureClass)
            implements ProbeResult {
        ProbeUnavailable {
            failureClass = Objects.requireNonNull(failureClass, "failureClass");
        }
    }

    record ProbeRejected(AccessFailure failure) implements ProbeResult {
        ProbeRejected {
            failure = Objects.requireNonNull(failure, "failure");
        }
    }

    enum AccessFailure {
        UNKNOWN_PROVIDER,
        PROVIDER_DISABLED,
        PROVIDER_CHANGED,
        CAPABILITY_MISMATCH,
        PROBE_FAILED
    }

    enum RegistrationResult {
        REGISTERED,
        DUPLICATE_ID,
        CAPACITY_EXCEEDED
    }

    enum EnableResult {
        UPDATED,
        UNCHANGED,
        UNKNOWN_PROVIDER
    }

    private static final class Entry {
        private final ProviderDescriptor descriptor;
        private final ProviderProbe probe;
        private boolean enabled;
        private long generation;

        private Entry(
                ProviderDescriptor descriptor,
                ProviderProbe probe,
                boolean enabled,
                long generation) {
            this.descriptor = descriptor;
            this.probe = probe;
            this.enabled = enabled;
            this.generation = generation;
        }
    }

    private record ProbeLease(Entry entry, long generation) {}
}
