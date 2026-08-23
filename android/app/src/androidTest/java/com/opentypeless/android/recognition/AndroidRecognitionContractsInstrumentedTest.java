package com.opentypeless.android.recognition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.opentypeless.android.config.ProviderConfig;
import com.opentypeless.android.config.RecognitionRoute;
import com.opentypeless.android.data.PersonalizationSnapshot;
import com.opentypeless.android.offline.LocalOfflineRecognizer;
import com.opentypeless.android.offline.OfflineModelStore;
import com.opentypeless.android.recognition.RecognitionFailureMapper.LocalAvailability;
import com.opentypeless.android.settings.AppSettings;
import com.opentypeless.android.settings.ProcessingMode;
import com.opentypeless.android.settings.RecognitionBackend;
import com.opentypeless.android.speech.core.SessionId;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

@RunWith(AndroidJUnit4.class)
public final class AndroidRecognitionContractsInstrumentedTest {
    private static final String PREFERENCES = "opentypeless_standard_recognition";

    @After
    public void clearSettings() {
        Context context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    public void activityAndServiceUseTheirDistinctAndroidResultContracts() {
        RecognitionResult result = new RecognitionResult(List.of("best", "other"), new float[]{0.8f});

        Intent activity = AndroidRecognitionContracts.resultIntent(result);
        Bundle service = AndroidRecognitionContracts.results(result);

        assertEquals(
                List.of("best", "other"),
                activity.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS));
        assertTrue(activity.hasExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES));
        assertFalse(activity.hasExtra(SpeechRecognizer.RESULTS_RECOGNITION));
        assertEquals(
                List.of("best", "other"),
                service.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION));
        assertTrue(service.containsKey(SpeechRecognizer.CONFIDENCE_SCORES));
        assertFalse(service.containsKey(RecognizerIntent.EXTRA_RESULTS));
    }

    @Test
    public void unifiedFailureMappingIsStableAndRedactsPlatformDetailOnDevice() {
        assertEquals(
                RecognitionRoute.FailureClass.OEM_MIC_BLOCKED,
                RecognitionFailureMapper.fromAndroidSystem(
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                        SystemSpeechRecognizer.MICROPHONE_ACCESS_BLOCKED));
        assertEquals(
                RecognitionRoute.FailureClass.PERMISSION_DENIED,
                RecognitionFailureMapper.fromAndroidSystem(
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                        "oem-provider-secret"));
        assertEquals(
                RecognitionRoute.FailureClass.MODEL_MISSING,
                RecognitionFailureMapper.fromLocalAvailability(LocalAvailability.MODEL_MISSING));

        RecognitionFailure legacy = RecognitionErrors.fromPipelineMessage(
                "Provider redirect was rejected: provider-secret");
        assertEquals(RecognitionRoute.FailureClass.PROTOCOL_ERROR, legacy.failureClass());
        assertEquals(SpeechRecognizer.ERROR_CLIENT, legacy.errorCode());
        assertFalse(legacy.message().contains("provider-secret"));
        assertFalse(legacy.toString().contains("provider-secret"));
    }

    @Test
    public void providerCircuitBreakerOpensHalfOpensAndRecoversOnAndroidRuntime() {
        long[] now = {0L};
        ProviderCircuitBreaker breaker = new ProviderCircuitBreaker(() -> now[0]);
        ProviderDescriptor provider = ProviderDescriptor.declaredForBackend(
                RecognitionBackend.SYSTEM_DEFAULT);

        for (int failure = 0; failure < ProviderCircuitBreaker.FAILURE_THRESHOLD; failure++) {
            ProviderCircuitBreaker.PermitGranted granted =
                    (ProviderCircuitBreaker.PermitGranted) breaker.acquire(provider);
            ProviderCircuitBreaker.Disposition disposition = breaker.onFailure(
                    granted.permit(),
                    RecognitionRoute.FailureClass.SERVER_ERROR);
            assertEquals(
                    failure + 1 == ProviderCircuitBreaker.FAILURE_THRESHOLD
                            ? ProviderCircuitBreaker.Disposition.OPENED
                            : ProviderCircuitBreaker.Disposition.RECORDED,
                    disposition);
        }
        assertEquals(
                ProviderCircuitBreaker.RejectionReason.OPEN,
                ((ProviderCircuitBreaker.PermitRejected) breaker.acquire(provider)).reason());

        now[0] = ProviderCircuitBreaker.OPEN_INTERVAL_MILLIS;
        ProviderCircuitBreaker.PermitGranted probe =
                (ProviderCircuitBreaker.PermitGranted) breaker.acquire(provider);
        assertEquals(
                ProviderCircuitBreaker.RejectionReason.HALF_OPEN_BUSY,
                ((ProviderCircuitBreaker.PermitRejected) breaker.acquire(provider)).reason());
        assertEquals(
                ProviderCircuitBreaker.Disposition.RECOVERED,
                breaker.onSuccess(probe.permit()));
        assertTrue(breaker.acquire(provider) instanceof ProviderCircuitBreaker.PermitGranted);
    }

    @Test
    public void externalStandardSpeechIsDisabledUntilUserExplicitlyEnablesAllowlist() {
        Context context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit();
        StandardRecognitionSettings settings = new StandardRecognitionSettings(context);

        assertFalse(settings.load().enabled());
        settings.save(settings.validate(true, "com.example.keyboard"));

        StandardRecognitionSettings.Snapshot saved = settings.load();
        assertTrue(saved.enabled());
        assertEquals(Set.of("com.example.keyboard"), saved.allowedPackages());
    }

    @Test
    public void systemCapabilityRequestKeepsRouteButOmitsRecognitionAndPersonalizationExtras() {
        AppSettings settings = systemSettings("zh-CN");

        Intent startIntent = SystemRecognitionIntentFactory.create(
                settings,
                PersonalizationSnapshot.empty());
        Intent capabilityIntent = SystemRecognitionIntentFactory.createCapabilityRequest(settings);

        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, startIntent.getAction());
        assertEquals(
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                capabilityIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL));
        assertFalse(capabilityIntent.getBooleanExtra(
                RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                true));
        assertEquals(1, capabilityIntent.getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 0));
        assertTrue(capabilityIntent.getBooleanExtra(
                RecognizerIntent.EXTRA_PREFER_OFFLINE,
                false));
        assertEquals("zh-CN", capabilityIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE));
        assertFalse(capabilityIntent.hasExtra(RecognizerIntent.EXTRA_PROMPT));
        assertFalse(capabilityIntent.hasExtra(RecognizerIntent.EXTRA_BIASING_STRINGS));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            assertEquals(
                    RecognizerIntent.FORMATTING_OPTIMIZE_LATENCY,
                    capabilityIntent.getStringExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING));
        } else {
            assertFalse(capabilityIntent.hasExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING));
        }
    }

    @Test
    public void systemSupportProbeReturnsOneContentFreeTerminalAcrossOemCallbacks()
            throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        CountDownLatch terminal = new CountDownLatch(1);
        AtomicInteger terminals = new AtomicInteger();
        AtomicReference<SystemRecognitionSupport.Result> observed = new AtomicReference<>();

        SystemRecognitionSupport.Operation operation =
                SystemSpeechRecognizer.checkRecognitionSupport(
                        context,
                        systemSettings("zh-CN"),
                        PersonalizationSnapshot.empty(),
                        result -> {
                            observed.set(result);
                            terminals.incrementAndGet();
                            terminal.countDown();
                        });

        assertTrue("System support probe must reach one bounded terminal",
                terminal.await(20, TimeUnit.SECONDS));
        operation.cancel();
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertEquals(1, terminals.get());
        assertTrue(observed.get() != null);
        assertFalse(observed.get().toString().contains("zh-CN"));
    }

    @Test
    public void systemProviderRequestBuildsLeastAuthorityIntentAndFactoryClosesOnMain() {
        Context context = ApplicationProvider.getApplicationContext();
        AndroidSystemRecognitionProvider.StartRequest request =
                AndroidSystemRecognitionProvider.StartRequest.fromSnapshot(
                        com.opentypeless.android.speech.core.SessionId.of("android-system-adapter"),
                        new RecognitionRequest(
                                "zh-CN",
                                "caller-must-not-reach-provider",
                                "prompt-must-not-reach-provider",
                                2,
                                false),
                        new PersonalizationSnapshot(
                                List.of(new com.opentypeless.android.data.PersonalTerm(
                                        1L,
                                        "偏置词",
                                        "",
                                        "",
                                        "",
                                        0,
                                        true)),
                                List.of()),
                        30_000L);
        Intent intent = SystemRecognitionIntentFactory.create(
                RecognitionBackend.SYSTEM_ON_DEVICE,
                request.language(),
                request.partialResults(),
                request.maxResults(),
                request.biasingTerms());

        assertEquals("zh-CN", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE));
        assertEquals(2, intent.getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 0));
        assertFalse(intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true));
        assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false));
        assertFalse(intent.hasExtra(RecognizerIntent.EXTRA_PROMPT));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            assertEquals(
                    List.of("偏置词"),
                    intent.getStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS));
        }

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            AndroidSystemRecognitionProvider provider = AndroidSystemRecognitionProvider.create(
                    context,
                    RecognitionBackend.SYSTEM_ON_DEVICE);
            assertEquals(
                    ProviderDescriptor.declaredForBackend(RecognitionBackend.SYSTEM_ON_DEVICE),
                    provider.descriptor());
            provider.close();
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void uploadProviderOwnsCopiedAudioAndEmitsOneRedactedTerminalOnAndroid() {
        byte[] callerAudio = {1, 2, 3, 4};
        CapturingUploadBackend backend = new CapturingUploadBackend();
        OpenAiCompatibleUploadProvider provider = new OpenAiCompatibleUploadProvider(
                new ProviderConfig.Asr(
                        "builtin.openai-compatible",
                        "OpenAI Compatible",
                        Optional.of(new ProviderConfig.Endpoint("http://localhost:8787/v1")),
                        Optional.of("whisper-test"),
                        Optional.empty(),
                        true),
                backend,
                new ImmediateUploadWorker());
        OpenAiCompatibleUploadProvider.StartRequest request =
                new OpenAiCompatibleUploadProvider.StartRequest(
                        SessionId.of("android-upload-adapter"),
                        callerAudio,
                        "zh-CN",
                        "device-private-prompt",
                        1_000L);
        callerAudio[0] = 99;
        List<RecognitionEvent> events = new ArrayList<>();

        RecognitionProvider.Session session = provider.start(request, events::add);

        assertEquals(4, events.size());
        assertEquals("Preparing", events.get(0).getClass().getSimpleName());
        assertEquals("Ready", events.get(1).getClass().getSimpleName());
        assertEquals("Endpoint", events.get(2).getClass().getSimpleName());
        assertEquals("Final", events.get(3).getClass().getSimpleName());
        assertEquals(1L, events.get(0).sequence());
        assertEquals(2L, events.get(1).sequence());
        assertEquals(3L, events.get(2).sequence());
        assertEquals(4L, events.get(3).sequence());
        assertEquals("设备转写", ((RecognitionEvent.Final) events.get(3)).text());
        assertTrue(Arrays.equals(new byte[]{1, 2, 3, 4}, backend.audio));
        assertEquals(0, request.audioByteCount());
        assertFalse(request.toString().contains("device-private-prompt"));
        assertFalse(session.toString().contains("android-upload-adapter"));
        provider.close();
    }

    @Test
    public void senseVoiceFinalProviderProbesDeviceAndOwnsFinalAudioOnAndroid() {
        Context context = ApplicationProvider.getApplicationContext();
        SenseVoiceFinalProvider production = SenseVoiceFinalProvider.create(context);
        assertEquals(
                ProviderDescriptor.declaredForBackend(RecognitionBackend.LOCAL_OFFLINE),
                production.descriptor());
        ProviderRegistry.ProbeObservation observation = production.probe();
        LocalOfflineRecognizer.DeviceSupport support =
                LocalOfflineRecognizer.deviceSupport(context);
        OfflineModelStore.Status modelStatus = OfflineModelStore.status(context);
        if (support == LocalOfflineRecognizer.DeviceSupport.SUPPORTED
                && modelStatus == OfflineModelStore.Status.INSTALLED) {
            assertTrue(observation instanceof ProviderRegistry.ObservedAvailable);
        } else {
            assertTrue(observation instanceof ProviderRegistry.ObservedUnavailable);
            RecognitionRoute.FailureClass expectedFailure =
                    support != LocalOfflineRecognizer.DeviceSupport.SUPPORTED
                            ? RecognitionRoute.FailureClass.UNAVAILABLE
                            : modelStatus == OfflineModelStore.Status.MISSING
                                    ? RecognitionRoute.FailureClass.MODEL_MISSING
                                    : RecognitionRoute.FailureClass.PROTOCOL_ERROR;
            assertEquals(
                    expectedFailure,
                    ((ProviderRegistry.ObservedUnavailable) observation).failureClass());
        }
        production.close();

        byte[] callerAudio = new byte[44];
        Arrays.fill(callerAudio, (byte) 5);
        CapturingSenseVoiceBackend backend = new CapturingSenseVoiceBackend();
        SenseVoiceFinalProvider provider = new SenseVoiceFinalProvider(
                backend, new ImmediateSenseVoiceWorker());
        SenseVoiceFinalProvider.StartRequest request =
                new SenseVoiceFinalProvider.StartRequest(
                        SessionId.of("android-sensevoice-adapter"),
                        callerAudio,
                        "zh-CN",
                        true,
                        900L);
        callerAudio[0] = 99;
        List<RecognitionEvent> events = new ArrayList<>();

        RecognitionProvider.Session session = provider.start(request, events::add);

        assertEquals(3, events.size());
        assertEquals("Preparing", events.get(0).getClass().getSimpleName());
        assertEquals("Ready", events.get(1).getClass().getSimpleName());
        assertEquals("Final", events.get(2).getClass().getSimpleName());
        assertEquals(1L, events.get(0).sequence());
        assertEquals(2L, events.get(1).sequence());
        assertEquals(3L, events.get(2).sequence());
        assertEquals("本地终稿", ((RecognitionEvent.Final) events.get(2)).text());
        assertEquals(5, backend.audio[0]);
        assertTrue(backend.useInverseTextNormalization);
        assertEquals(0, request.audioByteCount());
        assertFalse(session.toString().contains("android-sensevoice-adapter"));
        provider.close();

        CapturingSenseVoiceBackend cancelledBackend = new CapturingSenseVoiceBackend();
        QueuedSenseVoiceWorker queued = new QueuedSenseVoiceWorker();
        SenseVoiceFinalProvider cancellable = new SenseVoiceFinalProvider(
                cancelledBackend, queued);
        List<RecognitionEvent> cancelledEvents = new ArrayList<>();
        RecognitionProvider.Session cancelledSession = cancellable.start(
                new SenseVoiceFinalProvider.StartRequest(
                        SessionId.of("android-sensevoice-cancel"),
                        new byte[44],
                        "",
                        false,
                        1L),
                cancelledEvents::add);
        cancelledSession.cancel();
        queued.runAll();
        assertEquals(2, cancelledEvents.size());
        assertEquals("Preparing", cancelledEvents.get(0).getClass().getSimpleName());
        assertEquals("Cancelled", cancelledEvents.get(1).getClass().getSimpleName());
        assertEquals(1, cancelledBackend.cancelCount);
        assertEquals(0, cancelledBackend.transcribeCount);
        cancellable.close();
    }

    @Test
    public void prefixReplayProviderProbesDeviceAndEmitsRevisableNonStreamingPartials() {
        Context context = ApplicationProvider.getApplicationContext();
        PrefixReplayPreviewProvider production = PrefixReplayPreviewProvider.create(context);
        assertEquals("builtin.local-prefix-replay", production.descriptor().id());
        assertFalse(production.descriptor().capabilities().supportsStreaming());
        assertTrue(production.descriptor().capabilities().supportsPartialRevision());
        assertEquals(
                ProviderCapabilities.ImplementationKind.PREFIX_REPLAY,
                production.descriptor().capabilities().implementationKind());
        ProviderRegistry.ProbeObservation observation = production.probe();
        LocalOfflineRecognizer.DeviceSupport support =
                LocalOfflineRecognizer.deviceSupport(context);
        OfflineModelStore.Status modelStatus = OfflineModelStore.status(context);
        if (support == LocalOfflineRecognizer.DeviceSupport.SUPPORTED
                && modelStatus == OfflineModelStore.Status.INSTALLED) {
            assertTrue(observation instanceof ProviderRegistry.ObservedAvailable);
        } else {
            assertTrue(observation instanceof ProviderRegistry.ObservedUnavailable);
            RecognitionRoute.FailureClass expectedFailure =
                    support != LocalOfflineRecognizer.DeviceSupport.SUPPORTED
                            ? RecognitionRoute.FailureClass.UNAVAILABLE
                            : modelStatus == OfflineModelStore.Status.MISSING
                                    ? RecognitionRoute.FailureClass.MODEL_MISSING
                                    : RecognitionRoute.FailureClass.PROTOCOL_ERROR;
            assertEquals(
                    expectedFailure,
                    ((ProviderRegistry.ObservedUnavailable) observation).failureClass());
        }
        production.close();

        CapturingPrefixBackend backend = new CapturingPrefixBackend();
        PrefixReplayPreviewProvider provider = new PrefixReplayPreviewProvider(backend);
        List<RecognitionEvent> events = new ArrayList<>();
        PrefixReplayPreviewProvider.PreviewSession session = provider.start(
                new PrefixReplayPreviewProvider.StartRequest(
                        SessionId.of("android-prefix-replay"),
                        "zh-CN"),
                events::add);
        byte[] callerPcm = {1, 2, 3, 4, 5};
        session.acceptPcm(callerPcm, callerPcm.length);
        callerPcm[0] = 99;
        backend.engine.emit("设备草稿一");
        backend.engine.emit("设备改写草稿二");
        session.cancel();

        assertEquals(5, events.size());
        assertEquals("Preparing", events.get(0).getClass().getSimpleName());
        assertEquals("Ready", events.get(1).getClass().getSimpleName());
        assertEquals("Partial", events.get(2).getClass().getSimpleName());
        assertEquals("Partial", events.get(3).getClass().getSimpleName());
        assertEquals("Cancelled", events.get(4).getClass().getSimpleName());
        RecognitionEvent.Partial first = (RecognitionEvent.Partial) events.get(2);
        RecognitionEvent.Partial second = (RecognitionEvent.Partial) events.get(3);
        assertEquals(Integer.valueOf(0), first.stablePrefixLength());
        assertEquals(Long.valueOf(first.sequence()), second.revisionOf());
        assertTrue(Arrays.equals(new byte[]{1, 2, 3, 4}, backend.engine.audio));
        assertTrue(Arrays.equals(new byte[4], backend.engine.reference));
        assertEquals(4, session.acceptedPcmBytes());
        assertFalse(session.toString().contains("android-prefix-replay"));
        provider.close();
    }

    @Test
    public void unobservedDownloadResultKeepsOperationAliveForPlatformDispatch() throws Exception {
        SystemRecognitionSupport.OneShotOperation operation =
                new SystemRecognitionSupport.OneShotOperation(
                        new Handler(Looper.getMainLooper()));
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicInteger results = new AtomicInteger();
        SystemRecognitionSupport.DownloadCallback callback = result -> {
            results.incrementAndGet();
            delivered.countDown();
        };

        SystemRecognitionSupport.reportDownloadDispatched(
                operation,
                callback,
                new SystemRecognitionSupport.DownloadResult(
                        SystemRecognitionSupport.DownloadStatus.REQUESTED,
                        null));
        SystemRecognitionSupport.completeDownload(
                operation,
                callback,
                new SystemRecognitionSupport.DownloadResult(
                        SystemRecognitionSupport.DownloadStatus.FAILED,
                        RecognitionRoute.FailureClass.INTERNAL_ERROR));

        assertTrue(delivered.await(1, TimeUnit.SECONDS));
        assertEquals(1, results.get());
        assertTrue("Recognizer must survive long enough to drain its queued trigger",
                operation.isActive());
        Thread.sleep(1_250L);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertFalse(operation.isActive());
    }

    private static AppSettings systemSettings(String language) {
        return new AppSettings(
                RecognitionBackend.SYSTEM_ON_DEVICE,
                "",
                "",
                "",
                "wss://dashscope.aliyuncs.com/api-ws/v1/inference",
                "",
                "paraformer-realtime-v2",
                "",
                language,
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
                180);
    }

    private static final class CapturingUploadBackend
            implements OpenAiCompatibleUploadProvider.UploadBackend {
        private byte[] audio;

        @Override
        public String transcribe(
                ProviderConfig.Asr config,
                byte[] wav,
                String language,
                String prompt,
                long durationMs,
                BooleanSupplier cancelled) {
            audio = Arrays.copyOf(wav, wav.length);
            return "设备转写";
        }

        @Override
        public void cancel() {}

        @Override
        public void close() {}
    }

    private static final class ImmediateUploadWorker
            implements OpenAiCompatibleUploadProvider.Worker {
        @Override
        public void execute(Runnable action) {
            action.run();
        }

        @Override
        public void close() {}
    }

    private static final class CapturingSenseVoiceBackend
            implements SenseVoiceFinalProvider.Backend {
        private byte[] audio;
        private boolean useInverseTextNormalization;
        private int transcribeCount;
        private int cancelCount;

        @Override
        public LocalAvailability availability() {
            return LocalAvailability.READY;
        }

        @Override
        public String transcribe(
                byte[] wav,
                String language,
                boolean useInverseTextNormalization,
                BooleanSupplier cancelled) {
            transcribeCount++;
            audio = Arrays.copyOf(wav, wav.length);
            this.useInverseTextNormalization = useInverseTextNormalization;
            return "本地终稿";
        }

        @Override
        public void cancel() {
            cancelCount++;
        }

        @Override
        public void close() {}
    }

    private static final class ImmediateSenseVoiceWorker
            implements SenseVoiceFinalProvider.Worker {
        @Override
        public void execute(Runnable action) {
            action.run();
        }

        @Override
        public void close() {}
    }

    private static final class QueuedSenseVoiceWorker
            implements SenseVoiceFinalProvider.Worker {
        private Runnable action;

        @Override
        public void execute(Runnable action) {
            this.action = action;
        }

        @Override
        public void close() {
            action = null;
        }

        void runAll() {
            Runnable queued = action;
            action = null;
            if (queued != null) queued.run();
        }
    }

    private static final class CapturingPrefixBackend
            implements PrefixReplayPreviewProvider.Backend {
        private CapturingPrefixEngine engine;

        @Override
        public LocalAvailability availability() {
            return LocalAvailability.READY;
        }

        @Override
        public PrefixReplayPreviewProvider.PreviewEngine open(
                String language,
                PrefixReplayPreviewProvider.PartialSink sink) {
            engine = new CapturingPrefixEngine(sink);
            return engine;
        }

        @Override
        public void close() {}
    }

    private static final class CapturingPrefixEngine
            implements PrefixReplayPreviewProvider.PreviewEngine {
        private final PrefixReplayPreviewProvider.PartialSink sink;
        private byte[] reference;
        private byte[] audio;

        private CapturingPrefixEngine(PrefixReplayPreviewProvider.PartialSink sink) {
            this.sink = sink;
        }

        @Override
        public void accept(byte[] pcm, int length) {
            reference = pcm;
            audio = Arrays.copyOf(pcm, length);
        }

        @Override
        public void cancel() {}

        private void emit(String text) {
            sink.onPartial(text);
        }
    }
}
