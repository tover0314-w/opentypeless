package com.opentypeless.android.recognition;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.config.RecognitionRoute;
import com.opentypeless.android.offline.LocalOfflineRecognitionClient;
import com.opentypeless.android.offline.LocalOfflineRecognitionService;
import com.opentypeless.android.recognition.RecognitionFailureMapper.LocalAvailability;
import com.opentypeless.android.recognition.SenseVoiceFinalProvider.StartRequest;
import com.opentypeless.android.settings.RecognitionBackend;
import com.opentypeless.android.speech.core.SessionId;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

public final class SenseVoiceFinalProviderTest {
    @Test
    public void descriptorProbePrepareAndRegistryClassifyEveryLocalAvailability() {
        FakeBackend backend = new FakeBackend();
        SenseVoiceFinalProvider provider = provider(backend, new ImmediateWorker());
        StartRequest request = request("available", wav(44), "zh-CN", false, 500L);

        assertTrue(Modifier.isFinal(SenseVoiceFinalProvider.class.getModifiers()));
        assertFalse(Modifier.isPublic(SenseVoiceFinalProvider.class.getModifiers()));
        assertEquals(
                ProviderDescriptor.declaredForBackend(RecognitionBackend.LOCAL_OFFLINE),
                provider.descriptor());
        assertTrue(provider.probe() instanceof ProviderRegistry.ObservedAvailable);
        assertTrue(provider.prepare(request) instanceof RecognitionProvider.Prepared);

        ProviderRegistry registry = new ProviderRegistry();
        assertEquals(
                ProviderRegistry.RegistrationResult.REGISTERED,
                registry.register(provider.descriptor(), provider::probe, true));
        assertTrue(registry.probe(provider.descriptor().id())
                instanceof ProviderRegistry.ProbeAvailable);

        assertAvailabilityFailure(LocalAvailability.MODEL_MISSING,
                RecognitionRoute.FailureClass.MODEL_MISSING);
        assertAvailabilityFailure(LocalAvailability.MODEL_CORRUPT,
                RecognitionRoute.FailureClass.PROTOCOL_ERROR);
        assertAvailabilityFailure(LocalAvailability.LOW_MEMORY,
                RecognitionRoute.FailureClass.UNAVAILABLE);
        assertAvailabilityFailure(LocalAvailability.UNSUPPORTED_ABI,
                RecognitionRoute.FailureClass.UNAVAILABLE);
        assertAvailabilityFailure(LocalAvailability.SYSTEM_UNAVAILABLE,
                RecognitionRoute.FailureClass.UNAVAILABLE);

        backend.throwAvailability = true;
        assertEquals(
                RecognitionRoute.FailureClass.INTERNAL_ERROR,
                ((ProviderRegistry.ObservedUnavailable) provider.probe()).failureClass());
        provider.close();
        assertEquals(
                RecognitionRoute.FailureClass.UNAVAILABLE,
                ((ProviderRegistry.ObservedUnavailable) provider.probe()).failureClass());
    }

    @Test
    public void requestCopiesOwnsBoundsAndEmitsOneFinalWithoutEndpointing() {
        FakeBackend backend = new FakeBackend();
        backend.result = "离线终稿";
        SenseVoiceFinalProvider provider = provider(backend, new ImmediateWorker());
        byte[] mutable = wav(64);
        byte expectedFirst = mutable[0];
        StartRequest request = request("final", mutable, "zh-CN", true, 1_200L);
        mutable[0] = (byte) (expectedFirst + 1);
        RecordingSink sink = new RecordingSink();

        RecognitionProvider.Session session = provider.start(request, sink);

        assertEquals(expectedFirst, backend.audio[0]);
        assertEquals("zh-CN", backend.language);
        assertTrue(backend.useInverseTextNormalization);
        assertEquals(List.of("Preparing", "Ready", "Final"), sink.kinds());
        assertEquals(List.of(1L, 2L, 3L), sink.sequences());
        RecognitionEvent.Final terminal = (RecognitionEvent.Final) sink.events.get(2);
        assertEquals("离线终稿", terminal.text());
        assertEquals(Long.valueOf(1_200L), terminal.metadata().audioDurationMs());
        assertEquals(0, request.audioByteCount());
        assertReleased(session);

        assertThrows(
                IllegalArgumentException.class,
                () -> request("short", new byte[43], "", false, 1L));
        assertThrows(
                IllegalArgumentException.class,
                () -> request(
                        "large",
                        new byte[LocalOfflineRecognitionService.MAX_WAV_BYTES + 1],
                        "",
                        false,
                        1L));
        assertThrows(
                IllegalArgumentException.class,
                () -> request("duration", wav(44), "", false, 0L));
        assertThrows(
                IllegalArgumentException.class,
                () -> request("language", wav(44), "\uD800", false, 1L));
        assertFalse(request.toString().contains("final"));
        assertFalse(provider.toString().contains("离线终稿"));
    }

