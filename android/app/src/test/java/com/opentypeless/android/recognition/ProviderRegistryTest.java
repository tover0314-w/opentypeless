package com.opentypeless.android.recognition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.config.RecognitionRoute;
import com.opentypeless.android.recognition.ProviderRegistry.AccessFailure;
import com.opentypeless.android.recognition.ProviderRegistry.EnableResult;
import com.opentypeless.android.recognition.ProviderRegistry.LookupFound;
import com.opentypeless.android.recognition.ProviderRegistry.LookupRejected;
import com.opentypeless.android.recognition.ProviderRegistry.ObservedAvailable;
import com.opentypeless.android.recognition.ProviderRegistry.ObservedUnavailable;
import com.opentypeless.android.recognition.ProviderRegistry.ProbeAvailable;
import com.opentypeless.android.recognition.ProviderRegistry.ProbeRejected;
import com.opentypeless.android.recognition.ProviderRegistry.ProbeUnavailable;
import com.opentypeless.android.recognition.ProviderRegistry.RegistrationResult;
import com.opentypeless.android.recognition.ProviderRegistry.RouteLease;
import com.opentypeless.android.recognition.ProviderRegistry.RouteLeaseFound;
import com.opentypeless.android.recognition.ProviderRegistry.RouteLeaseRejected;
import com.opentypeless.android.settings.RecognitionBackend;

