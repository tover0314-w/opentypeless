package com.opentypeless.android.recognition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.config.RecognitionRoute.FailureClass;
import com.opentypeless.android.recognition.ProviderCircuitBreaker.AcquireResult;
import com.opentypeless.android.recognition.ProviderCircuitBreaker.Disposition;
import com.opentypeless.android.recognition.ProviderCircuitBreaker.Permit;
import com.opentypeless.android.recognition.ProviderCircuitBreaker.PermitGranted;
import com.opentypeless.android.recognition.ProviderCircuitBreaker.PermitRejected;
import com.opentypeless.android.recognition.ProviderCircuitBreaker.RejectionReason;
import com.opentypeless.android.settings.RecognitionBackend;

import org.junit.Test;

import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.EnumSet;

public final class ProviderCircuitBreakerTest {
    @Test
    public void threeConsecutiveHealthFailuresOpenForExactlyThirtySeconds() {
        FakeClock clock = new FakeClock(1_000L);
        ProviderCircuitBreaker breaker = new ProviderCircuitBreaker(clock);
        ProviderDescriptor provider = provider("provider.threshold");

        assertEquals(Disposition.RECORDED, fail(breaker, provider, FailureClass.SERVER_ERROR));
        assertEquals(Disposition.RECORDED, fail(breaker, provider, FailureClass.SERVER_ERROR));
        assertEquals(Disposition.OPENED, fail(breaker, provider, FailureClass.SERVER_ERROR));

        clock.now = 30_999L;
        assertRejected(breaker.acquire(provider), RejectionReason.OPEN);
        clock.now = 31_000L;
        Permit halfOpen = granted(breaker.acquire(provider));
        assertRejected(breaker.acquire(provider), RejectionReason.HALF_OPEN_BUSY);
        assertEquals(Disposition.RECOVERED, breaker.onSuccess(halfOpen));
        assertTrue(breaker.acquire(provider) instanceof PermitGranted);
    }

    @Test
    public void halfOpenFailureReopensAndOnlyOneProbeCanResolveIt() {
        FakeClock clock = new FakeClock(0L);
        ProviderCircuitBreaker breaker = openedBreaker(clock, provider("provider.half-open"));
        ProviderDescriptor provider = providerFromBreaker(breaker);

        clock.now = ProviderCircuitBreaker.OPEN_INTERVAL_MILLIS;
        Permit firstProbe = granted(breaker.acquire(provider));
        assertRejected(breaker.acquire(provider), RejectionReason.HALF_OPEN_BUSY);
        assertEquals(
                Disposition.REOPENED,
                breaker.onFailure(firstProbe, FailureClass.NETWORK_TIMEOUT));
        assertEquals(Disposition.IGNORED_STALE, breaker.onSuccess(firstProbe));

        clock.now += ProviderCircuitBreaker.OPEN_INTERVAL_MILLIS - 1L;
        assertRejected(breaker.acquire(provider), RejectionReason.OPEN);
        clock.now++;
        Permit secondProbe = granted(breaker.acquire(provider));
        assertEquals(Disposition.RECOVERED, breaker.onSuccess(secondProbe));
        assertEquals(Disposition.IGNORED_STALE, breaker.onSuccess(secondProbe));
    }

    @Test
    public void noMatchAndSpeechTimeoutRecoverWhileUserAndPolicyEventsNeverCount() {
        FakeClock clock = new FakeClock(0L);
        ProviderCircuitBreaker breaker = new ProviderCircuitBreaker(clock);
        ProviderDescriptor provider = provider("provider.classification");

        assertEquals(Disposition.RECORDED, fail(breaker, provider, FailureClass.SERVER_ERROR));
        assertEquals(Disposition.RECORDED, fail(breaker, provider, FailureClass.SERVER_ERROR));
        for (FailureClass neutral : EnumSet.of(
                FailureClass.PERMISSION_DENIED,
                FailureClass.UNSUPPORTED_LANGUAGE,
                FailureClass.CANCELLED,
                FailureClass.TARGET_CHANGED)) {
            assertEquals(Disposition.IGNORED_NON_HEALTH, fail(breaker, provider, neutral));
        }
        assertEquals(Disposition.RECOVERED, fail(breaker, provider, FailureClass.NO_MATCH));
        assertEquals(Disposition.RECORDED, fail(breaker, provider, FailureClass.SERVER_ERROR));
        assertEquals(
                Disposition.RECOVERED,
                fail(breaker, provider, FailureClass.SPEECH_TIMEOUT));

        assertEquals(Disposition.RECORDED, fail(breaker, provider, FailureClass.SERVER_ERROR));
        assertEquals(Disposition.RECORDED, fail(breaker, provider, FailureClass.SERVER_ERROR));
        assertEquals(Disposition.OPENED, fail(breaker, provider, FailureClass.SERVER_ERROR));
        clock.now = ProviderCircuitBreaker.OPEN_INTERVAL_MILLIS;
        Permit probe = granted(breaker.acquire(provider));
        assertEquals(
                Disposition.REOPENED,
                breaker.onFailure(probe, FailureClass.CANCELLED));
    }

