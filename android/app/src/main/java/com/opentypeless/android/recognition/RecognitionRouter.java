package com.opentypeless.android.recognition;

import com.opentypeless.android.config.RecognitionRoute;
import com.opentypeless.android.config.EffectiveProfile;
import com.opentypeless.android.config.OverrideValue;
import com.opentypeless.android.config.RecognitionRoute.ConfirmationPolicy;
import com.opentypeless.android.config.RecognitionRoute.FailureClass;
import com.opentypeless.android.config.RecognitionRoute.PrivacyClass;
import com.opentypeless.android.config.RecognitionRoute.ProviderCapability;
import com.opentypeless.android.config.RecognitionRoute.RouteStep;
import com.opentypeless.android.recognition.ProviderRegistry.RouteLease;
import com.opentypeless.android.recognition.ProviderRegistry.RouteLeaseFound;

import java.util.Objects;

/**
 * Finite, package-confined route decision state machine.
 *
 * <p>The router selects only a canonical registry descriptor. It owns no Provider instance,
 * Android object, endpoint, credential, audio, transcript, callback, executor, persistence, or
 * editor capability. An {@link Attempt} is an opaque generation-bound decision token, not Provider
 * execution authority. REC-010 confirmation is bound to the exact effective profile, pending
 * request identity and registry lease; cancellation and stale observations never resume routing.
 */
final class RecognitionRouter {
    private final RecognitionRoute route;
    private final ProviderRegistry registry;
    private final EffectiveProfile effectiveProfile;
    private final PrivacyAuthorization privacyAuthorization;
    private final ProviderCircuitBreaker circuitBreaker;
    private Status status = Status.NEW;
    private Attempt activeAttempt;
    private ConfirmationRequest pendingConfirmation;
    private long attemptGeneration;

    RecognitionRouter(
            RecognitionRoute route,
            ProviderRegistry registry,
            EffectiveProfile effectiveProfile,
            PrivacyAuthorization privacyAuthorization,
            ProviderCircuitBreaker circuitBreaker) {
        this.route = Objects.requireNonNull(route, "route");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.effectiveProfile = Objects.requireNonNull(effectiveProfile, "effectiveProfile");
        this.privacyAuthorization = Objects.requireNonNull(
                privacyAuthorization,
                "privacyAuthorization");
        this.circuitBreaker = Objects.requireNonNull(circuitBreaker, "circuitBreaker");
        if (privacyAuthorization.ownerProfile != effectiveProfile) {
            throw new IllegalArgumentException("privacy authorization belongs to another profile");
        }
    }

    synchronized Decision start() {
        if (status != Status.NEW) return ignoredForStatus();
        FailureReason profileFailure = effectiveProfileFailure();
        if (profileFailure != null) {
            return fail(
                    profileFailure == FailureReason.EFFECTIVE_ROUTE_DISABLED
                            ? FailureClass.PERMISSION_DENIED
                            : FailureClass.UNAVAILABLE,
                    profileFailure);
        }
        return selectStep(0, 1, null, false);
    }

    synchronized Decision onConfirmation(
            ConfirmationRequest expected,
            ConfirmationDecision decision) {
        ConfirmationRequest request = Objects.requireNonNull(expected, "expected");
        ConfirmationDecision choice = Objects.requireNonNull(decision, "decision");
        if (status != Status.AWAITING_CONFIRMATION
                || request != pendingConfirmation
                || request.owner != this) {
            return new Ignored(IgnoreReason.STALE_CONFIRMATION);
        }
        if (choice == ConfirmationDecision.CANCEL) {
            return fail(FailureClass.CANCELLED, FailureReason.CONFIRMATION_REJECTED);
        }
        FailureReason profileFailure = effectiveProfileFailure();
        if (profileFailure != null) {
            return fail(
                    profileFailure == FailureReason.EFFECTIVE_ROUTE_DISABLED
                            ? FailureClass.PERMISSION_DENIED
                            : FailureClass.UNAVAILABLE,
                    profileFailure);
        }
        if (!registry.isCurrent(request.lease)) {
            return fail(FailureClass.UNAVAILABLE, FailureReason.PROVIDER_CHANGED);
        }
        ProviderCircuitBreaker.AcquireResult circuit =
                circuitBreaker.acquire(request.lease.descriptor());
        if (!(circuit instanceof ProviderCircuitBreaker.PermitGranted granted)) {
            return circuitFailure(circuit);
        }
        ProviderCircuitBreaker.Permit permit = granted.permit();
        long generation = nextGeneration();
        if (generation == 0L) {
            circuitBreaker.abandon(permit);
            return fail(FailureClass.INTERNAL_ERROR, FailureReason.GENERATION_EXHAUSTED);
        }
        pendingConfirmation = null;
        activeAttempt = new Attempt(
                this,
                request.lease,
                permit,
                generation,
                request.stepIndex,
                request.attemptNumber,
                request.targetPrivacy);
        status = Status.ACTIVE;
        return new AttemptReady(activeAttempt);
    }