import org.junit.Test;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class ProviderRegistryTest {
    @Test
    public void registrySurfaceIsPackageConfinedBoundedAndHasOneProbeCapabilityOwner() {
        assertTrue(Modifier.isFinal(ProviderRegistry.class.getModifiers()));
        assertFalse(Modifier.isPublic(ProviderRegistry.class.getModifiers()));
        assertFalse(Serializable.class.isAssignableFrom(ProviderRegistry.class));
        assertEquals(32, ProviderRegistry.MAX_PROVIDERS);
        assertEquals(
                Set.of("entries", "generation", "LOOKUP_ID_PATTERN", "MAX_PROVIDERS"),
                Arrays.stream(ProviderRegistry.class.getDeclaredFields())
                        .map(Field::getName)
                        .collect(java.util.stream.Collectors.toSet()));

        Method probe = Arrays.stream(ProviderRegistry.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("probe"))
                .findFirst()
                .orElseThrow();
        assertFalse(Modifier.isSynchronized(probe.getModifiers()));
        for (String methodName : Set.of(
                "register", "setEnabled", "lookup", "routeLease", "isCurrent", "size",
                "enabledCount", "toString")) {
            Method method = Arrays.stream(ProviderRegistry.class.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(methodName))
                    .findFirst()
                    .orElseThrow();
            assertTrue(methodName, Modifier.isSynchronized(method.getModifiers()));
        }
    }

    @Test
    public void routeLeaseIsOpaqueRedactedAndRejectsForeignOrEnablementAba() {
        String secret = "provider-secret-sentinel";
        ProviderCapabilities capabilities = capabilities(RecognitionBackend.SYSTEM_DEFAULT);
        ProviderDescriptor canonical = new ProviderDescriptor("system", secret, capabilities);
        ProviderRegistry registry = new ProviderRegistry();
        assertEquals(
                RegistrationResult.REGISTERED,
                registry.register(
                        canonical,
                        () -> new ObservedAvailable(capabilities),
                        true));
        Object found = registry.routeLease("system");
        assertTrue(found instanceof RouteLeaseFound);
        RouteLease first = ((RouteLeaseFound) found).lease();
        assertTrue(first.descriptor() == canonical);
        assertTrue(registry.isCurrent(first));

        ProviderRegistry foreign = new ProviderRegistry();
        assertFalse(foreign.isCurrent(first));
        assertEquals(EnableResult.UPDATED, registry.setEnabled("system", false));
        assertFalse(registry.isCurrent(first));
        Object disabled = registry.routeLease("system");
        assertTrue(disabled instanceof RouteLeaseRejected);
        assertEquals(
                AccessFailure.PROVIDER_DISABLED,
                ((RouteLeaseRejected) disabled).failure());
        assertEquals(EnableResult.UPDATED, registry.setEnabled("system", true));
        RouteLease second = ((RouteLeaseFound) registry.routeLease("system")).lease();
        assertFalse(registry.isCurrent(first));
        assertTrue(registry.isCurrent(second));
        assertFalse(second.toString().contains(secret));
        assertFalse(second.toString().contains("system"));
        assertFalse(found.toString().contains(secret));
    }

    @Test
    public void registerIsExactIdBoundedAndNeverSilentlyReplacesDuplicates() {
        ProviderRegistry registry = new ProviderRegistry();
        ProviderCapabilities capabilities = capabilities(RecognitionBackend.LOCAL_OFFLINE);
        ProviderDescriptor first = descriptor("p0", capabilities);
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger duplicateCalls = new AtomicInteger();

        assertEquals(
                RegistrationResult.REGISTERED,
                registry.register(first, () -> {
                    firstCalls.incrementAndGet();
                    return new ObservedAvailable(capabilities);
                }, true));
        assertEquals(
                RegistrationResult.DUPLICATE_ID,
                registry.register(first, () -> {
                    duplicateCalls.incrementAndGet();
                    return new ObservedAvailable(capabilities);
                }, true));
        assertEquals(1, registry.size());
        assertTrue(registry.lookup("p0") instanceof LookupFound);
        assertTrue(registry.probe("p0") instanceof ProbeAvailable);
        assertEquals(1, firstCalls.get());
        assertEquals(0, duplicateCalls.get());

        ProviderRegistry full = new ProviderRegistry();
        for (int index = 0; index < ProviderRegistry.MAX_PROVIDERS; index++) {
            ProviderDescriptor descriptor = descriptor("p" + index, capabilities);
            assertEquals(
                    RegistrationResult.REGISTERED,
                    full.register(
                            descriptor,
                            () -> new ObservedAvailable(capabilities),
                            index % 2 == 0));
        }
        assertEquals(
                RegistrationResult.CAPACITY_EXCEEDED,
                full.register(
                        descriptor("overflow", capabilities),
                        () -> new ObservedAvailable(capabilities),
                        true));
        assertEquals(ProviderRegistry.MAX_PROVIDERS, full.size());
        assertEquals(ProviderRegistry.MAX_PROVIDERS / 2, full.enabledCount());
    }

    @Test
    public void unknownMalformedAndDisabledIdsAreClassifiedBeforeProbe() {
        ProviderRegistry registry = new ProviderRegistry();
        ProviderCapabilities capabilities = capabilities(RecognitionBackend.SYSTEM_DEFAULT);
        AtomicInteger calls = new AtomicInteger();
        assertEquals(
                RegistrationResult.REGISTERED,
                registry.register(
                        descriptor("system", capabilities),
                        () -> {
                            calls.incrementAndGet();
                            return new ObservedAvailable(capabilities);
                        },
                        false));

        for (String id : new String[]{null, "", "UPPER", "a".repeat(129), "missing"}) {
            assertLookupFailure(registry.lookup(id), AccessFailure.UNKNOWN_PROVIDER);
            assertProbeFailure(registry.probe(id), AccessFailure.UNKNOWN_PROVIDER);
        }
        assertLookupFailure(registry.lookup("system"), AccessFailure.PROVIDER_DISABLED);
        assertProbeFailure(registry.probe("system"), AccessFailure.PROVIDER_DISABLED);
        assertEquals(0, calls.get());

        assertEquals(EnableResult.UNKNOWN_PROVIDER, registry.setEnabled("missing", true));
        assertEquals(EnableResult.UPDATED, registry.setEnabled("system", true));
        assertEquals(EnableResult.UNCHANGED, registry.setEnabled("system", true));
        assertTrue(registry.lookup("system") instanceof LookupFound);
        assertTrue(registry.probe("system") instanceof ProbeAvailable);
        assertEquals(1, calls.get());
    }

    @Test
    public void probeRequiresExactDeclaredCapabilitiesAndStableFailureVocabulary() {
        ProviderRegistry registry = new ProviderRegistry();
        ProviderCapabilities declared = capabilities(RecognitionBackend.LOCAL_OFFLINE);
        ProviderCapabilities different = capabilities(RecognitionBackend.SYSTEM_DEFAULT);
        assertEquals(
                RegistrationResult.REGISTERED,
                registry.register(
                        descriptor("mismatch", declared),
                        () -> new ObservedAvailable(different),
                        true));
        assertProbeFailure(
                registry.probe("mismatch"), AccessFailure.CAPABILITY_MISMATCH);

        ProviderRegistry unavailable = new ProviderRegistry();
        assertEquals(
                RegistrationResult.REGISTERED,
                unavailable.register(
                        descriptor("offline", declared),
                        () -> new ObservedUnavailable(
                                RecognitionRoute.FailureClass.MODEL_MISSING),
                        true));
        Object result = unavailable.probe("offline");
        assertTrue(result instanceof ProbeUnavailable);
        assertEquals(
                RecognitionRoute.FailureClass.MODEL_MISSING,
                ((ProbeUnavailable) result).failureClass());

        for (RecognitionRoute.FailureClass sessionOnly : new RecognitionRoute.FailureClass[]{
                RecognitionRoute.FailureClass.NO_MATCH,
                RecognitionRoute.FailureClass.SPEECH_TIMEOUT,
                RecognitionRoute.FailureClass.CANCELLED,
                RecognitionRoute.FailureClass.TARGET_CHANGED}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new ObservedUnavailable(sessionOnly));
        }
    }

    @Test
    public void nullAndThrowingProbesFailClosedWithoutLeakingProviderText() {
        String secret = "provider-secret-sentinel";
        ProviderCapabilities capabilities = capabilities(RecognitionBackend.OPENAI_COMPATIBLE);
        ProviderRegistry throwing = new ProviderRegistry();
        throwing.register(
                new ProviderDescriptor("cloud", secret, capabilities),
                () -> {
                    throw new IllegalStateException(secret);
                },
                true);
        Object thrownResult = throwing.probe("cloud");
        assertProbeFailure(thrownResult, AccessFailure.PROBE_FAILED);

        ProviderRegistry nullResult = new ProviderRegistry();
        nullResult.register(
                new ProviderDescriptor("nullprobe", secret, capabilities),
                () -> null,
                true);
        Object nullProbe = nullResult.probe("nullprobe");
        assertProbeFailure(nullProbe, AccessFailure.PROBE_FAILED);

        for (Object diagnostic : new Object[]{throwing, thrownResult, nullResult, nullProbe}) {
            String text = diagnostic.toString();
            assertFalse(text, text.contains(secret));
            assertFalse(text, text.contains("cloud"));
            assertFalse(text, text.contains("nullprobe"));
        }
    }

    @Test
    public void probeReentrancyAndConcurrentEnableAbaInvalidateTheLease() throws Exception {
        ProviderCapabilities capabilities = capabilities(RecognitionBackend.SYSTEM_ON_DEVICE);
        ProviderRegistry reentrant = new ProviderRegistry();
        reentrant.register(
                descriptor("reentrant", capabilities),
                () -> {
                    assertEquals(
                            EnableResult.UPDATED,
                            reentrant.setEnabled("reentrant", false));
                    return new ObservedAvailable(capabilities);
                },
                true);
        assertProbeFailure(
                reentrant.probe("reentrant"), AccessFailure.PROVIDER_DISABLED);

        ProviderRegistry concurrent = new ProviderRegistry();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        concurrent.register(
                descriptor("concurrent", capabilities),
                () -> {
                    entered.countDown();
                    await(release);
                    return new ObservedAvailable(capabilities);
                },
                true);
        AtomicReference<Object> result = new AtomicReference<>();
        Thread worker = new Thread(() -> result.set(concurrent.probe("concurrent")));
        worker.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertEquals(EnableResult.UPDATED, concurrent.setEnabled("concurrent", false));
        assertEquals(EnableResult.UPDATED, concurrent.setEnabled("concurrent", true));
        release.countDown();
        worker.join(5_000L);
        assertFalse(worker.isAlive());
        assertProbeFailure(result.get(), AccessFailure.PROVIDER_CHANGED);
    }

    @Test
    public void generationOverflowFailsBeforeRegistrationOrEnableMutation() throws Exception {
        ProviderCapabilities capabilities = capabilities(RecognitionBackend.LOCAL_OFFLINE);
        ProviderRegistry registration = new ProviderRegistry();
        setGeneration(registration, Long.MAX_VALUE);
        assertThrows(
                IllegalStateException.class,
                () -> registration.register(
                        descriptor("overflow", capabilities),
                        () -> new ObservedAvailable(capabilities),
                        true));
        assertEquals(0, registration.size());

        ProviderRegistry enabling = new ProviderRegistry();
        enabling.register(
                descriptor("enabled", capabilities),
                () -> new ObservedAvailable(capabilities),
                true);
        setGeneration(enabling, Long.MAX_VALUE);
        assertThrows(
                IllegalStateException.class,
                () -> enabling.setEnabled("enabled", false));
        assertTrue(enabling.lookup("enabled") instanceof LookupFound);
        assertEquals(1, enabling.enabledCount());
    }

    @Test
    public void lookupAndProbeReturnTheCanonicalRegisteredDescriptorOnly() {
        ProviderRegistry registry = new ProviderRegistry();
        ProviderDescriptor canonical = ProviderDescriptor.declaredForBackend(
                RecognitionBackend.DASHSCOPE_STREAMING);
        assertEquals(
                RegistrationResult.REGISTERED,
                registry.register(
                        canonical,
                        () -> new ObservedAvailable(canonical.capabilities()),
                        true));

        Object lookup = registry.lookup(canonical.id());
        Object probe = registry.probe(canonical.id());
        assertTrue(lookup instanceof LookupFound);
        assertTrue(probe instanceof ProbeAvailable);
        assertTrue(canonical == ((LookupFound) lookup).descriptor());
        assertTrue(canonical == ((ProbeAvailable) probe).descriptor());
        assertNotNull(canonical.capabilities());
    }

    private static ProviderCapabilities capabilities(RecognitionBackend backend) {
        return ProviderCapabilities.declaredForBackend(backend);
    }

    private static ProviderDescriptor descriptor(
            String id, ProviderCapabilities capabilities) {
        return new ProviderDescriptor(id, "Provider " + id, capabilities);
    }

    private static void assertLookupFailure(Object result, AccessFailure expected) {
        assertTrue(result instanceof LookupRejected);
        assertEquals(expected, ((LookupRejected) result).failure());
    }

    private static void assertProbeFailure(Object result, AccessFailure expected) {
        assertTrue(String.valueOf(result), result instanceof ProbeRejected);
        assertEquals(expected, ((ProbeRejected) result).failure());
    }

    private static void setGeneration(ProviderRegistry registry, long value) throws Exception {
        Field generation = ProviderRegistry.class.getDeclaredField("generation");
        generation.setAccessible(true);
        generation.setLong(registry, value);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for deterministic probe race");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError("probe race interrupted");
        }
    }
}