    @Test
    public void closeBeforeStartZerosCopiedAudioAndConsumedRequestFailsOnce() throws Exception {
        StartRequest closed = request("closed", wav(44), "", false, 1L);
        Field wavField = StartRequest.class.getDeclaredField("wav");
        wavField.setAccessible(true);
        byte[] owned = (byte[]) wavField.get(closed);
        closed.close();
        assertArrayEquals(new byte[owned.length], owned);
        assertNull(wavField.get(closed));

        FakeBackend backend = new FakeBackend();
        SenseVoiceFinalProvider provider = provider(backend, new ImmediateWorker());
        RecordingSink rejected = new RecordingSink();
        provider.start(closed, rejected);
        assertFailure(rejected, RecognitionRoute.FailureClass.INTERNAL_ERROR);
        assertEquals(0, backend.transcribeCount);

        StartRequest once = request("once", wav(44), "", false, 1L);
        provider.start(once, new RecordingSink());
        RecordingSink consumed = new RecordingSink();
        provider.start(once, consumed);
        assertFailure(consumed, RecognitionRoute.FailureClass.INTERNAL_ERROR);
        assertEquals(1, backend.transcribeCount);
    }

    @Test
    public void cancelBeforeWorkerRunIsSingleTerminalAndClearsAudioWithoutDecode() {
        FakeBackend backend = new FakeBackend();
        QueuedWorker worker = new QueuedWorker();
        SenseVoiceFinalProvider provider = provider(backend, worker);
        RecordingSink sink = new RecordingSink();
        RecognitionProvider.Session session = provider.start(
                request("queued", wav(44), "", false, 1L), sink);

        session.stop();
        session.cancel();
        worker.runAll();

        assertEquals(List.of("Preparing", "Cancelled"), sink.kinds());
        assertEquals(1, backend.cancelCount);
        assertEquals(0, backend.transcribeCount);
        assertReleased(session);
    }

    @Test
    public void cancellationDuringDecodeDropsLateResultAndProviderCloseIsIdempotent() {
        FakeBackend backend = new FakeBackend();
        QueuedWorker worker = new QueuedWorker();
        SenseVoiceFinalProvider provider = provider(backend, worker);
        RecordingSink sink = new RecordingSink();
        RecognitionProvider.Session[] session = new RecognitionProvider.Session[1];
        backend.onTranscribe = () -> session[0].cancel();
        session[0] = provider.start(
                request("during", wav(44), "", false, 1L), sink);

        worker.runAll();
        provider.close();
        provider.close();

        assertEquals(List.of("Preparing", "Ready", "Cancelled"), sink.kinds());
        assertEquals(1, backend.transcribeCount);
        assertEquals(1, backend.cancelCount);
        assertEquals(1, backend.closeCount);
        assertEquals(1, worker.closeCount);
        assertReleased(session[0]);
    }

    @Test
    public void busyStartDoesNotConsumeRejectedRequestOrDisturbActiveSession() {
        FakeBackend backend = new FakeBackend();
        QueuedWorker worker = new QueuedWorker();
        SenseVoiceFinalProvider provider = provider(backend, worker);
        RecordingSink active = new RecordingSink();
        provider.start(request("active", wav(44), "", false, 1L), active);
        StartRequest busyRequest = request("busy", wav(44), "", false, 1L);
        RecordingSink busy = new RecordingSink();

        provider.start(busyRequest, busy);

        assertFailure(busy, RecognitionRoute.FailureClass.RECOGNIZER_BUSY);
        assertTrue(busyRequest.available());
        worker.runAll();
        assertEquals(List.of("Preparing", "Ready", "Final"), active.kinds());
        assertEquals(1, backend.transcribeCount);
    }

