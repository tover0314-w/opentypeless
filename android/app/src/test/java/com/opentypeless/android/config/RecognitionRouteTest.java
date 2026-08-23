package com.opentypeless.android.config;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.config.RecognitionRoute.ConfirmationPolicy;
import com.opentypeless.android.config.RecognitionRoute.FailureClass;
import com.opentypeless.android.config.RecognitionRoute.PrivacyClass;
import com.opentypeless.android.config.RecognitionRoute.ProviderCapability;
import com.opentypeless.android.config.RecognitionRoute.RetryPolicy;
import com.opentypeless.android.config.RecognitionRoute.RouteStep;
import java.io.Serializable;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class RecognitionRouteTest {
    private static final RetryPolicy ONCE = new RetryPolicy(1, Set.of());
    private static final Set<ProviderCapability> ON_DEVICE =
            Set.of(ProviderCapability.ON_DEVICE);

    @Test
    public void routeFamilyIsExactImmutablePureDomainShape() {
        assertComponents(
                RecognitionRoute.class,
                new String[]{"id", "steps", "privacyFloor", "allowPrivacyDowngrade"},
                new Class<?>[]{String.class, List.class, PrivacyClass.class, boolean.class});
        assertComponents(
                RouteStep.class,
                new String[]{
                        "providerId",
                        "privacyClass",
                        "retryPolicy",
                        "fallbackOn",
                        "requiredCapabilities",
                        "confirmationPolicy"},
                new Class<?>[]{
                        String.class,
                        PrivacyClass.class,
                        RetryPolicy.class,
                        Set.class,
                        Set.class,
                        ConfirmationPolicy.class});
        assertComponents(
                RetryPolicy.class,
                new String[]{"maximumAttempts", "retryOn"},
                new Class<?>[]{int.class, Set.class});

        for (Class<?> type : new Class<?>[]{RecognitionRoute.class, RouteStep.class, RetryPolicy.class}) {
            assertTrue(type.isRecord());
            assertTrue(Modifier.isFinal(type.getModifiers()));
            assertFalse(Serializable.class.isAssignableFrom(type));
            for (Class<?> implemented : type.getInterfaces()) {
                assertFalse(implemented.getName().startsWith("android."));
            }
        }
    }

    @Test
    public void enumVocabulariesAreClosedAndExact() {
        assertArrayEquals(
                new PrivacyClass[]{
                        PrivacyClass.ON_DEVICE,
                        PrivacyClass.LOCAL_NETWORK,
                        PrivacyClass.PUBLIC_NETWORK},
                PrivacyClass.values());
        assertArrayEquals(
                new ProviderCapability[]{
                        ProviderCapability.STREAMING,
                        ProviderCapability.PARTIAL_REVISION,
                        ProviderCapability.ENDPOINTING,
                        ProviderCapability.ON_DEVICE,
                        ProviderCapability.PROMPT,
                        ProviderCapability.BIASING_TERMS,
                        ProviderCapability.DYNAMIC_KEYTERMS,
                        ProviderCapability.LANGUAGE_DETECTION,
                        ProviderCapability.TIMESTAMPS,
                        ProviderCapability.AUDIO_UPLOAD},
                ProviderCapability.values());
        assertArrayEquals(
                new FailureClass[]{
                        FailureClass.UNAVAILABLE,
                        FailureClass.MODEL_MISSING,
                        FailureClass.PERMISSION_DENIED,
                        FailureClass.OEM_MIC_BLOCKED,
                        FailureClass.AUDIO_ERROR,
                        FailureClass.NETWORK_UNAVAILABLE,
                        FailureClass.NETWORK_TIMEOUT,
                        FailureClass.AUTHENTICATION,
                        FailureClass.QUOTA_EXCEEDED,
                        FailureClass.RATE_LIMITED,
                        FailureClass.SERVER_ERROR,
                        FailureClass.PROTOCOL_ERROR,
                        FailureClass.RECOGNIZER_BUSY,
                        FailureClass.NO_MATCH,
                        FailureClass.SPEECH_TIMEOUT,
                        FailureClass.UNSUPPORTED_LANGUAGE,
                        FailureClass.CANCELLED,
                        FailureClass.TARGET_CHANGED,
                        FailureClass.INTERNAL_ERROR},
                FailureClass.values());
        assertArrayEquals(
                new ConfirmationPolicy[]{
                        ConfirmationPolicy.NOT_REQUIRED,
                        ConfirmationPolicy.REQUIRE_ON_PRIVACY_DOWNGRADE,
                        ConfirmationPolicy.REQUIRE_BEFORE_USE},
                ConfirmationPolicy.values());
    }

    @Test
    public void buildsFiniteSingleAndMaximumStepRoutesWithValueSemantics() {
        RecognitionRoute single = new RecognitionRoute(
                "route.primary",
                List.of(step("asr.local", PrivacyClass.ON_DEVICE, Set.of(), ON_DEVICE)),
                PrivacyClass.ON_DEVICE,
                false);
        assertEquals("route.primary", single.id());
        assertEquals(1, single.steps().size());
        assertEquals(single, new RecognitionRoute(
                "route.primary",
                List.of(step("asr.local", PrivacyClass.ON_DEVICE, Set.of(), ON_DEVICE)),
                PrivacyClass.ON_DEVICE,
                false));

        List<RouteStep> maximum = new ArrayList<>();
        for (int index = 0; index < RecognitionRoute.MAX_STEPS; index++) {
            maximum.add(step(
                    "asr.p" + index,
                    PrivacyClass.PUBLIC_NETWORK,
                    index == RecognitionRoute.MAX_STEPS - 1
                            ? Set.of()
                            : Set.of(FailureClass.UNAVAILABLE),
                    Set.of()));
        }
        RecognitionRoute route = new RecognitionRoute(
                "route.maximum",
                maximum,
                PrivacyClass.PUBLIC_NETWORK,
                false);
        assertEquals(RecognitionRoute.MAX_STEPS, route.steps().size());
    }

    @Test
    public void defensivelyCopiesOrderedStepsAndEnumSets() {
        EnumSet<FailureClass> fallback = EnumSet.of(FailureClass.NETWORK_TIMEOUT);
        EnumSet<ProviderCapability> capabilities = EnumSet.of(ProviderCapability.STREAMING);
        RouteStep first = step(
                "asr.remote",
                PrivacyClass.PUBLIC_NETWORK,
                fallback,
                capabilities);
        List<RouteStep> mutable = new ArrayList<>(List.of(
                first,
                step("asr.backup", PrivacyClass.PUBLIC_NETWORK, Set.of(), Set.of())));
        RecognitionRoute route = new RecognitionRoute(
                "route.copy",
                mutable,
                PrivacyClass.PUBLIC_NETWORK,
                false);

        fallback.clear();
        capabilities.clear();
        mutable.clear();
        assertEquals(2, route.steps().size());
        assertEquals(Set.of(FailureClass.NETWORK_TIMEOUT), first.fallbackOn());
        assertEquals(Set.of(ProviderCapability.STREAMING), first.requiredCapabilities());
        assertThrows(UnsupportedOperationException.class, () -> route.steps().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.fallbackOn().clear());
        assertNotSame(mutable, route.steps());
    }

    @Test
    public void hostileCollectionsAreStoppedAtDomainBoundsBeforeUnboundedCopying() {
        int[] stepReads = {0};
        List<RouteStep> lyingSteps = new java.util.AbstractList<>() {
            @Override
            public RouteStep get(int index) {
                return step("asr.get" + index, PrivacyClass.PUBLIC_NETWORK,
                        Set.of(FailureClass.UNAVAILABLE), Set.of());
            }

            @Override
            public int size() {
                return 1;
            }

            @Override
            public java.util.Iterator<RouteStep> iterator() {
                return new java.util.Iterator<>() {
                    @Override public boolean hasNext() { return true; }

                    @Override
                    public RouteStep next() {
                        return step(
                                "asr.iter" + stepReads[0]++,
                                PrivacyClass.PUBLIC_NETWORK,
                                Set.of(FailureClass.UNAVAILABLE),
                                Set.of());
                    }
                };
            }
        };
        assertInvalid(() -> route(
                "route.hostile",
                lyingSteps,
                PrivacyClass.PUBLIC_NETWORK,
                false));
        assertEquals(RecognitionRoute.MAX_STEPS + 1, stepReads[0]);

        int[] failureReads = {0};
        Set<FailureClass> lyingFailures = new java.util.AbstractSet<>() {
            @Override
            public java.util.Iterator<FailureClass> iterator() {
                return new java.util.Iterator<>() {
                    @Override public boolean hasNext() { return true; }

                    @Override
                    public FailureClass next() {
                        failureReads[0]++;
                        return FailureClass.NETWORK_TIMEOUT;
                    }
                };
            }

            @Override
            public int size() {
                return 1;
            }
        };
        assertInvalid(() -> new RetryPolicy(2, lyingFailures));
        assertEquals(FailureClass.values().length + 1, failureReads[0]);
    }

    @Test
    public void rejectsEmptyOversizedDuplicateOrUnreachableRoutes() {
        assertInvalid(() -> route("route.empty", List.of(), PrivacyClass.PUBLIC_NETWORK, false));

        List<RouteStep> oversized = new ArrayList<>();
        for (int index = 0; index <= RecognitionRoute.MAX_STEPS; index++) {
            oversized.add(step(
                    "asr.p" + index,
                    PrivacyClass.PUBLIC_NETWORK,
                    index == RecognitionRoute.MAX_STEPS
                            ? Set.of()
                            : Set.of(FailureClass.UNAVAILABLE),
                    Set.of()));
        }
        assertInvalid(() -> route(
                "route.oversized", oversized, PrivacyClass.PUBLIC_NETWORK, false));
        assertInvalid(() -> route(
                "route.duplicate",
                List.of(
                        step("asr.same", PrivacyClass.PUBLIC_NETWORK,
                                Set.of(FailureClass.UNAVAILABLE), Set.of()),
                        step("asr.same", PrivacyClass.PUBLIC_NETWORK, Set.of(), Set.of())),
                PrivacyClass.PUBLIC_NETWORK,
                false));
        assertInvalid(() -> route(
                "route.unreachable",
                List.of(
                        step("asr.first", PrivacyClass.PUBLIC_NETWORK, Set.of(), Set.of()),
                        step("asr.last", PrivacyClass.PUBLIC_NETWORK, Set.of(), Set.of())),
                PrivacyClass.PUBLIC_NETWORK,
                false));
        assertInvalid(() -> route(
                "route.dangling",
                List.of(step("asr.last", PrivacyClass.PUBLIC_NETWORK,
                        Set.of(FailureClass.UNAVAILABLE), Set.of())),
                PrivacyClass.PUBLIC_NETWORK,
                false));
    }

    @Test
    public void routeAndProviderIdsUseTheExactCfg001BoundWithoutNormalization() {
        String maximum = "a" + "b".repeat(RecognitionRoute.MAX_ID_CODE_POINTS - 1);
        RecognitionRoute route = route(
                maximum,
                List.of(step(maximum, PrivacyClass.PUBLIC_NETWORK, Set.of(), Set.of())),
                PrivacyClass.PUBLIC_NETWORK,
                false);
        assertEquals(maximum, route.id());
        assertEquals(maximum, route.steps().get(0).providerId());

        for (String invalid : new String[]{
                "", "1route", "Route", "route/path", "route value", " route", "route\n"}) {
            assertInvalid(() -> route(
                    invalid,
                    List.of(step("asr.valid", PrivacyClass.PUBLIC_NETWORK, Set.of(), Set.of())),
                    PrivacyClass.PUBLIC_NETWORK,
                    false));
            assertInvalid(() -> step(
                    invalid,
                    PrivacyClass.PUBLIC_NETWORK,
                    Set.of(),
                    Set.of()));
        }
        assertInvalid(() -> route(
                maximum + "c",
                List.of(step("asr.valid", PrivacyClass.PUBLIC_NETWORK, Set.of(), Set.of())),
                PrivacyClass.PUBLIC_NETWORK,
                false));
        assertInvalid(() -> step(
                maximum + "c",
                PrivacyClass.PUBLIC_NETWORK,
                Set.of(),
                Set.of()));
    }

    @Test
    public void retryPolicyIsBoundedClassifiedAndNeverRetriesTerminalFailures() {
        RetryPolicy retry = new RetryPolicy(
                2,
                EnumSet.of(FailureClass.RECOGNIZER_BUSY, FailureClass.NETWORK_TIMEOUT));
        assertEquals(2, retry.maximumAttempts());
        assertEquals(
                Set.of(FailureClass.RECOGNIZER_BUSY, FailureClass.NETWORK_TIMEOUT),
                retry.retryOn());
        assertThrows(UnsupportedOperationException.class, () -> retry.retryOn().clear());

        assertInvalid(() -> new RetryPolicy(0, Set.of()));
        assertInvalid(() -> new RetryPolicy(3, Set.of(FailureClass.UNAVAILABLE)));
        assertInvalid(() -> new RetryPolicy(1, Set.of(FailureClass.UNAVAILABLE)));
        assertInvalid(() -> new RetryPolicy(2, Set.of()));
        for (FailureClass terminal : new FailureClass[]{
                FailureClass.PERMISSION_DENIED,
                FailureClass.CANCELLED,
                FailureClass.TARGET_CHANGED}) {
            assertInvalid(() -> new RetryPolicy(2, Set.of(terminal)));
        }
    }

    @Test
    public void fallbackNeverContainsTerminalFailures() {
        for (FailureClass terminal : new FailureClass[]{
                FailureClass.PERMISSION_DENIED,
                FailureClass.CANCELLED,
                FailureClass.TARGET_CHANGED}) {
            assertInvalid(() -> step(
                    "asr.primary",
                    PrivacyClass.PUBLIC_NETWORK,
                    Set.of(terminal),
                    Set.of()));
        }
        RouteStep valid = step(
                "asr.primary",
                PrivacyClass.PUBLIC_NETWORK,
                EnumSet.of(
                        FailureClass.MODEL_MISSING,
                        FailureClass.NETWORK_TIMEOUT,
                        FailureClass.NO_MATCH),
                Set.of(ProviderCapability.STREAMING));
        assertEquals(3, valid.fallbackOn().size());

        RouteStep authFailure = step(
                "asr.primary",
                PrivacyClass.PUBLIC_NETWORK,
                Set.of(FailureClass.AUTHENTICATION),
                Set.of());
        assertInvalid(() -> route(
                "route.auth_silent",
                List.of(
                        authFailure,
                        step("asr.backup", PrivacyClass.PUBLIC_NETWORK, Set.of(), Set.of())),
                PrivacyClass.PUBLIC_NETWORK,
                false));
        RecognitionRoute confirmed = route(
                "route.auth_confirmed",
                List.of(
                        authFailure,
                        step(
                                "asr.backup",
                                PrivacyClass.PUBLIC_NETWORK,
                                Set.of(),
                                Set.of(),
                                ConfirmationPolicy.REQUIRE_BEFORE_USE)),
                PrivacyClass.PUBLIC_NETWORK,
                false);
        assertEquals(2, confirmed.steps().size());
    }

    @Test
    public void privacyFloorAndEveryActualDowngradeFailClosed() {
        RouteStep local = step(
                "asr.local",
                PrivacyClass.ON_DEVICE,
                Set.of(FailureClass.MODEL_MISSING),
                ON_DEVICE);
        RouteStep lan = step(
                "asr.lan",
                PrivacyClass.LOCAL_NETWORK,
                Set.of(FailureClass.NETWORK_TIMEOUT),
                Set.of(),
                ConfirmationPolicy.REQUIRE_ON_PRIVACY_DOWNGRADE);
        RouteStep cloud = step(
                "asr.cloud",
                PrivacyClass.PUBLIC_NETWORK,
                Set.of(),
                Set.of(),
                ConfirmationPolicy.REQUIRE_BEFORE_USE);

        assertEquals(3, route(
                "route.confirmed",
                List.of(local, lan, cloud),
                PrivacyClass.PUBLIC_NETWORK,
                true).steps().size());
        assertInvalid(() -> route(
                "route.no_downgrade",
                List.of(local, lan),
                PrivacyClass.PUBLIC_NETWORK,
                false));
        assertInvalid(() -> route(
                "route.no_confirmation",
                List.of(
                        local,
                        step("asr.lan", PrivacyClass.LOCAL_NETWORK, Set.of(), Set.of())),
                PrivacyClass.PUBLIC_NETWORK,
                true));
        assertInvalid(() -> route(
                "route.floor",
                List.of(cloud),
                PrivacyClass.LOCAL_NETWORK,
                true));

        RecognitionRoute upgrade = route(
                "route.upgrade",
                List.of(
                        step("asr.lan", PrivacyClass.LOCAL_NETWORK,
                                Set.of(FailureClass.NETWORK_TIMEOUT), Set.of()),
                        step("asr.local", PrivacyClass.ON_DEVICE, Set.of(), ON_DEVICE)),
                PrivacyClass.LOCAL_NETWORK,
                false);
        assertFalse(upgrade.allowPrivacyDowngrade());
    }

    @Test
    public void privacyAndCapabilityClaimsCannotContradictEachOther() {
        assertInvalid(() -> step(
                "asr.local",
                PrivacyClass.ON_DEVICE,
                Set.of(),
                Set.of()));
        assertInvalid(() -> step(
                "asr.lan",
                PrivacyClass.LOCAL_NETWORK,
                Set.of(),
                ON_DEVICE));
        assertInvalid(() -> step(
                "asr.cloud",
                PrivacyClass.PUBLIC_NETWORK,
                Set.of(),
                ON_DEVICE));
        assertInvalid(() -> step(
                "asr.local",
                PrivacyClass.ON_DEVICE,
                Set.of(),
                Set.of(ProviderCapability.ON_DEVICE, ProviderCapability.AUDIO_UPLOAD)));

        RouteStep valid = step(
                "asr.local",
                PrivacyClass.ON_DEVICE,
                Set.of(),
                Set.of(
                        ProviderCapability.ON_DEVICE,
                        ProviderCapability.STREAMING,
                        ProviderCapability.PARTIAL_REVISION));
        assertTrue(valid.requiredCapabilities().contains(ProviderCapability.ON_DEVICE));
    }

    @Test
    public void descriptionsRedactRouteAndProviderIdentity() {
        String routeSecret = "route.private_identifier";
        String providerSecret = "asr.private_provider";
        RouteStep step = step(
                providerSecret,
                PrivacyClass.PUBLIC_NETWORK,
                Set.of(),
                Set.of(ProviderCapability.STREAMING));
        RecognitionRoute route = route(
                routeSecret,
                List.of(step),
                PrivacyClass.PUBLIC_NETWORK,
                false);

        for (String description : new String[]{route.toString(), step.toString(), ONCE.toString()}) {
            assertFalse(description.contains(routeSecret));
            assertFalse(description.contains(providerSecret));
            assertFalse(description.toLowerCase().contains("secret"));
            assertFalse(description.contains("https://"));
        }
        assertTrue(route.toString().contains("stepCount=1"));
        assertTrue(step.toString().contains("providerId=<redacted>"));
    }

    private static RecognitionRoute route(
            String id,
            List<RouteStep> steps,
            PrivacyClass privacyFloor,
            boolean allowPrivacyDowngrade) {
        return new RecognitionRoute(id, steps, privacyFloor, allowPrivacyDowngrade);
    }

    private static RouteStep step(
            String providerId,
            PrivacyClass privacyClass,
            Set<FailureClass> fallbackOn,
            Set<ProviderCapability> capabilities) {
        return step(
                providerId,
                privacyClass,
                fallbackOn,
                capabilities,
                ConfirmationPolicy.NOT_REQUIRED);
    }

    private static RouteStep step(
            String providerId,
            PrivacyClass privacyClass,
            Set<FailureClass> fallbackOn,
            Set<ProviderCapability> capabilities,
            ConfirmationPolicy confirmationPolicy) {
        return new RouteStep(
                providerId,
                privacyClass,
                ONCE,
                fallbackOn,
                capabilities,
                confirmationPolicy);
    }

    private static void assertComponents(
            Class<?> type,
            String[] expectedNames,
            Class<?>[] expectedTypes) {
        assertArrayEquals(
                expectedNames,
                java.util.Arrays.stream(type.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .toArray(String[]::new));
        assertArrayEquals(
                expectedTypes,
                java.util.Arrays.stream(type.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getType)
                        .toArray(Class<?>[]::new));
    }

    private static void assertInvalid(Runnable action) {
        assertThrows(RuntimeException.class, action::run);
    }
}