    @Test
    public void allHealthFailuresCountAndAHealthySuccessResetsTheSequence() {
        FakeClock clock = new FakeClock(0L);
        ProviderCircuitBreaker breaker = new ProviderCircuitBreaker(clock);
        ProviderDescriptor provider = provider("provider.health-table");
        EnumSet<FailureClass> healthFailures = EnumSet.of(
                FailureClass.UNAVAILABLE,
                FailureClass.MODEL_MISSING,
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
                FailureClass.INTERNAL_ERROR);
        for (FailureClass failure : healthFailures) {
            assertEquals(Disposition.RECORDED, fail(breaker, provider, failure));
            Permit success = granted(breaker.acquire(provider));
            assertEquals(Disposition.RECOVERED, breaker.onSuccess(success));
        }
        assertEquals(Disposition.RECORDED, fail(breaker, provider, FailureClass.SERVER_ERROR));
        assertEquals(Disposition.RECORDED, fail(breaker, provider, FailureClass.SERVER_ERROR));
        assertEquals(Disposition.OPENED, fail(breaker, provider, FailureClass.SERVER_ERROR));
    }

    @Test
    public void staleForeignAndAbandonedPermitsCannotMutateAnotherState() {
        FakeClock clock = new FakeClock(0L);
        ProviderCircuitBreaker first = new ProviderCircuitBreaker(clock);
        ProviderCircuitBreaker second = new ProviderCircuitBreaker(clock);
        ProviderDescriptor provider = provider("provider.identity");
        Permit firstPermit = granted(first.acquire(provider));
        Permit secondPermit = granted(second.acquire(provider));

        assertEquals(Disposition.IGNORED_STALE, first.onSuccess(secondPermit));
        assertEquals(Disposition.RECOVERED, first.onSuccess(firstPermit));
        assertEquals(
                Disposition.IGNORED_STALE,
                first.onFailure(firstPermit, FailureClass.SERVER_ERROR));
        assertEquals(Disposition.IGNORED_STALE, first.abandon(secondPermit));

        ProviderDescriptor equalButForeign = new ProviderDescriptor(
                provider.id(), provider.displayName(), provider.capabilities());
        assertNotSame(provider, equalButForeign);
        assertTrue(first.acquire(equalButForeign) instanceof PermitGranted);
        assertEquals(2, first.size());

        ProviderDescriptor duplicateProvider = provider("provider.duplicate-permit");
        Permit duplicate = granted(first.acquire(duplicateProvider));
        assertEquals(
                Disposition.RECORDED,
                first.onFailure(duplicate, FailureClass.SERVER_ERROR));
        assertEquals(
                Disposition.IGNORED_STALE,
                first.onFailure(duplicate, FailureClass.SERVER_ERROR));
        assertEquals(
                Disposition.RECORDED,
                fail(first, duplicateProvider, FailureClass.SERVER_ERROR));
        assertEquals(
                Disposition.OPENED,
                fail(first, duplicateProvider, FailureClass.SERVER_ERROR));
    }

    @Test
    public void boundedIdentityMapRejectsTheThirtyThirdCanonicalProvider() {
        ProviderCircuitBreaker breaker = new ProviderCircuitBreaker(() -> 0L);
        for (int index = 0; index < ProviderCircuitBreaker.MAX_PROVIDERS; index++) {
            assertTrue(breaker.acquire(provider("provider.capacity-" + index))
                    instanceof PermitGranted);
        }
        assertEquals(ProviderCircuitBreaker.MAX_PROVIDERS, breaker.size());
        assertRejected(
                breaker.acquire(provider("provider.capacity-overflow")),
                RejectionReason.CAPACITY_EXCEEDED);
        assertEquals(ProviderCircuitBreaker.MAX_PROVIDERS, breaker.size());
    }

    @Test
    public void invalidClockAndTimerOverflowFailClosedWithoutLeakingClockErrors() {
        FakeClock clock = new FakeClock(100L);
        ProviderCircuitBreaker breaker = new ProviderCircuitBreaker(clock);
        ProviderDescriptor first = provider("provider.clock-backward");
        assertTrue(breaker.acquire(first) instanceof PermitGranted);
        clock.now = 99L;
        assertRejected(
                breaker.acquire(provider("provider.clock-second")),
                RejectionReason.CLOCK_INVALID);

        RuntimeException secret = new IllegalStateException("clock.secret.provider");
        clock.failure = secret;
        assertRejected(
                breaker.acquire(provider("provider.clock-throw")),
                RejectionReason.CLOCK_INVALID);
        assertFalse(breaker.toString().contains(secret.getMessage()));

        FakeClock overflowClock = new FakeClock(Long.MAX_VALUE - 1L);
        ProviderCircuitBreaker overflow = new ProviderCircuitBreaker(overflowClock);
        ProviderDescriptor overflowProvider = provider("provider.timer-overflow");
        assertEquals(
                Disposition.RECORDED,
                fail(overflow, overflowProvider, FailureClass.SERVER_ERROR));
        assertEquals(
                Disposition.RECORDED,
                fail(overflow, overflowProvider, FailureClass.SERVER_ERROR));
        assertEquals(
                Disposition.CLOCK_INVALID,
                fail(overflow, overflowProvider, FailureClass.SERVER_ERROR));
        assertRejected(overflow.acquire(overflowProvider), RejectionReason.CLOCK_INVALID);
    }