    @Test
    public void runtimeAndModelRaceFailuresMapWithoutExceptionMessages() {
        assertRuntimeFailure(
                new IllegalArgumentException("audio-secret"),
                LocalAvailability.READY,
                RecognitionRoute.FailureClass.AUDIO_ERROR);
        assertRuntimeFailure(
                new IllegalStateException("native-secret"),
                LocalAvailability.READY,
                RecognitionRoute.FailureClass.INTERNAL_ERROR);
        assertRuntimeFailure(
                new IllegalStateException("missing-secret"),
                LocalAvailability.MODEL_MISSING,
                RecognitionRoute.FailureClass.MODEL_MISSING);
        assertRuntimeFailure(
                new IllegalStateException("corrupt-secret"),
                LocalAvailability.MODEL_CORRUPT,
                RecognitionRoute.FailureClass.PROTOCOL_ERROR);
        assertRuntimeFailure(
                new IllegalStateException("memory-secret"),
                LocalAvailability.LOW_MEMORY,
                RecognitionRoute.FailureClass.UNAVAILABLE);
    }

    @Test
    public void blankMalformedAndOversizedResultsFailClosedAndRemainRedacted() {
        Object[][] cases = {
                {" ", RecognitionRoute.FailureClass.NO_MATCH},
                {"\uD800", RecognitionRoute.FailureClass.PROTOCOL_ERROR},
                {"x".repeat(RecognitionEvent.MAX_TEXT_CODE_POINTS + 1),
                        RecognitionRoute.FailureClass.PROTOCOL_ERROR}
        };
        for (int index = 0; index < cases.length; index++) {
            FakeBackend backend = new FakeBackend();
            backend.result = (String) cases[index][0];
            RecordingSink sink = new RecordingSink();
            provider(backend, new ImmediateWorker()).start(
                    request("result-" + index, wav(44), "", false, 1L), sink);
            assertFailure(sink, (RecognitionRoute.FailureClass) cases[index][1]);
            assertEquals(1, sink.terminalCount());
            for (RecognitionEvent event : sink.events) {
                assertFalse(event.toString().contains("result-"));
            }
        }
    }

    @Test
    public void sinkAndWorkerFailuresRevokeAuthorityWithoutLeakingContent() {
        FakeBackend sinkBackend = new FakeBackend();
        QueuedWorker queued = new QueuedWorker();
        SenseVoiceFinalProvider sinkProvider = provider(sinkBackend, queued);
        RecognitionProvider.Session session = sinkProvider.start(
                request("sink", wav(44), "", false, 1L),
                event -> {
                    throw new IllegalStateException("sink-secret");
                });
        queued.runAll();
        assertEquals(1, sinkBackend.cancelCount);
        assertEquals(0, sinkBackend.transcribeCount);
        assertReleased(session);

        FakeBackend workerBackend = new FakeBackend();
        SenseVoiceFinalProvider workerProvider = provider(workerBackend, new ThrowingWorker());
        RecordingSink sink = new RecordingSink();
        workerProvider.start(request("worker", wav(44), "", false, 1L), sink);
        assertFailure(sink, RecognitionRoute.FailureClass.INTERNAL_ERROR);
        assertFalse(sink.events.toString().contains("worker-secret"));
    }

    @Test
    public void legacyClientResultRejectsMalformedTextAndRedactsBothOutputs() {
        LocalOfflineRecognitionClient.Result result =
                new LocalOfflineRecognitionClient.Result("exact-secret", "punctuated-secret");
        assertEquals("exact-secret", result.exactText());
        assertEquals("punctuated-secret", result.punctuatedText());
        assertFalse(result.toString().contains("exact-secret"));
        assertFalse(result.toString().contains("punctuated-secret"));
        assertThrows(
                IllegalStateException.class,
                () -> new LocalOfflineRecognitionClient.Result("\uD800", "safe"));
        assertThrows(
                IllegalStateException.class,
                () -> new LocalOfflineRecognitionClient.Result("safe", "\uDC00"));
    }

    private static void assertAvailabilityFailure(
            LocalAvailability availability,
            RecognitionRoute.FailureClass expected) {
        FakeBackend backend = new FakeBackend();
        backend.availability = availability;
        SenseVoiceFinalProvider provider = provider(backend, new ImmediateWorker());
        assertEquals(
                expected,
                ((ProviderRegistry.ObservedUnavailable) provider.probe()).failureClass());
        StartRequest request = request("availability", wav(44), "", false, 1L);
        assertEquals(
                expected,
                ((RecognitionProvider.NotPrepared) provider.prepare(request)).failureClass());
        RecordingSink sink = new RecordingSink();
        provider.start(request, sink);
        assertFailure(sink, expected);
        assertTrue(request.available());
        assertEquals(0, backend.transcribeCount);
    }

