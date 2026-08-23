package com.opentypeless.android.recognition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.config.AppRule;
import com.opentypeless.android.config.EffectiveProfile;
import com.opentypeless.android.config.EffectiveProfileResolver;
import com.opentypeless.android.config.GlobalConfig;
import com.opentypeless.android.config.OverrideValue;
import com.opentypeless.android.config.ProcessingMode;
import com.opentypeless.android.config.RecognitionRoute;
import com.opentypeless.android.config.RecognitionRoute.ConfirmationPolicy;
import com.opentypeless.android.config.RecognitionRoute.FailureClass;
import com.opentypeless.android.config.RecognitionRoute.PrivacyClass;
import com.opentypeless.android.config.RecognitionRoute.ProviderCapability;
import com.opentypeless.android.config.RecognitionRoute.RetryPolicy;
import com.opentypeless.android.config.RecognitionRoute.RouteStep;
import com.opentypeless.android.config.RuleOverrides;
import com.opentypeless.android.context.FieldKind;
import com.opentypeless.android.recognition.ProviderRegistry.EnableResult;
import com.opentypeless.android.recognition.ProviderRegistry.ObservedAvailable;
import com.opentypeless.android.recognition.ProviderRegistry.RegistrationResult;
import com.opentypeless.android.recognition.RecognitionRouter.Attempt;
import com.opentypeless.android.recognition.RecognitionRouter.AttemptReady;
import com.opentypeless.android.recognition.RecognitionRouter.Completed;
import com.opentypeless.android.recognition.RecognitionRouter.ConfirmationDecision;
import com.opentypeless.android.recognition.RecognitionRouter.ConfirmationRequired;
import com.opentypeless.android.recognition.RecognitionRouter.FailureReason;
import com.opentypeless.android.recognition.RecognitionRouter.IgnoreReason;
import com.opentypeless.android.recognition.RecognitionRouter.Ignored;
import com.opentypeless.android.recognition.RecognitionRouter.PrivacyAuthorization;
import com.opentypeless.android.recognition.RecognitionRouter.RouteFailed;
import com.opentypeless.android.settings.RecognitionBackend;

import org.junit.Test;

