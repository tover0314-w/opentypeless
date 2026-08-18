package com.opentypeless.android.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, bounded recognition-route configuration.
 *
 * <p>This value is neither a runtime routing decision nor execution authority. It carries no
 * provider instance, Android object, secret, endpoint, callback, persistence contract, or user
 * content. A future {@code RecognitionRouter} must still cross-check every step against the
 * registry's actual descriptor and the effective privacy policy.
 */
public record RecognitionRoute(
        String id,
        List<RouteStep> steps,
        PrivacyClass privacyFloor,
        boolean allowPrivacyDowngrade) {
    public static final int MAX_ID_CODE_POINTS = 128;
    public static final int MAX_STEPS = 8;

    private static final Set<FailureClass> NON_ROUTABLE_FAILURES =
            Collections.unmodifiableSet(EnumSet.of(
                    FailureClass.PERMISSION_DENIED,
                    FailureClass.CANCELLED,
                    FailureClass.TARGET_CHANGED));

    public RecognitionRoute {
        id = requireConfigId(id, "route id");
        privacyFloor = Objects.requireNonNull(privacyFloor, "privacyFloor");
        steps = boundedSteps(steps);
        validateSteps(steps, privacyFloor, allowPrivacyDowngrade);
    }

    /** One finite provider attempt in route order. */
    public record RouteStep(
            String providerId,
            PrivacyClass privacyClass,
            RetryPolicy retryPolicy,
            Set<FailureClass> fallbackOn,
            Set<ProviderCapability> requiredCapabilities,
            ConfirmationPolicy confirmationPolicy) {
        public RouteStep {
            providerId = requireConfigId(providerId, "provider id");
            privacyClass = Objects.requireNonNull(privacyClass, "privacyClass");
            retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
            fallbackOn = immutableEnumSet(fallbackOn, FailureClass.class, "fallbackOn");
            requiredCapabilities = immutableEnumSet(
                    requiredCapabilities,
                    ProviderCapability.class,
                    "requiredCapabilities");
            confirmationPolicy = Objects.requireNonNull(
                    confirmationPolicy,
                    "confirmationPolicy");

            rejectNonRoutableFailures(fallbackOn, "fallbackOn");
            boolean claimsOnDevice = requiredCapabilities.contains(ProviderCapability.ON_DEVICE);
            if ((privacyClass == PrivacyClass.ON_DEVICE) != claimsOnDevice) {
                throw new IllegalArgumentException(
                        "on-device privacy and capability must be declared together");
            }
            if (privacyClass == PrivacyClass.ON_DEVICE
                    && requiredCapabilities.contains(ProviderCapability.AUDIO_UPLOAD)) {
                throw new IllegalArgumentException(
                        "an on-device route step cannot require audio upload");
            }
        }

        @Override
        public String toString() {
            return "RouteStep{providerId=<redacted>, privacyClass=" + privacyClass
                    + ", maximumAttempts=" + retryPolicy.maximumAttempts()
                    + ", fallbackClassCount=" + fallbackOn.size()
                    + ", capabilityCount=" + requiredCapabilities.size()
                    + ", confirmationPolicy=" + confirmationPolicy + "}";
        }
    }

    /** Bounded total attempts plus the stable failures eligible for the single retry. */
    public record RetryPolicy(int maximumAttempts, Set<FailureClass> retryOn) {
        public RetryPolicy {
            retryOn = immutableEnumSet(retryOn, FailureClass.class, "retryOn");
            if (maximumAttempts < 1 || maximumAttempts > 2) {
                throw new IllegalArgumentException("maximum attempts must be one or two");
            }
            if ((maximumAttempts == 1) != retryOn.isEmpty()) {
                throw new IllegalArgumentException(
                        "retry failures must be empty for one attempt and non-empty for two");
            }
            rejectNonRoutableFailures(retryOn, "retryOn");
        }

        @Override
        public String toString() {
            return "RetryPolicy{maximumAttempts=" + maximumAttempts
                    + ", retryClassCount=" + retryOn.size() + "}";
        }
    }

    /** Privacy strength from most private to most externally disclosed. */
    public enum PrivacyClass {
        ON_DEVICE,
        LOCAL_NETWORK,
        PUBLIC_NETWORK
    }

    /** Provider behavior that a future registry must prove rather than infer from a name. */
    public enum ProviderCapability {
        STREAMING,
        PARTIAL_REVISION,
        ENDPOINTING,
        ON_DEVICE,
        PROMPT,
        BIASING_TERMS,
        DYNAMIC_KEYTERMS,
        LANGUAGE_DETECTION,
        TIMESTAMPS,
        AUDIO_UPLOAD
    }

    /** Stable route failure vocabulary; raw provider or OEM messages never enter this model. */
    public enum FailureClass {
        UNAVAILABLE,
        MODEL_MISSING,
        PERMISSION_DENIED,
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
        NO_MATCH,
        SPEECH_TIMEOUT,
        UNSUPPORTED_LANGUAGE,
        CANCELLED,
        TARGET_CHANGED,
        INTERNAL_ERROR
    }

    /** User-consent requirement attached to one route step. */
    public enum ConfirmationPolicy {
        NOT_REQUIRED,
        REQUIRE_ON_PRIVACY_DOWNGRADE,
        REQUIRE_BEFORE_USE
    }

    @Override
    public String toString() {
        return "RecognitionRoute{id=<redacted>, stepCount=" + steps.size()
                + ", privacyFloor=" + privacyFloor
                + ", allowPrivacyDowngrade=" + allowPrivacyDowngrade + "}";
    }

    private static void validateSteps(
            List<RouteStep> steps,
            PrivacyClass privacyFloor,
            boolean allowPrivacyDowngrade) {
        if (steps.isEmpty() || steps.size() > MAX_STEPS) {
            throw new IllegalArgumentException("route step count is outside its bound");
        }
        Set<String> providerIds = new HashSet<>();
        int floorExposure = exposure(privacyFloor);
        int previousExposure = -1;
        RouteStep previous = null;
        for (int index = 0; index < steps.size(); index++) {
            RouteStep step = Objects.requireNonNull(steps.get(index), "route step");
            if (!providerIds.add(step.providerId())) {
                throw new IllegalArgumentException("route provider ids must be unique");
            }

            boolean last = index == steps.size() - 1;
            if (last == !step.fallbackOn().isEmpty()) {
                throw new IllegalArgumentException(
                        "non-final steps require fallback failures and the final step forbids them");
            }

            int currentExposure = exposure(step.privacyClass());
            if (currentExposure > floorExposure) {
                throw new IllegalArgumentException("route step is below the privacy floor");
            }
            boolean downgrade = previousExposure >= 0 && currentExposure > previousExposure;
            if (downgrade && !allowPrivacyDowngrade) {
                throw new IllegalArgumentException("route contains a forbidden privacy downgrade");
            }
            if (downgrade && step.confirmationPolicy() == ConfirmationPolicy.NOT_REQUIRED) {
                throw new IllegalArgumentException(
                        "a privacy downgrade requires an explicit confirmation policy");
            }
            if (previous != null
                    && previous.fallbackOn().contains(FailureClass.AUTHENTICATION)
                    && step.confirmationPolicy() != ConfirmationPolicy.REQUIRE_BEFORE_USE) {
                throw new IllegalArgumentException(
                        "authentication failure fallback requires confirmation before use");
            }
            previousExposure = currentExposure;
            previous = step;
        }
    }

    private static List<RouteStep> boundedSteps(List<RouteStep> values) {
        List<RouteStep> safe = Objects.requireNonNull(values, "steps");
        List<RouteStep> copy = new ArrayList<>(MAX_STEPS);
        for (RouteStep value : safe) {
            if (copy.size() == MAX_STEPS) {
                throw new IllegalArgumentException("route step count is outside its bound");
            }
            copy.add(Objects.requireNonNull(value, "route step"));
        }
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("route step count is outside its bound");
        }
        return List.copyOf(copy);
    }

    private static int exposure(PrivacyClass privacyClass) {
        // Enum order is an audited part of the privacy contract: lower ordinal is more private.
        return privacyClass.ordinal();
    }

    private static void rejectNonRoutableFailures(
            Set<FailureClass> failures,
            String name) {
        if (!Collections.disjoint(failures, NON_ROUTABLE_FAILURES)) {
            throw new IllegalArgumentException(name + " contains a terminal failure");
        }
    }

    private static <E extends Enum<E>> Set<E> immutableEnumSet(
            Set<E> values,
            Class<E> enumType,
            String name) {
        Set<E> safe = Objects.requireNonNull(values, name);
        if (safe.isEmpty()) return Set.of();
        EnumSet<E> copy = EnumSet.noneOf(enumType);
        int observed = 0;
        for (E value : safe) {
            if (++observed > enumType.getEnumConstants().length) {
                throw new IllegalArgumentException(name + " exceeds its enum bound");
            }
            copy.add(Objects.requireNonNull(value, name + " entry"));
        }
        return Collections.unmodifiableSet(copy);
    }

    private static String requireConfigId(String value, String name) {
        String safe = Objects.requireNonNull(value, name);
        if (safe.isEmpty() || safe.length() > MAX_ID_CODE_POINTS) {
            throw new IllegalArgumentException(name + " is outside its bound");
        }
        for (int index = 0; index < safe.length(); index++) {
            char character = safe.charAt(index);
            boolean lower = character >= 'a' && character <= 'z';
            boolean allowed = lower
                    || (index > 0 && character >= '0' && character <= '9')
                    || (index > 0 && (character == '.' || character == '_'
                    || character == '-'));
            if (!allowed) {
                throw new IllegalArgumentException(name + " has an invalid shape");
            }
        }
        return safe;
    }
}