    private static void assertRuntimeFailure(
            RuntimeException failure,
            LocalAvailability afterFailure,
            RecognitionRoute.FailureClass expected) {
        FakeBackend backend = new FakeBackend();
        backend.failure = failure;
        backend.availabilityAfterTranscribe = afterFailure;
        RecordingSink sink = new RecordingSink();
        provider(backend, new ImmediateWorker()).start(
                request("runtime", wav(44), "", false, 1L), sink);
        assertFailure(sink, expected);
        assertFalse(sink.events.toString().contains("secret"));
    }

    private static SenseVoiceFinalProvider provider(
            FakeBackend backend,
            SenseVoiceFinalProvider.Worker worker) {
        return new SenseVoiceFinalProvider(backend, worker);
    }

    private static StartRequest request(
            String id,
            byte[] wav,
            String language,
            boolean useInverseTextNormalization,
            long durationMs) {
        return new StartRequest(
                SessionId.of(id), wav, language, useInverseTextNormalization, durationMs);
    }

    private static byte[] wav(int length) {
        byte[] value = new byte[length];
        Arrays.fill(value, (byte) 7);
        return value;
    }

    private static void assertFailure(
            RecordingSink sink,
            RecognitionRoute.FailureClass expected) {
        assertEquals(1, sink.terminalCount());
        RecognitionEvent event = sink.events.get(sink.events.size() - 1);
        assertTrue(event instanceof RecognitionEvent.Failure);
        assertEquals(expected, ((RecognitionEvent.Failure) event).failureClass());
    }

    private static void assertReleased(RecognitionProvider.Session session) {
        try {
            for (String fieldName : List.of("audio", "language", "sink")) {
                Field field = session.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                assertNull(field.get(session));
            }
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static final class RecordingSink implements RecognitionProvider.EventSink {
        private final List<RecognitionEvent> events = new ArrayList<>();

        @Override
        public void onEvent(RecognitionEvent event) {
            events.add(event);
        }

        List<String> kinds() {
            return events.stream().map(event -> event.getClass().getSimpleName()).toList();
        }

        List<Long> sequences() {
            return events.stream().map(RecognitionEvent::sequence).toList();
        }

        int terminalCount() {
            return (int) events.stream().filter(RecognitionEvent::terminal).count();
        }
    }

    private static final class FakeBackend implements SenseVoiceFinalProvider.Backend {
        private LocalAvailability availability = LocalAvailability.READY;
        private LocalAvailability availabilityAfterTranscribe;
        private boolean throwAvailability;
        private String result = "local final";
        private RuntimeException failure;
        private Runnable onTranscribe;
        private byte[] audio;
        private String language;
        private boolean useInverseTextNormalization;
        private int transcribeCount;
        private int cancelCount;
        private int closeCount;

        @Override
        public LocalAvailability availability() {
            if (throwAvailability) throw new IllegalStateException("availability-secret");
            return availability;
        }

        @Override
        public String transcribe(
                byte[] wav,
                String language,
                boolean useInverseTextNormalization,
                BooleanSupplier cancelled) {
            transcribeCount++;
            audio = Arrays.copyOf(wav, wav.length);
            this.language = language;
            this.useInverseTextNormalization = useInverseTextNormalization;
            if (onTranscribe != null) onTranscribe.run();
            if (availabilityAfterTranscribe != null) availability = availabilityAfterTranscribe;
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("cancelled-secret");
            }
            if (failure != null) throw failure;
            return result;
        }

        @Override
        public void cancel() {
            cancelCount++;
        }

        @Override
        public void close() {
            closeCount++;
        }
    }

    private static final class ImmediateWorker implements SenseVoiceFinalProvider.Worker {
        @Override
        public void execute(Runnable action) {
            action.run();
        }

        @Override
        public void close() {}
    }

    private static final class QueuedWorker implements SenseVoiceFinalProvider.Worker {
        private final List<Runnable> queued = new ArrayList<>();
        private int closeCount;

        @Override
        public void execute(Runnable action) {
            queued.add(action);
        }

        @Override
        public void close() {
            closeCount++;
            queued.clear();
        }

        void runAll() {
            List<Runnable> actions = List.copyOf(queued);
            queued.clear();
            actions.forEach(Runnable::run);
        }
    }

    private static final class ThrowingWorker implements SenseVoiceFinalProvider.Worker {
        @Override
        public void execute(Runnable action) {
            throw new IllegalStateException("worker-secret");
        }

        @Override
        public void close() {}
    }
}