import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class RecognitionRouterTest {
    @Test
    public void routerAndOpaqueTokensArePackageConfinedContentFreeValues() {
        assertTrue(Modifier.isFinal(RecognitionRouter.class.getModifiers()));
        assertFalse(Modifier.isPublic(RecognitionRouter.class.getModifiers()));
        assertFalse(Serializable.class.isAssignableFrom(RecognitionRouter.class));
        assertFalse(Serializable.class.isAssignableFrom(Attempt.class));
        assertFalse(Serializable.class.isAssignableFrom(
                RecognitionRouter.ConfirmationRequest.class));
        for (Constructor<?> constructor : Attempt.class.getDeclaredConstructors()) {
            assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        }
        for (Constructor<?> constructor
                : RecognitionRouter.ConfirmationRequest.class.getDeclaredConstructors()) {
            assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        }
        assertEquals(
                Set.of("route", "registry", "effectiveProfile", "privacyAuthorization",
                        "circuitBreaker", "status", "activeAttempt", "pendingConfirmation",
                        "attemptGeneration"),
                Arrays.stream(RecognitionRouter.class.getDeclaredFields())
                        .map(Field::getName)
                        .collect(java.util.stream.Collectors.toSet()));

        ProviderRegistry registry = registry(systemDefault("provider.secret"));
        RecognitionRouter router = router(
                route(
                        "route.secret",
                        List.of(step(
                                "provider.secret",
                                PrivacyClass.PUBLIC_NETWORK,
                                Set.of(),
                                Set.of(),
                                Set.of(ProviderCapability.STREAMING),
                                ConfirmationPolicy.NOT_REQUIRED)),
                        false),
                registry);
        Attempt attempt = ready(router.start());
        for (Object diagnostic : new Object[]{router, attempt, new AttemptReady(attempt)}) {
            String text = diagnostic.toString();
            assertFalse(text, text.contains("route.secret"));
            assertFalse(text, text.contains("provider.secret"));
        }
    }

    @Test
    public void initialSelectionUsesExactEnabledRegistryDescriptorCapabilitiesAndPrivacy() {
        ProviderDescriptor canonical = systemDefault("system.default");
        ProviderRegistry registry = registry(canonical);
        RecognitionRouter router = router(
                route(
                        "route.system",
                        List.of(step(
                                canonical.id(),
                                PrivacyClass.PUBLIC_NETWORK,
                                Set.of(),
                                Set.of(),
                                EnumSet.of(
                                        ProviderCapability.STREAMING,
                                        ProviderCapability.PARTIAL_REVISION,
                                        ProviderCapability.ENDPOINTING,
                                        ProviderCapability.BIASING_TERMS,
                                        ProviderCapability.AUDIO_UPLOAD),
                                ConfirmationPolicy.NOT_REQUIRED)),
                        false),
                registry);

        Attempt attempt = ready(router.start());
        assertSame(canonical, attempt.descriptor());
        assertEquals(0, attempt.stepIndex());
        assertEquals(1, attempt.attemptNumber());
        assertEquals(PrivacyClass.PUBLIC_NETWORK, attempt.privacyClass());
        assertTrue(router.isCurrent(attempt));
        assertIgnored(router.start(), IgnoreReason.ALREADY_STARTED);
    }

    @Test
    public void unavailableCapabilityAndPrivacyDriftFailBeforeAnAttemptIsPublished() {
        ProviderDescriptor local = localOffline("local");

        ProviderRegistry unknown = new ProviderRegistry();
        assertRouteFailure(
                router(single("missing", PrivacyClass.PUBLIC_NETWORK), unknown)
                        .start(),
                FailureClass.UNAVAILABLE,
                FailureReason.PROVIDER_UNAVAILABLE);

        ProviderRegistry disabled = new ProviderRegistry();
        register(disabled, systemDefault("disabled"), false);
        assertRouteFailure(
                router(single("disabled", PrivacyClass.PUBLIC_NETWORK), disabled)
                        .start(),
                FailureClass.UNAVAILABLE,
                FailureReason.PROVIDER_UNAVAILABLE);

        ProviderRegistry missingCapability = registry(local);
        RecognitionRoute capabilityRoute = route(
                "route.capability",
                List.of(step(
                        local.id(),
                        PrivacyClass.ON_DEVICE,
                        Set.of(),
                        Set.of(),
                        EnumSet.of(ProviderCapability.ON_DEVICE, ProviderCapability.STREAMING),
                        ConfirmationPolicy.NOT_REQUIRED)),
                false);
        assertRouteFailure(
                router(capabilityRoute, missingCapability).start(),
                FailureClass.UNAVAILABLE,
                FailureReason.CAPABILITY_MISMATCH);

        ProviderRegistry privacyMismatch = registry(systemDefault("cloud"));
        assertRouteFailure(
                router(
                        single("cloud", PrivacyClass.LOCAL_NETWORK), privacyMismatch).start(),
                FailureClass.UNAVAILABLE,
                FailureReason.PRIVACY_MISMATCH);
    }

    @Test
    public void failureTableRetriesFallsBackOrStopsWithoutUnboundedAttempts() {
        Object[][] cases = {
                {FailureClass.RECOGNIZER_BUSY, true, false},
                {FailureClass.OEM_MIC_BLOCKED, false, true},
                {FailureClass.NETWORK_TIMEOUT, false, true},
                {FailureClass.NO_MATCH, false, true},
                {FailureClass.SERVER_ERROR, false, false}
        };
        for (Object[] entry : cases) {
            FailureClass failure = (FailureClass) entry[0];
            boolean retry = (Boolean) entry[1];
            boolean fallback = (Boolean) entry[2];
            ProviderDescriptor primary = systemDefault("primary");
            ProviderDescriptor backup = systemDefault("backup");
            RecognitionRoute configured = route(
                    "route.table",
                    List.of(
                            step(
                                    primary.id(),
                                    PrivacyClass.PUBLIC_NETWORK,
                                    Set.of(FailureClass.RECOGNIZER_BUSY),
                                    EnumSet.of(
                                            FailureClass.OEM_MIC_BLOCKED,
                                            FailureClass.NETWORK_TIMEOUT,
                                            FailureClass.NO_MATCH),
                                    Set.of(),
                                    ConfirmationPolicy.NOT_REQUIRED),
                            step(
                                    backup.id(),
                                    PrivacyClass.PUBLIC_NETWORK,
                                    Set.of(),
                                    Set.of(),
                                    Set.of(),
                                    ConfirmationPolicy.NOT_REQUIRED)),
                    false);
            RecognitionRouter router = router(
                    configured,
                    registry(primary, backup));
            Attempt first = ready(router.start());
            Object decision = router.onFailure(first, failure);
            if (retry) {
                Attempt second = ready(decision);
                assertSame(primary, second.descriptor());
                assertEquals(2, second.attemptNumber());
                assertRouteFailure(
                        router.onFailure(second, failure),
                        failure,
                        FailureReason.EXHAUSTED);
            } else if (fallback) {
                Attempt second = ready(decision);
                assertSame(backup, second.descriptor());
                assertEquals(1, second.attemptNumber());
                assertEquals(1, second.stepIndex());
            } else {
                assertRouteFailure(decision, failure, FailureReason.EXHAUSTED);
            }
        }
    }

    @Test
    public void terminalFailuresNeverRetryOrFallbackEvenForHostileFeedback() {
        for (FailureClass terminal : new FailureClass[]{
                FailureClass.CANCELLED,
                FailureClass.PERMISSION_DENIED,
                FailureClass.TARGET_CHANGED}) {
            ProviderDescriptor first = systemDefault("first");
            ProviderDescriptor second = localOffline("second");
            RecognitionRouter router = router(
                    route(
                            "route.terminal",
                            List.of(
                                    step(
                                            first.id(),
                                            PrivacyClass.PUBLIC_NETWORK,
                                            Set.of(),
                                            Set.of(FailureClass.OEM_MIC_BLOCKED),
                                            Set.of(),
                                            ConfirmationPolicy.NOT_REQUIRED),
                                    step(
                                            second.id(),
                                            PrivacyClass.ON_DEVICE,
                                            Set.of(),
                                            Set.of(),
                                            Set.of(ProviderCapability.ON_DEVICE),
                                            ConfirmationPolicy.NOT_REQUIRED)),
                            false),
                    registry(first, second));
            Attempt attempt = ready(router.start());
            assertRouteFailure(
                    router.onFailure(attempt, terminal),
                    terminal,
                    FailureReason.TERMINAL_FAILURE);
            assertIgnored(router.onFailure(attempt, terminal), IgnoreReason.STALE_ATTEMPT);
        }
    }

    @Test
    public void privacyDowngradeAndAuthenticationFallbackRequireRec010Confirmation() {
        ProviderDescriptor local = localOffline("local");
        ProviderDescriptor cloud = systemDefault("cloud");
        RecognitionRouter downgrade = router(
                route(
                        "route.downgrade",
                        List.of(
                                step(
                                        local.id(),
                                        PrivacyClass.ON_DEVICE,
                                        Set.of(),
                                        Set.of(FailureClass.MODEL_MISSING),
                                        Set.of(ProviderCapability.ON_DEVICE),
                                        ConfirmationPolicy.NOT_REQUIRED),
                                step(
                                        cloud.id(),
                                        PrivacyClass.PUBLIC_NETWORK,
                                        Set.of(),
                                        Set.of(),
                                        Set.of(),
                                        ConfirmationPolicy.REQUIRE_ON_PRIVACY_DOWNGRADE)),
                        true),
                registry(local, cloud));
        Attempt localAttempt = ready(downgrade.start());
        ConfirmationRequired required = confirmation(
                downgrade.onFailure(localAttempt, FailureClass.MODEL_MISSING));
        assertEquals(
                ConfirmationPolicy.REQUIRE_ON_PRIVACY_DOWNGRADE,
                required.request().policy());
        assertEquals(PrivacyClass.PUBLIC_NETWORK, required.request().targetPrivacy());
        assertTrue(required.request().privacyDowngrade());
        assertFalse(required.toString().contains("cloud"));
        assertIgnored(downgrade.start(), IgnoreReason.CONFIRMATION_PENDING);

        RecognitionRouter authentication = router(
                route(
                        "route.auth",
                        List.of(
                                step(
                                        "first",
                                        PrivacyClass.PUBLIC_NETWORK,
                                        Set.of(),
                                        Set.of(FailureClass.AUTHENTICATION),
                                        Set.of(),
                                        ConfirmationPolicy.NOT_REQUIRED),
                                step(
                                        "second",
                                        PrivacyClass.PUBLIC_NETWORK,
                                        Set.of(),
                                        Set.of(),
                                        Set.of(),
                                        ConfirmationPolicy.REQUIRE_BEFORE_USE)),
                        false),
                registry(systemDefault("first"), systemDefault("second")));
        ConfirmationRequired authRequired = confirmation(authentication.onFailure(
                ready(authentication.start()), FailureClass.AUTHENTICATION));
        assertEquals(ConfirmationPolicy.REQUIRE_BEFORE_USE, authRequired.request().policy());
        assertFalse(authRequired.request().privacyDowngrade());
    }

    @Test
    public void effectiveProfileMustSelectTheExactRouteAndSensitiveHardSafetyWins() {
        RecognitionRoute configured = single("provider", PrivacyClass.PUBLIC_NETWORK);
        ProviderRegistry registry = registry(systemDefault("provider"));

        EffectiveProfile mismatch = profile("route.other", FieldKind.GENERAL);
        RecognitionRouter mismatched = new RecognitionRouter(
                configured,
                registry,
                mismatch,
                PrivacyAuthorization.requireConfirmation(mismatch),
                breaker());
        assertRouteFailure(
                mismatched.start(),
                FailureClass.UNAVAILABLE,
                FailureReason.EFFECTIVE_ROUTE_MISMATCH);

        EffectiveProfile sensitive = profile(configured.id(), FieldKind.SENSITIVE);
        RecognitionRouter denied = new RecognitionRouter(
                configured,
                new ProviderRegistry(),
                sensitive,
                PrivacyAuthorization.preauthorized(sensitive, PrivacyClass.PUBLIC_NETWORK),
                breaker());
        assertRouteFailure(
                denied.start(),
                FailureClass.PERMISSION_DENIED,
                FailureReason.EFFECTIVE_ROUTE_DISABLED);

        assertTrue(router(configured, registry, sessionProfile(configured.id())).start()
                instanceof AttemptReady);
        assertTrue(router(configured, registry, appProfile(configured.id())).start()
                instanceof AttemptReady);
    }

    @Test
    public void preauthorizationIsProfileBoundAndLimitedByMaximumExposure() {
        ProviderDescriptor local = localOffline("local");
        ProviderDescriptor lan = lanProvider("lan");
        ProviderDescriptor cloud = systemDefault("cloud");
        RecognitionRoute configured = threeTierRoute(local, lan, cloud);
        ProviderRegistry registry = registry(local, lan, cloud);
        EffectiveProfile profile = profile(configured.id(), FieldKind.GENERAL);

        PrivacyAuthorization lanOnly = PrivacyAuthorization.preauthorized(
                profile,
                PrivacyClass.LOCAL_NETWORK);
        RecognitionRouter limited = new RecognitionRouter(
                configured,
                registry,
                profile,
                lanOnly,
                breaker());
        Attempt localAttempt = ready(limited.start());
        Attempt lanAttempt = ready(limited.onFailure(
                localAttempt,
                FailureClass.MODEL_MISSING));
        assertSame(lan, lanAttempt.descriptor());
        assertTrue(limited.onFailure(lanAttempt, FailureClass.NETWORK_TIMEOUT)
                instanceof ConfirmationRequired);

        PrivacyAuthorization publicAllowed = PrivacyAuthorization.preauthorized(
                profile,
                PrivacyClass.PUBLIC_NETWORK);
        RecognitionRouter fullyAuthorized = new RecognitionRouter(
                configured,
                registry,
                profile,
                publicAllowed,
                breaker());
        Attempt first = ready(fullyAuthorized.start());
        Attempt second = ready(fullyAuthorized.onFailure(first, FailureClass.MODEL_MISSING));
        Attempt third = ready(fullyAuthorized.onFailure(second, FailureClass.NETWORK_TIMEOUT));
        assertSame(cloud, third.descriptor());

        EffectiveProfile foreign = profile(configured.id(), FieldKind.GENERAL);
        assertThrows(
                IllegalArgumentException.class,
                () -> new RecognitionRouter(
                        configured,
                        registry,
                        foreign,
                        publicAllowed,
                        breaker()));
        assertFalse(publicAllowed.toString().contains(configured.id()));
    }

    @Test
    public void oneTimeApprovalAndCancellationAreIdentityBoundAndNeverAutoResume() {
        ProviderDescriptor local = localOffline("local");
        ProviderDescriptor cloud = systemDefault("cloud");
        RecognitionRoute configured = downgradeRoute(local, cloud);
        ProviderRegistry registry = registry(local, cloud);
        EffectiveProfile profile = profile(configured.id(), FieldKind.GENERAL);
        RecognitionRouter first = new RecognitionRouter(
                configured,
                registry,
                profile,
                PrivacyAuthorization.requireConfirmation(profile),
                breaker());
        RecognitionRouter second = new RecognitionRouter(
                configured,
                registry,
                profile,
                PrivacyAuthorization.requireConfirmation(profile),
                breaker());

        ConfirmationRequired firstRequest = confirmation(first.onFailure(
                ready(first.start()),
                FailureClass.MODEL_MISSING));
        ConfirmationRequired secondRequest = confirmation(second.onFailure(
                ready(second.start()),
                FailureClass.MODEL_MISSING));
        assertIgnored(
                first.onConfirmation(
                        secondRequest.request(),
                        ConfirmationDecision.APPROVE_ONCE),
                IgnoreReason.STALE_CONFIRMATION);
        Attempt cloudAttempt = ready(first.onConfirmation(
                firstRequest.request(),
                ConfirmationDecision.APPROVE_ONCE));
        assertSame(cloud, cloudAttempt.descriptor());
        assertSame(
                privateField(firstRequest.request(), "lease"),
                privateField(cloudAttempt, "lease"));
        assertIgnored(
                first.onConfirmation(
                        firstRequest.request(),
                        ConfirmationDecision.APPROVE_ONCE),
                IgnoreReason.STALE_CONFIRMATION);

        assertRouteFailure(
                second.onConfirmation(
                        secondRequest.request(),
                        ConfirmationDecision.CANCEL),
                FailureClass.CANCELLED,
                FailureReason.CONFIRMATION_REJECTED);
        assertIgnored(second.start(), IgnoreReason.TERMINAL);
    }

    @Test
    public void approvalRevalidatesTheExactRegistryLeaseAndGenerationCapacity()
            throws Exception {
        ProviderDescriptor local = localOffline("local");
        ProviderDescriptor cloud = systemDefault("cloud");
        RecognitionRoute configured = downgradeRoute(local, cloud);
        ProviderRegistry registry = registry(local, cloud);
        EffectiveProfile profile = profile(configured.id(), FieldKind.GENERAL);
        RecognitionRouter changed = new RecognitionRouter(
                configured,
                registry,
                profile,
                PrivacyAuthorization.requireConfirmation(profile),
                breaker());
        ConfirmationRequired pending = confirmation(changed.onFailure(
                ready(changed.start()),
                FailureClass.MODEL_MISSING));
        assertEquals(EnableResult.UPDATED, registry.setEnabled(cloud.id(), false));
        assertEquals(EnableResult.UPDATED, registry.setEnabled(cloud.id(), true));
        assertRouteFailure(
                changed.onConfirmation(pending.request(), ConfirmationDecision.APPROVE_ONCE),
                FailureClass.UNAVAILABLE,
                FailureReason.PROVIDER_CHANGED);

        ProviderRegistry overflowRegistry = registry(local, cloud);
        RecognitionRouter exhausted = new RecognitionRouter(
                configured,
                overflowRegistry,
                profile,
                PrivacyAuthorization.requireConfirmation(profile),
                breaker());
        Field generation = RecognitionRouter.class.getDeclaredField("attemptGeneration");
        generation.setAccessible(true);
        generation.setLong(exhausted, Long.MAX_VALUE - 2L);
        ConfirmationRequired lastRequest = confirmation(exhausted.onFailure(
                ready(exhausted.start()),
                FailureClass.MODEL_MISSING));
        assertRouteFailure(
                exhausted.onConfirmation(
                        lastRequest.request(),
                        ConfirmationDecision.APPROVE_ONCE),
                FailureClass.INTERNAL_ERROR,
                FailureReason.GENERATION_EXHAUSTED);
    }

    @Test
    public void requireBeforeUseAlwaysPromptsEvenWithPublicPreauthorization() {
        ProviderDescriptor first = systemDefault("first");
        ProviderDescriptor second = systemDefault("second");
        RecognitionRoute configured = route(
                "route.authentication",
                List.of(
                        step(
                                first.id(),
                                PrivacyClass.PUBLIC_NETWORK,
                                Set.of(),
                                Set.of(FailureClass.AUTHENTICATION),
                                Set.of(),
                                ConfirmationPolicy.NOT_REQUIRED),
                        step(
                                second.id(),
                                PrivacyClass.PUBLIC_NETWORK,
                                Set.of(),
                                Set.of(),
                                Set.of(),
                                ConfirmationPolicy.REQUIRE_BEFORE_USE)),
                false);
        EffectiveProfile profile = profile(configured.id(), FieldKind.GENERAL);
        RecognitionRouter router = new RecognitionRouter(
                configured,
                registry(first, second),
                profile,
                PrivacyAuthorization.preauthorized(profile, PrivacyClass.PUBLIC_NETWORK),
                breaker());
        assertTrue(router.onFailure(ready(router.start()), FailureClass.AUTHENTICATION)
                instanceof ConfirmationRequired);
    }

    @Test
    public void staleForeignAndRegistryAbaCannotAdvanceOrCompleteTheRoute() {
        ProviderDescriptor primary = systemDefault("primary");
        ProviderDescriptor backup = systemDefault("backup");
        ProviderRegistry registry = registry(primary, backup);
        RecognitionRoute route = route(
                "route.aba",
                List.of(
                        step(
                                primary.id(),
                                PrivacyClass.PUBLIC_NETWORK,
                                Set.of(FailureClass.RECOGNIZER_BUSY),
                                Set.of(FailureClass.NETWORK_TIMEOUT),
                                Set.of(),
                                ConfirmationPolicy.NOT_REQUIRED),
                        step(
                                backup.id(),
                                PrivacyClass.PUBLIC_NETWORK,
                                Set.of(),
                                Set.of(),
                                Set.of(),
                                ConfirmationPolicy.NOT_REQUIRED)),
                false);
        RecognitionRouter firstRouter = router(route, registry);
        RecognitionRouter foreignRouter = router(route, registry);
        Attempt first = ready(firstRouter.start());
        Attempt foreign = ready(foreignRouter.start());
        assertIgnored(
                firstRouter.onFailure(foreign, FailureClass.NETWORK_TIMEOUT),
                IgnoreReason.STALE_ATTEMPT);
        assertTrue(firstRouter.isCurrent(first));

        Attempt retry = ready(firstRouter.onFailure(first, FailureClass.RECOGNIZER_BUSY));
        assertNotSame(first, retry);
        assertIgnored(
                firstRouter.onFailure(first, FailureClass.NETWORK_TIMEOUT),
                IgnoreReason.STALE_ATTEMPT);

        assertEquals(EnableResult.UPDATED, registry.setEnabled(primary.id(), false));
        assertEquals(EnableResult.UPDATED, registry.setEnabled(primary.id(), true));
        assertFalse(firstRouter.isCurrent(retry));
        assertRouteFailure(
                firstRouter.onSuccess(retry),
                FailureClass.UNAVAILABLE,
                FailureReason.PROVIDER_CHANGED);
        assertIgnored(firstRouter.onSuccess(retry), IgnoreReason.STALE_ATTEMPT);
    }

    @Test
    public void sharedCircuitOpensHalfOpensAndRecoversAcrossRouterInstances() {
        long[] now = {0L};
        ProviderCircuitBreaker shared = new ProviderCircuitBreaker(() -> now[0]);
        ProviderDescriptor provider = systemDefault("provider.shared-circuit");
        ProviderRegistry registry = registry(provider);
        RecognitionRoute configured = single(provider.id(), PrivacyClass.PUBLIC_NETWORK);
        EffectiveProfile profile = profile(configured.id(), FieldKind.GENERAL);

        for (int failure = 0; failure < ProviderCircuitBreaker.FAILURE_THRESHOLD; failure++) {
            RecognitionRouter router = router(configured, registry, profile, shared);
            assertRouteFailure(
                    router.onFailure(ready(router.start()), FailureClass.SERVER_ERROR),
                    FailureClass.SERVER_ERROR,
                    FailureReason.EXHAUSTED);
        }
        assertRouteFailure(
                router(configured, registry, profile, shared).start(),
                FailureClass.UNAVAILABLE,
                FailureReason.CIRCUIT_OPEN);

        now[0] = ProviderCircuitBreaker.OPEN_INTERVAL_MILLIS;
        RecognitionRouter halfOpen = router(configured, registry, profile, shared);
        Attempt probe = ready(halfOpen.start());
        assertRouteFailure(
                router(configured, registry, profile, shared).start(),
                FailureClass.UNAVAILABLE,
                FailureReason.CIRCUIT_OPEN);

        assertEquals(EnableResult.UPDATED, registry.setEnabled(provider.id(), false));
        assertEquals(EnableResult.UPDATED, registry.setEnabled(provider.id(), true));
        assertRouteFailure(
                halfOpen.onSuccess(probe),
                FailureClass.UNAVAILABLE,
                FailureReason.PROVIDER_CHANGED);
        now[0] += ProviderCircuitBreaker.OPEN_INTERVAL_MILLIS - 1L;
        assertRouteFailure(
                router(configured, registry, profile, shared).start(),
                FailureClass.UNAVAILABLE,
                FailureReason.CIRCUIT_OPEN);

        now[0]++;
        RecognitionRouter recovered = router(configured, registry, profile, shared);
        assertTrue(recovered.onSuccess(ready(recovered.start())) instanceof Completed);
        assertTrue(router(configured, registry, profile, shared).start()
                instanceof AttemptReady);
    }

    @Test
    public void successIsSingleTerminalAndDoesNotExposeTheProviderIdentity() {
        ProviderDescriptor provider = localOffline("local.secret");
        RecognitionRouter router = router(
                route(
                        "route.complete",
                        List.of(step(
                                provider.id(),
                                PrivacyClass.ON_DEVICE,
                                Set.of(),
                                Set.of(),
                                Set.of(ProviderCapability.ON_DEVICE),
                                ConfirmationPolicy.NOT_REQUIRED)),
                        false),
                registry(provider));
        Attempt attempt = ready(router.start());
        Object completed = router.onSuccess(attempt);
        assertTrue(completed instanceof Completed);
        assertFalse(completed.toString().contains(provider.id()));
        assertIgnored(router.onSuccess(attempt), IgnoreReason.STALE_ATTEMPT);
        assertIgnored(router.start(), IgnoreReason.TERMINAL);
    }

    @Test
    public void attemptGenerationExhaustionFailsClosedWithoutPublishingAnotherAttempt()
            throws Exception {
        ProviderDescriptor provider = systemDefault("provider");
        RecognitionRouter router = router(
                route(
                        "route.overflow",
                        List.of(step(
                                provider.id(),
                                PrivacyClass.PUBLIC_NETWORK,
                                Set.of(),
                                Set.of(),
                                Set.of(),
                                ConfirmationPolicy.NOT_REQUIRED)),
                        false),
                registry(provider));
        Field generation = RecognitionRouter.class.getDeclaredField("attemptGeneration");
        generation.setAccessible(true);
        generation.setLong(router, Long.MAX_VALUE);
        assertRouteFailure(
                router.start(),
                FailureClass.INTERNAL_ERROR,
                FailureReason.GENERATION_EXHAUSTED);
        assertIgnored(router.start(), IgnoreReason.TERMINAL);
    }

    private static RecognitionRoute single(String providerId, PrivacyClass privacyClass) {
        return route(
                "route.single",
                List.of(step(
                        providerId,
                        privacyClass,
                        Set.of(),
                        Set.of(),
                        privacyClass == PrivacyClass.ON_DEVICE
                                ? Set.of(ProviderCapability.ON_DEVICE)
                                : Set.of(),
                        ConfirmationPolicy.NOT_REQUIRED)),
                false);
    }

    private static RecognitionRouter router(
            RecognitionRoute route,
            ProviderRegistry registry) {
        EffectiveProfile profile = profile(route.id(), FieldKind.GENERAL);
        return router(route, registry, profile);
    }

    private static RecognitionRouter router(
            RecognitionRoute route,
            ProviderRegistry registry,
            EffectiveProfile profile) {
        return router(route, registry, profile, breaker());
    }

    private static RecognitionRouter router(
            RecognitionRoute route,
            ProviderRegistry registry,
            EffectiveProfile profile,
            ProviderCircuitBreaker circuitBreaker) {
        return new RecognitionRouter(
                route,
                registry,
                profile,
                PrivacyAuthorization.requireConfirmation(profile),
                circuitBreaker);
    }

    private static ProviderCircuitBreaker breaker() {
        return new ProviderCircuitBreaker(() -> 0L);
    }

    private static EffectiveProfile profile(String routeId, FieldKind fieldKind) {
        return resolveProfile(
                routeId,
                List.of(),
                inherited(),
                fieldKind);
    }

    private static EffectiveProfile sessionProfile(String routeId) {
        return resolveProfile(
                "route.default",
                List.of(),
                overrides(OverrideValue.value(routeId)),
                FieldKind.GENERAL);
    }

    private static EffectiveProfile appProfile(String routeId) {
        return resolveProfile(
                "route.default",
                List.of(new AppRule(
                        "com.example.target",
                        OverrideValue.value(routeId),
                        OverrideValue.inherit(),
                        OverrideValue.inherit(),
                        OverrideValue.inherit(),
                        OverrideValue.inherit())),
                inherited(),
                FieldKind.GENERAL);
    }

    private static EffectiveProfile resolveProfile(
            String globalRouteId,
            List<AppRule> appRules,
            RuleOverrides session,
            FieldKind fieldKind) {
        return EffectiveProfileResolver.resolve(new EffectiveProfileResolver.Request(
                new GlobalConfig(
                        GlobalConfig.FORMAT_VERSION,
                        new GlobalConfig.KeyboardConfig("latin.base"),
                        new GlobalConfig.VoiceConfig(OverrideValue.value(globalRouteId)),
                        new GlobalConfig.ProcessingConfig(
                                OverrideValue.value(ProcessingMode.EXACT)),
                        new GlobalConfig.PrivacyConfig(
                                OverrideValue.value(false),
                                OverrideValue.value(false)),
                        new GlobalConfig.AutomationConfig(OverrideValue.disabled())),
                new EffectiveProfileResolver.ProviderDefaults(
                        OverrideValue.value("route.provider"),
                        OverrideValue.value(ProcessingMode.EXACT),
                        OverrideValue.value(false),
                        OverrideValue.value(false),
                        OverrideValue.disabled()),
                appRules,
                List.of(),
                session,
                "com.example.target",
                fieldKind));
    }

    private static RuleOverrides inherited() {
        return overrides(OverrideValue.inherit());
    }

    private static RuleOverrides overrides(OverrideValue<String> voiceRouteId) {
        return new RuleOverrides(
                voiceRouteId,
                OverrideValue.inherit(),
                OverrideValue.inherit(),
                OverrideValue.inherit(),
                OverrideValue.inherit());
    }

    private static RecognitionRoute downgradeRoute(
            ProviderDescriptor local,
            ProviderDescriptor cloud) {
        return route(
                "route.downgrade.confirmation",
                List.of(
                        step(
                                local.id(),
                                PrivacyClass.ON_DEVICE,
                                Set.of(),
                                Set.of(FailureClass.MODEL_MISSING),
                                Set.of(ProviderCapability.ON_DEVICE),
                                ConfirmationPolicy.NOT_REQUIRED),
                        step(
                                cloud.id(),
                                PrivacyClass.PUBLIC_NETWORK,
                                Set.of(),
                                Set.of(),
                                Set.of(),
                                ConfirmationPolicy.REQUIRE_ON_PRIVACY_DOWNGRADE)),
                true);
    }

    private static RecognitionRoute threeTierRoute(
            ProviderDescriptor local,
            ProviderDescriptor lan,
            ProviderDescriptor cloud) {
        return route(
                "route.three-tier",
                List.of(
                        step(
                                local.id(),
                                PrivacyClass.ON_DEVICE,
                                Set.of(),
                                Set.of(FailureClass.MODEL_MISSING),
                                Set.of(ProviderCapability.ON_DEVICE),
                                ConfirmationPolicy.NOT_REQUIRED),
                        step(
                                lan.id(),
                                PrivacyClass.LOCAL_NETWORK,
                                Set.of(),
                                Set.of(FailureClass.NETWORK_TIMEOUT),
                                Set.of(),
                                ConfirmationPolicy.REQUIRE_ON_PRIVACY_DOWNGRADE),
                        step(
                                cloud.id(),
                                PrivacyClass.PUBLIC_NETWORK,
                                Set.of(),
                                Set.of(),
                                Set.of(),
                                ConfirmationPolicy.REQUIRE_ON_PRIVACY_DOWNGRADE)),
                true);
    }

    private static RecognitionRoute route(
            String id,
            List<RouteStep> steps,
            boolean allowPrivacyDowngrade) {
        return new RecognitionRoute(
                id,
                steps,
                PrivacyClass.PUBLIC_NETWORK,
                allowPrivacyDowngrade);
    }

    private static RouteStep step(
            String providerId,
            PrivacyClass privacyClass,
            Set<FailureClass> retryOn,
            Set<FailureClass> fallbackOn,
            Set<ProviderCapability> required,
            ConfirmationPolicy confirmation) {
        return new RouteStep(
                providerId,
                privacyClass,
                new RetryPolicy(retryOn.isEmpty() ? 1 : 2, retryOn),
                fallbackOn,
                required,
                confirmation);
    }

    private static ProviderDescriptor systemDefault(String id) {
        return new ProviderDescriptor(
                id,
                "System provider",
                ProviderCapabilities.declaredForBackend(RecognitionBackend.SYSTEM_DEFAULT));
    }

    private static ProviderDescriptor localOffline(String id) {
        return new ProviderDescriptor(
                id,
                "Local provider",
                ProviderCapabilities.declaredForBackend(RecognitionBackend.LOCAL_OFFLINE));
    }

    private static ProviderDescriptor lanProvider(String id) {
        return new ProviderDescriptor(
                id,
                "LAN provider",
                new ProviderCapabilities(
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        true,
                        ProviderCapabilities.ImplementationKind.BATCH_FINAL,
                        PrivacyClass.LOCAL_NETWORK,
                        ProviderCapabilities.APP_CAPTURE_LIMIT_MS,
                        Set.of(ProviderCapabilities.AudioFormat.PCM_16_MONO_16000_HZ)));
    }

    private static ProviderRegistry registry(ProviderDescriptor... descriptors) {
        ProviderRegistry registry = new ProviderRegistry();
        for (ProviderDescriptor descriptor : descriptors) register(registry, descriptor, true);
        return registry;
    }

    private static void register(
            ProviderRegistry registry,
            ProviderDescriptor descriptor,
            boolean enabled) {
        assertEquals(
                RegistrationResult.REGISTERED,
                registry.register(
                        descriptor,
                        () -> new ObservedAvailable(descriptor.capabilities()),
                        enabled));
    }

    private static Attempt ready(Object decision) {
        assertTrue(String.valueOf(decision), decision instanceof AttemptReady);
        return ((AttemptReady) decision).attempt();
    }

    private static ConfirmationRequired confirmation(Object decision) {
        assertTrue(String.valueOf(decision), decision instanceof ConfirmationRequired);
        return (ConfirmationRequired) decision;
    }

    private static void assertRouteFailure(
            Object decision,
            FailureClass failureClass,
            FailureReason reason) {
        assertTrue(String.valueOf(decision), decision instanceof RouteFailed);
        RouteFailed failed = (RouteFailed) decision;
        assertEquals(failureClass, failed.failureClass());
        assertEquals(reason, failed.reason());
    }

    private static void assertIgnored(Object decision, IgnoreReason reason) {
        assertTrue(String.valueOf(decision), decision instanceof Ignored);
        assertEquals(reason, ((Ignored) decision).reason());
    }

    private static Object privateField(Object owner, String name) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }
}
