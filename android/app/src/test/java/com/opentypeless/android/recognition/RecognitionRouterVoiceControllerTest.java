package com.opentypeless.android.recognition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.config.EffectiveProfile;
import com.opentypeless.android.config.EffectiveProfileResolver;
import com.opentypeless.android.config.GlobalConfig;
import com.opentypeless.android.config.OverrideValue;
import com.opentypeless.android.config.RuleOverrides;
import com.opentypeless.android.context.FieldKind;
import com.opentypeless.android.context.InputContext;
import com.opentypeless.android.data.PersonalizationSnapshot;
import com.opentypeless.android.ime.DictationRequest;
import com.opentypeless.android.ime.DictationResult;
import com.opentypeless.android.ime.TranscriptUpdate;
import com.opentypeless.android.ime.VoiceController;
import com.opentypeless.android.settings.AppSettings;
import com.opentypeless.android.settings.ProcessingMode;
import com.opentypeless.android.settings.RecognitionBackend;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RecognitionRouterVoiceControllerTest {
    @Test
    public void allLegacyBackendsCrossAnExactRouterAttemptBeforeDelegateExecution() {
        for (RecognitionBackend backend : RecognitionBackend.values()) {
            FakeDelegate delegate = new FakeDelegate();
            RecordingEvents events = new RecordingEvents();
            RecognitionRouterVoiceController controller = controller(
                    delegate,
                    prepared(backend, profile(routeId(backend), FieldKind.GENERAL)));
            DictationRequest request = request(backend, FieldKind.GENERAL);

            assertTrue(controller.start(request, events));
            assertEquals(1, delegate.startCalls);
            assertSame(request, delegate.request);
            assertSame(VoiceController.State.RECORDING, controller.state());

            DictationResult result = result(backend);
            delegate.emitResult(result);
            assertSame(result, events.result);
            assertSame(VoiceController.State.IDLE, controller.state());
            assertNull(events.error);
        }
    }

    @Test
    public void disabledMismatchedUnavailableAndCapabilityDriftNeverOpenTheDelegate() {
        RecognitionBackend backend = RecognitionBackend.SYSTEM_DEFAULT;
        Object[][] cases = {
                {profile(routeId(backend), FieldKind.SENSITIVE), available(backend)},
                {profile("route.wrong", FieldKind.GENERAL), available(backend)},
                {profile(routeId(backend), FieldKind.GENERAL),
                        new ProviderRegistry.ObservedUnavailable(
                                com.opentypeless.android.config.RecognitionRoute.FailureClass
                                        .MODEL_MISSING)},
                {profile(routeId(backend), FieldKind.GENERAL),
                        new ProviderRegistry.ObservedAvailable(
                                ProviderCapabilities.localTwoStage())}
        };
        for (Object[] entry : cases) {
            FakeDelegate delegate = new FakeDelegate();
            RecordingEvents events = new RecordingEvents();
            RecognitionRouterVoiceController controller = controller(
                    delegate,
                    new RecognitionRouterVoiceController.PreparedRoute(
                            backend,
                            routeId(backend),
                            (EffectiveProfile) entry[0],
                            (ProviderRegistry.ProbeObservation) entry[1]));

            assertTrue(controller.start(request(backend, FieldKind.GENERAL), events));
            assertEquals(0, delegate.startCalls);
            assertSame(VoiceController.State.IDLE, controller.state());
            assertTrue(events.error != null && !events.error.isBlank());
        }
    }

    @Test
    public void terminalEventsAreSingleUseAndLegacyMessagesAreRedacted() {
        RecognitionBackend backend = RecognitionBackend.OPENAI_COMPATIBLE;
        FakeDelegate delegate = new FakeDelegate();
        RecordingEvents events = new RecordingEvents();
        RecognitionRouterVoiceController controller = controller(
                delegate,
                prepared(backend, profile(routeId(backend), FieldKind.GENERAL)));

        assertTrue(controller.start(request(backend, FieldKind.GENERAL), events));
        TranscriptUpdate update = TranscriptUpdate.unstable(
                1L,
                "partial",
                TranscriptUpdate.Source.OPENAI_COMPATIBLE_BATCH);
        delegate.emitTranscript(update);
        assertSame(update, events.update);

        delegate.emitError("api key super-secret-token is unauthorized");
        assertEquals("Speech provider authentication failed", events.error);
        assertFalse(events.error.contains("super-secret-token"));
        assertSame(VoiceController.State.IDLE, controller.state());

        delegate.emitResult(result(backend));
        delegate.emitError("another secret");
        assertNull(events.result);
        assertEquals(1, events.errorCalls);
    }

    @Test
    public void breakerUsesStableDescriptorIdentityAcrossControllerRuns() {
        RecognitionBackend backend = RecognitionBackend.SYSTEM_DEFAULT;
        FakeDelegate delegate = new FakeDelegate();
        RecognitionRouterVoiceController controller = controller(
                delegate,
                prepared(backend, profile(routeId(backend), FieldKind.GENERAL)));

        for (int failure = 0; failure < ProviderCircuitBreaker.FAILURE_THRESHOLD; failure++) {
            RecordingEvents events = new RecordingEvents();
            assertTrue(controller.start(request(backend, FieldKind.GENERAL), events));
            delegate.emitError("server error with private body");
            assertEquals("The selected speech route is unavailable", events.error);
        }
        int executed = delegate.startCalls;
        RecordingEvents blocked = new RecordingEvents();
        assertTrue(controller.start(request(backend, FieldKind.GENERAL), blocked));
        assertEquals(executed, delegate.startCalls);
        assertEquals("The selected speech route is unavailable", blocked.error);
    }

    @Test
    public void preparationRunsOutsideLifecycleLockAndStopOrCancelCannotDoubleStart()
            throws Exception {
        RecognitionBackend backend = RecognitionBackend.LOCAL_OFFLINE;
        BlockingEnvironment environment = new BlockingEnvironment(
                prepared(backend, profile(routeId(backend), FieldKind.GENERAL)));
        FakeDelegate delegate = new FakeDelegate();
        RecognitionRouterVoiceController controller = new RecognitionRouterVoiceController(
                delegate,
                environment,
                new ProviderCircuitBreaker(() -> 0L),
                "com.example.target");
        AtomicBoolean accepted = new AtomicBoolean();
        Thread starter = new Thread(() -> accepted.set(controller.start(
                request(backend, FieldKind.GENERAL),
                new RecordingEvents())));
        starter.start();
        assertTrue(environment.entered.await(5, TimeUnit.SECONDS));
        assertFalse(controller.start(
                request(backend, FieldKind.GENERAL),
                new RecordingEvents()));
        controller.stop();
        environment.release.countDown();
        starter.join(5_000L);
        assertFalse(starter.isAlive());
        assertTrue(accepted.get());
        assertEquals(1, delegate.startCalls);
        assertEquals(1, delegate.stopCalls);

        BlockingEnvironment cancelledEnvironment = new BlockingEnvironment(
                prepared(backend, profile(routeId(backend), FieldKind.GENERAL)));
        FakeDelegate cancelledDelegate = new FakeDelegate();
        RecognitionRouterVoiceController cancelled = new RecognitionRouterVoiceController(
                cancelledDelegate,
                cancelledEnvironment,
                new ProviderCircuitBreaker(() -> 0L),
                "com.example.target");
        Thread cancelledStart = new Thread(() -> cancelled.start(
                request(backend, FieldKind.GENERAL),
                new RecordingEvents()));
        cancelledStart.start();
        assertTrue(cancelledEnvironment.entered.await(5, TimeUnit.SECONDS));
        cancelled.cancel();
        cancelledEnvironment.release.countDown();
        cancelledStart.join(5_000L);
        assertFalse(cancelledStart.isAlive());
        assertEquals(0, cancelledDelegate.startCalls);
        assertSame(VoiceController.State.IDLE, cancelled.state());
    }

    @Test
    public void generationExhaustionAndDelegateBusyFailWithoutRouteReuse() throws Exception {
        RecognitionBackend backend = RecognitionBackend.SYSTEM_ON_DEVICE;
        FakeDelegate delegate = new FakeDelegate();
        RecognitionRouterVoiceController controller = controller(
                delegate,
                prepared(backend, profile(routeId(backend), FieldKind.GENERAL)));
        Field generation = RecognitionRouterVoiceController.class.getDeclaredField("generation");
        generation.setAccessible(true);
        generation.setLong(controller, Long.MAX_VALUE);
        RecordingEvents exhausted = new RecordingEvents();
        assertTrue(controller.start(request(backend, FieldKind.GENERAL), exhausted));
        assertEquals("The selected speech route is unavailable", exhausted.error);
        assertEquals(0, delegate.startCalls);

        FakeDelegate busyDelegate = new FakeDelegate();
        busyDelegate.acceptStart = false;
        RecognitionRouterVoiceController busy = controller(
                busyDelegate,
                prepared(backend, profile(routeId(backend), FieldKind.GENERAL)));
        assertFalse(busy.start(request(backend, FieldKind.GENERAL), new RecordingEvents()));
        assertEquals(1, busyDelegate.startCalls);
        assertSame(VoiceController.State.IDLE, busy.state());
    }

    private static RecognitionRouterVoiceController controller(
            FakeDelegate delegate,
            RecognitionRouterVoiceController.PreparedRoute prepared) {
        return new RecognitionRouterVoiceController(
                delegate,
                (request, packageName) -> prepared,
                new ProviderCircuitBreaker(() -> 0L),
                "com.example.target");
    }

    private static RecognitionRouterVoiceController.PreparedRoute prepared(
            RecognitionBackend backend,
            EffectiveProfile profile) {
        return new RecognitionRouterVoiceController.PreparedRoute(
                backend,
                routeId(backend),
                profile,
                available(backend));
    }

    private static ProviderRegistry.ObservedAvailable available(RecognitionBackend backend) {
        return new ProviderRegistry.ObservedAvailable(
                backend == RecognitionBackend.LOCAL_OFFLINE
                        ? ProviderCapabilities.localTwoStage()
                        : ProviderCapabilities.declaredForBackend(backend));
    }

    private static EffectiveProfile profile(String routeId, FieldKind fieldKind) {
        RuleOverrides inherited = new RuleOverrides(
                OverrideValue.inherit(),
                OverrideValue.inherit(),
                OverrideValue.inherit(),
                OverrideValue.inherit(),
                OverrideValue.inherit());
        GlobalConfig global = new GlobalConfig(
                GlobalConfig.FORMAT_VERSION,
                new GlobalConfig.KeyboardConfig("latin.base"),
                new GlobalConfig.VoiceConfig(OverrideValue.value(routeId)),
                new GlobalConfig.ProcessingConfig(OverrideValue.value(
                        com.opentypeless.android.config.ProcessingMode.EXACT)),
                new GlobalConfig.PrivacyConfig(
                        OverrideValue.value(false),
                        OverrideValue.value(false)),
                new GlobalConfig.AutomationConfig(OverrideValue.disabled()));
        return EffectiveProfileResolver.resolve(new EffectiveProfileResolver.Request(
                global,
                new EffectiveProfileResolver.ProviderDefaults(
                        OverrideValue.value(routeId),
                        OverrideValue.value(
                                com.opentypeless.android.config.ProcessingMode.EXACT),
                        OverrideValue.value(false),
                        OverrideValue.value(false),
                        OverrideValue.disabled()),
                List.of(),
                List.of(),
                inherited,
                "com.example.target",
                fieldKind));
    }

    private static DictationRequest request(
            RecognitionBackend backend,
            FieldKind fieldKind) {
        return new DictationRequest(
                new AppSettings(
                        backend,
                        "https://speech.example.test/v1",
                        "",
                        "model",
                        "wss://stream.example.test/v1",
                        "streaming-key",
                        "streaming-model",
                        "",
                        "",
                        ProcessingMode.VERBATIM,
                        false,
                        "",
                        "",
                        "",
                        "",
                        "",
                        false,
                        false,
                        false,
                        180),
                ProcessingMode.VERBATIM,
                new InputContext("com.example.target", fieldKind, "", "", false),
                PersonalizationSnapshot.empty());
    }

    private static DictationResult result(RecognitionBackend backend) {
        return new DictationResult(
                "final",
                "final",
                "final",
                DictationResult.Outcome.INSERTED,
                ProcessingMode.VERBATIM,
                backend,
                10L,
                false,
                false,
                false,
                List.of(),
                List.of());
    }

    private static String routeId(RecognitionBackend backend) {
        return switch (backend) {
            case OPENAI_COMPATIBLE -> "legacy.openai-compatible";
            case LOCAL_OFFLINE -> "legacy.local-offline";
            case DASHSCOPE_STREAMING -> "legacy.dashscope-streaming";
            case SYSTEM_ON_DEVICE -> "legacy.system-on-device";
            case SYSTEM_DEFAULT -> "legacy.system-default";
        };
    }

    private static final class BlockingEnvironment
            implements RecognitionRouterVoiceController.Environment {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final RecognitionRouterVoiceController.PreparedRoute prepared;

        private BlockingEnvironment(
                RecognitionRouterVoiceController.PreparedRoute prepared) {
            this.prepared = prepared;
        }

        @Override
        public RecognitionRouterVoiceController.PreparedRoute prepare(
                DictationRequest request,
                String fallbackPackageName) {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("preparation was not released");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError(error);
            }
            return prepared;
        }
    }

    private static final class FakeDelegate implements VoiceController {
        private boolean acceptStart = true;
        private int startCalls;
        private int stopCalls;
        private DictationRequest request;
        private Events events;
        private State state = State.IDLE;

        @Override
        public boolean start(DictationRequest request, Events events) {
            startCalls++;
            this.request = request;
            this.events = events;
            if (!acceptStart) return false;
            state = State.RECORDING;
            return true;
        }

        @Override
        public void stop() {
            stopCalls++;
        }

        @Override
        public void cancel() {
            state = State.IDLE;
        }

        @Override
        public State state() {
            return state;
        }

        private void emitTranscript(TranscriptUpdate update) {
            events.onTranscript(update);
        }

        private void emitResult(DictationResult result) {
            state = State.IDLE;
            events.onResult(result);
        }

        private void emitError(String message) {
            state = State.IDLE;
            events.onError(message);
        }
    }

    private static final class RecordingEvents implements VoiceController.Events {
        private TranscriptUpdate update;
        private DictationResult result;
        private String error;
        private int errorCalls;

        @Override
        public void onState(VoiceController.State state, String message) {}

        @Override
        public void onResult(DictationResult result) {
            this.result = result;
        }

        @Override
        public void onTranscript(TranscriptUpdate update) {
            this.update = update;
        }

        @Override
        public void onError(String message) {
            errorCalls++;
            error = message;
        }
    }
}