    @Test
    public void epochExhaustionIsPermanentAndCannotReuseTheLastPermit() throws Exception {
        ProviderCircuitBreaker breaker = new ProviderCircuitBreaker(() -> 0L);
        ProviderDescriptor provider = provider("provider.epoch-overflow");
        Permit permit = granted(breaker.acquire(provider));
        Object entry = privateField(permit, "entry");
        Field epoch = entry.getClass().getDeclaredField("epoch");
        epoch.setAccessible(true);
        epoch.setLong(entry, Long.MAX_VALUE);

        Permit lastPermit = granted(breaker.acquire(provider));
        assertEquals(Disposition.IGNORED_STALE, breaker.onSuccess(permit));
        assertEquals(Disposition.GENERATION_EXHAUSTED, breaker.onSuccess(lastPermit));
        assertRejected(
                breaker.acquire(provider),
                RejectionReason.GENERATION_EXHAUSTED);
        assertEquals(
                Disposition.IGNORED_STALE,
                breaker.onFailure(permit, FailureClass.SERVER_ERROR));
    }

    @Test
    public void shapeAndDiagnosticsStayProcessLocalBoundedAndContentFree() {
        assertTrue(Modifier.isFinal(ProviderCircuitBreaker.class.getModifiers()));
        assertFalse(Modifier.isPublic(ProviderCircuitBreaker.class.getModifiers()));
        assertFalse(Serializable.class.isAssignableFrom(ProviderCircuitBreaker.class));
        assertFalse(Serializable.class.isAssignableFrom(Permit.class));
        for (Constructor<?> constructor : Permit.class.getDeclaredConstructors()) {
            assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        }

        ProviderCircuitBreaker breaker = new ProviderCircuitBreaker(() -> 0L);
        ProviderDescriptor provider = new ProviderDescriptor(
                "provider.private-id",
                "Private Provider Name",
                ProviderCapabilities.declaredForBackend(RecognitionBackend.SYSTEM_DEFAULT));
        Permit permit = granted(breaker.acquire(provider));
        String rendered = breaker + " " + permit + " " + new PermitGranted(permit);
        assertFalse(rendered.contains(provider.id()));
        assertFalse(rendered.contains(provider.displayName()));
        assertTrue(rendered.contains("<redacted>"));
    }

    private static ProviderCircuitBreaker openedBreaker(
            FakeClock clock,
            ProviderDescriptor provider) {
        ProviderCircuitBreaker breaker = new ProviderCircuitBreaker(clock);
        assertEquals(Disposition.RECORDED, fail(breaker, provider, FailureClass.SERVER_ERROR));
        assertEquals(Disposition.RECORDED, fail(breaker, provider, FailureClass.SERVER_ERROR));
        assertEquals(Disposition.OPENED, fail(breaker, provider, FailureClass.SERVER_ERROR));
        return breaker;
    }

    private static ProviderDescriptor providerFromBreaker(ProviderCircuitBreaker breaker) {
        try {
            Object entries = privateField(breaker, "entries");
            return (ProviderDescriptor) ((java.util.Map<?, ?>) entries).keySet().iterator().next();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Disposition fail(
            ProviderCircuitBreaker breaker,
            ProviderDescriptor provider,
            FailureClass failure) {
        return breaker.onFailure(granted(breaker.acquire(provider)), failure);
    }

    private static Permit granted(AcquireResult result) {
        assertTrue(result.toString(), result instanceof PermitGranted);
        return ((PermitGranted) result).permit();
    }

    private static void assertRejected(AcquireResult result, RejectionReason reason) {
        assertTrue(result.toString(), result instanceof PermitRejected);
        assertEquals(reason, ((PermitRejected) result).reason());
    }

    private static ProviderDescriptor provider(String id) {
        return new ProviderDescriptor(
                id,
                "Provider",
                ProviderCapabilities.declaredForBackend(RecognitionBackend.SYSTEM_DEFAULT));
    }

    private static Object privateField(Object target, String fieldName)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static final class FakeClock implements ProviderCircuitBreaker.MonotonicClock {
        private long now;
        private RuntimeException failure;

        private FakeClock(long now) {
            this.now = now;
        }

        @Override
        public long nowMillis() {
            if (failure != null) throw failure;
            return now;
        }
    }
}