    synchronized Decision onFailure(Attempt expected, FailureClass failureClass) {
        Attempt attempt = Objects.requireNonNull(expected, "expected");
        FailureClass failure = Objects.requireNonNull(failureClass, "failureClass");
        if (!isActiveIdentity(attempt)) {
            return new Ignored(IgnoreReason.STALE_ATTEMPT);
        }
        if (!registry.isCurrent(attempt.lease)) {
            circuitBreaker.abandon(attempt.permit);
            return fail(FailureClass.UNAVAILABLE, FailureReason.PROVIDER_CHANGED);
        }

        circuitBreaker.onFailure(attempt.permit, failure);
        activeAttempt = null;
        if (isTerminalFailure(failure)) {
            return fail(failure, FailureReason.TERMINAL_FAILURE);
        }

        RouteStep step = route.steps().get(attempt.stepIndex);
        if (attempt.attemptNumber < step.retryPolicy().maximumAttempts()
                && step.retryPolicy().retryOn().contains(failure)) {
            return selectStep(
                    attempt.stepIndex,
                    attempt.attemptNumber + 1,
                    attempt.privacyClass,
                    true);
        }
        if (step.fallbackOn().contains(failure)
                && attempt.stepIndex + 1 < route.steps().size()) {
            return selectStep(
                    attempt.stepIndex + 1,
                    1,
                    attempt.privacyClass,
                    false);
        }
        return fail(failure, FailureReason.EXHAUSTED);
    }

    synchronized Decision onSuccess(Attempt expected) {
        Attempt attempt = Objects.requireNonNull(expected, "expected");
        if (!isActiveIdentity(attempt)) {
            return new Ignored(IgnoreReason.STALE_ATTEMPT);
        }
        if (!registry.isCurrent(attempt.lease)) {
            circuitBreaker.abandon(attempt.permit);
            return fail(FailureClass.UNAVAILABLE, FailureReason.PROVIDER_CHANGED);
        }
        circuitBreaker.onSuccess(attempt.permit);
        activeAttempt = null;
        pendingConfirmation = null;
        status = Status.COMPLETED;
        return new Completed();
    }

    synchronized boolean isCurrent(Attempt expected) {
        return expected != null
                && isActiveIdentity(expected)
                && registry.isCurrent(expected.lease);
    }

    @Override
    public synchronized String toString() {
        return "RecognitionRouter{status=" + status
                + ", stepCount=" + route.steps().size()
                + ", activeAttempt=" + (activeAttempt != null)
                + ", confirmationPending=" + (pendingConfirmation != null)
                + ", identities=<redacted>}";
    }

    private Decision selectStep(
            int stepIndex,
            int attemptNumber,
            PrivacyClass previousPrivacy,
            boolean confirmationAlreadySatisfied) {
        RouteStep step = route.steps().get(stepIndex);
        ProviderRegistry.RouteLookupResult lookup = registry.routeLease(step.providerId());
        if (!(lookup instanceof RouteLeaseFound found)) {
            return fail(FailureClass.UNAVAILABLE, FailureReason.PROVIDER_UNAVAILABLE);
        }
        RouteLease lease = found.lease();
        ProviderCapabilities capabilities = lease.descriptor().capabilities();
        if (capabilities.privacyClass() != step.privacyClass()) {
            return fail(FailureClass.UNAVAILABLE, FailureReason.PRIVACY_MISMATCH);
        }
        if (!supportsAll(capabilities, step)) {
            return fail(FailureClass.UNAVAILABLE, FailureReason.CAPABILITY_MISMATCH);
        }
        boolean privacyDowngrade = previousPrivacy != null
                && exposure(step.privacyClass()) > exposure(previousPrivacy);
        if (!confirmationAlreadySatisfied
                && requiresConfirmation(
                        step.confirmationPolicy(),
                        privacyDowngrade,
                        step.privacyClass())) {
            long generation = nextGeneration();
            if (generation == 0L) {
                return fail(FailureClass.INTERNAL_ERROR, FailureReason.GENERATION_EXHAUSTED);
            }
            activeAttempt = null;
            pendingConfirmation = new ConfirmationRequest(
                    this,
                    lease,
                    generation,
                    stepIndex,
                    attemptNumber,
                    step.confirmationPolicy(),
                    step.privacyClass(),
                    privacyDowngrade);
            status = Status.AWAITING_CONFIRMATION;
            return new ConfirmationRequired(pendingConfirmation);
        }
        if (!registry.isCurrent(lease)) {
            return fail(FailureClass.UNAVAILABLE, FailureReason.PROVIDER_CHANGED);
        }
        ProviderCircuitBreaker.AcquireResult circuit = circuitBreaker.acquire(lease.descriptor());
        if (!(circuit instanceof ProviderCircuitBreaker.PermitGranted granted)) {
            return circuitFailure(circuit);
        }
        ProviderCircuitBreaker.Permit permit = granted.permit();
        long generation = nextGeneration();
        if (generation == 0L) {
            circuitBreaker.abandon(permit);
            return fail(FailureClass.INTERNAL_ERROR, FailureReason.GENERATION_EXHAUSTED);
        }
        pendingConfirmation = null;
        activeAttempt = new Attempt(
                this,
                lease,
                permit,
                generation,
                stepIndex,
                attemptNumber,
                step.privacyClass());
        status = Status.ACTIVE;
        return new AttemptReady(activeAttempt);
    }

    private long nextGeneration() {
        if (attemptGeneration == Long.MAX_VALUE) return 0L;
        return ++attemptGeneration;
    }

    private Decision fail(FailureClass failureClass, FailureReason reason) {
        activeAttempt = null;
        pendingConfirmation = null;
        status = Status.FAILED;
        return new RouteFailed(failureClass, reason);
    }

    private Decision circuitFailure(ProviderCircuitBreaker.AcquireResult result) {
        ProviderCircuitBreaker.RejectionReason reason =
                ((ProviderCircuitBreaker.PermitRejected) result).reason();
        return reason == ProviderCircuitBreaker.RejectionReason.OPEN
                        || reason == ProviderCircuitBreaker.RejectionReason.HALF_OPEN_BUSY
                ? fail(FailureClass.UNAVAILABLE, FailureReason.CIRCUIT_OPEN)
                : fail(FailureClass.INTERNAL_ERROR, FailureReason.CIRCUIT_UNAVAILABLE);
    }

    private boolean isActiveIdentity(Attempt attempt) {
        return status == Status.ACTIVE
                && attempt == activeAttempt
                && attempt.owner == this;
    }

    private Decision ignoredForStatus() {
        return new Ignored(switch (status) {
            case ACTIVE -> IgnoreReason.ALREADY_STARTED;
            case AWAITING_CONFIRMATION -> IgnoreReason.CONFIRMATION_PENDING;
            case COMPLETED, FAILED -> IgnoreReason.TERMINAL;
            case NEW -> throw new AssertionError("new router must start");
        });
    }

    private static boolean isTerminalFailure(FailureClass failureClass) {
        return failureClass == FailureClass.CANCELLED
                || failureClass == FailureClass.PERMISSION_DENIED
                || failureClass == FailureClass.TARGET_CHANGED;
    }

    private boolean requiresConfirmation(
            ConfirmationPolicy policy,
            boolean privacyDowngrade,
            PrivacyClass targetPrivacy) {
        return policy == ConfirmationPolicy.REQUIRE_BEFORE_USE
                || (privacyDowngrade
                        && policy == ConfirmationPolicy.REQUIRE_ON_PRIVACY_DOWNGRADE
                        && !privacyAuthorization.allows(targetPrivacy));
    }

    private FailureReason effectiveProfileFailure() {
        OverrideValue<String> resolved = effectiveProfile.voiceRouteId().value();
        if (resolved instanceof OverrideValue.Disabled<?>) {
            return FailureReason.EFFECTIVE_ROUTE_DISABLED;
        }
        if (!(resolved instanceof OverrideValue.Value<?> explicit)
                || !(explicit.value() instanceof String routeId)
                || !route.id().equals(routeId)) {
            return FailureReason.EFFECTIVE_ROUTE_MISMATCH;
        }
        return null;
    }

    private static int exposure(PrivacyClass privacyClass) {
        return privacyClass.ordinal();
    }

    private static boolean supportsAll(ProviderCapabilities capabilities, RouteStep step) {
        for (ProviderCapability required : step.requiredCapabilities()) {
            boolean supported = switch (required) {
                case STREAMING -> capabilities.supportsStreaming();
                case PARTIAL_REVISION -> capabilities.supportsPartialRevision();
                case ENDPOINTING -> capabilities.supportsEndpointing();
                case ON_DEVICE -> capabilities.supportsOnDevice();
                case PROMPT -> capabilities.supportsPrompt();
                case BIASING_TERMS -> capabilities.supportsBiasingTerms();
                case DYNAMIC_KEYTERMS -> capabilities.supportsDynamicKeyterms();
                case LANGUAGE_DETECTION -> capabilities.supportsLanguageDetection();
                case TIMESTAMPS -> capabilities.supportsTimestamps();
                case AUDIO_UPLOAD -> capabilities.supportsAudioUpload();
            };
            if (!supported) return false;
        }
        return true;
    }

    sealed interface Decision permits
            AttemptReady,
            ConfirmationRequired,
            RouteFailed,
            Completed,
            Ignored {}

    record AttemptReady(Attempt attempt) implements Decision {
        AttemptReady {
            attempt = Objects.requireNonNull(attempt, "attempt");
        }
    }

    record ConfirmationRequired(ConfirmationRequest request) implements Decision {
        ConfirmationRequired {
            request = Objects.requireNonNull(request, "request");
        }
    }

    record RouteFailed(FailureClass failureClass, FailureReason reason) implements Decision {
        RouteFailed {
            failureClass = Objects.requireNonNull(failureClass, "failureClass");
            reason = Objects.requireNonNull(reason, "reason");
        }
    }

    record Completed() implements Decision {}

    record Ignored(IgnoreReason reason) implements Decision {
        Ignored {
            reason = Objects.requireNonNull(reason, "reason");
        }
    }

    static final class Attempt {
        private final RecognitionRouter owner;
        private final RouteLease lease;
        private final ProviderCircuitBreaker.Permit permit;
        private final long generation;
        private final int stepIndex;
        private final int attemptNumber;
        private final PrivacyClass privacyClass;

        private Attempt(
                RecognitionRouter owner,
                RouteLease lease,
                ProviderCircuitBreaker.Permit permit,
                long generation,
                int stepIndex,
                int attemptNumber,
                PrivacyClass privacyClass) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.lease = Objects.requireNonNull(lease, "lease");
            this.permit = Objects.requireNonNull(permit, "permit");
            this.generation = generation;
            this.stepIndex = stepIndex;
            this.attemptNumber = attemptNumber;
            this.privacyClass = Objects.requireNonNull(privacyClass, "privacyClass");
        }

        ProviderDescriptor descriptor() {
            return lease.descriptor();
        }

        int stepIndex() {
            return stepIndex;
        }

        int attemptNumber() {
            return attemptNumber;
        }

        PrivacyClass privacyClass() {
            return privacyClass;
        }

        @Override
        public String toString() {
            return "Attempt{generation=<redacted>, stepIndex=" + stepIndex
                    + ", attemptNumber=" + attemptNumber
                    + ", privacyClass=" + privacyClass
                    + ", provider=<redacted>}";
        }
    }

    static final class ConfirmationRequest {
        private final RecognitionRouter owner;
        private final RouteLease lease;
        private final long generation;
        private final int stepIndex;
        private final int attemptNumber;
        private final ConfirmationPolicy policy;
        private final PrivacyClass targetPrivacy;
        private final boolean privacyDowngrade;

        private ConfirmationRequest(
                RecognitionRouter owner,
                RouteLease lease,
                long generation,
                int stepIndex,
                int attemptNumber,
                ConfirmationPolicy policy,
                PrivacyClass targetPrivacy,
                boolean privacyDowngrade) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.lease = Objects.requireNonNull(lease, "lease");
            this.generation = generation;
            this.stepIndex = stepIndex;
            this.attemptNumber = attemptNumber;
            this.policy = Objects.requireNonNull(policy, "policy");
            this.targetPrivacy = Objects.requireNonNull(targetPrivacy, "targetPrivacy");
            this.privacyDowngrade = privacyDowngrade;
        }

        ConfirmationPolicy policy() {
            return policy;
        }

        PrivacyClass targetPrivacy() {
            return targetPrivacy;
        }

        boolean privacyDowngrade() {
            return privacyDowngrade;
        }

        @Override
        public String toString() {
            return "ConfirmationRequest{policy=" + policy
                    + ", targetPrivacy=" + targetPrivacy
                    + ", privacyDowngrade=" + privacyDowngrade
                    + ", identities=<redacted>}";
        }
    }

    /** Profile-bound, content-free proof that a configured privacy exposure was preauthorized. */
    static final class PrivacyAuthorization {
        private final EffectiveProfile ownerProfile;
        private final AuthorizationMode mode;
        private final PrivacyClass maximumPrivacy;

        private PrivacyAuthorization(
                EffectiveProfile ownerProfile,
                AuthorizationMode mode,
                PrivacyClass maximumPrivacy) {
            this.ownerProfile = Objects.requireNonNull(ownerProfile, "ownerProfile");
            this.mode = Objects.requireNonNull(mode, "mode");
            this.maximumPrivacy = Objects.requireNonNull(maximumPrivacy, "maximumPrivacy");
            if (mode == AuthorizationMode.REQUIRE_CONFIRMATION
                    && maximumPrivacy != PrivacyClass.ON_DEVICE) {
                throw new IllegalArgumentException("non-preauthorized policy has an invalid bound");
            }
        }

        static PrivacyAuthorization requireConfirmation(EffectiveProfile profile) {
            return new PrivacyAuthorization(
                    profile,
                    AuthorizationMode.REQUIRE_CONFIRMATION,
                    PrivacyClass.ON_DEVICE);
        }

        static PrivacyAuthorization preauthorized(
                EffectiveProfile profile,
                PrivacyClass maximumPrivacy) {
            return new PrivacyAuthorization(
                    profile,
                    AuthorizationMode.PREAUTHORIZED,
                    maximumPrivacy);
        }

        private boolean allows(PrivacyClass targetPrivacy) {
            return mode == AuthorizationMode.PREAUTHORIZED
                    && exposure(targetPrivacy) <= exposure(maximumPrivacy);
        }

        @Override
        public String toString() {
            return "PrivacyAuthorization{mode=" + mode
                    + ", maximumPrivacy=" + maximumPrivacy
                    + ", profile=<redacted>}";
        }
    }

    enum ConfirmationDecision {
        APPROVE_ONCE,
        CANCEL
    }

    enum FailureReason {
        PROVIDER_UNAVAILABLE,
        PROVIDER_CHANGED,
        CIRCUIT_OPEN,
        CIRCUIT_UNAVAILABLE,
        CAPABILITY_MISMATCH,
        PRIVACY_MISMATCH,
        EFFECTIVE_ROUTE_DISABLED,
        EFFECTIVE_ROUTE_MISMATCH,
        CONFIRMATION_REJECTED,
        TERMINAL_FAILURE,
        EXHAUSTED,
        GENERATION_EXHAUSTED
    }

    enum IgnoreReason {
        STALE_ATTEMPT,
        STALE_CONFIRMATION,
        ALREADY_STARTED,
        CONFIRMATION_PENDING,
        TERMINAL
    }

    private enum Status {
        NEW,
        ACTIVE,
        AWAITING_CONFIRMATION,
        COMPLETED,
        FAILED
    }

    private enum AuthorizationMode {
        REQUIRE_CONFIRMATION,
        PREAUTHORIZED
    }
}
