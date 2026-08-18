package com.opentypeless.android.recognition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.speech.SpeechRecognizer;

import com.opentypeless.android.config.RecognitionRoute;
import com.opentypeless.android.data.PersonalTerm;
import com.opentypeless.android.data.PersonalizationSnapshot;
import com.opentypeless.android.recognition.AndroidSystemRecognitionProvider.Backend;
import com.opentypeless.android.recognition.AndroidSystemRecognitionProvider.StartRequest;
import com.opentypeless.android.settings.RecognitionBackend;
import com.opentypeless.android.speech.core.SessionId;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

public final class AndroidSystemRecognitionProviderTest {
    @Test
    public void descriptorProbePrepareAndRegistryUseExactDeclaredSystemCapabilities() {
        ImmediateMainThread main = new ImmediateMainThread();
        FakeBackend backend = new FakeBackend(main);
        AndroidSystemRecognitionProvider provider = provider(
                RecognitionBackend.SYSTEM_ON_DEVICE, backend, main);

        assertTrue(Modifier.isFinal(AndroidSystemRecognitionProvider.class.getModifiers()));
        assertFalse(Modifier.isPublic(AndroidSystemRecognitionProvider.class.getModifiers()));
        assertFalse(Modifier.isPublic(RecognitionProvider.class.getModifiers()));
        assertEquals(
                ProviderDescriptor.declaredForBackend(RecognitionBackend.SYSTEM_ON_DEVICE),
                provider.descriptor());
        assertTrue(provider.probe() instanceof ProviderRegistry.ObservedAvailable);
        assertTrue(provider.prepare(request("probe", true)) instanceof RecognitionProvider.Prepared);

        ProviderRegistry registry = new ProviderRegistry();
        assertEquals(
                ProviderRegistry.RegistrationResult.REGISTERED,
                registry.register(provider.descriptor(), provider::probe, true));
        assertTrue(registry.probe(provider.descriptor().id())
                instanceof ProviderRegistry.ProbeAvailable);

        backend.available = false;
        Object unavailable = provider.prepare(request("unavailable", true));
        assertTrue(unavailable instanceof RecognitionProvider.NotPrepared);
        assertEquals(
                RecognitionRoute.FailureClass.UNAVAILABLE,
                ((RecognitionProvider.NotPrepared) unavailable).failureClass());
        backend.throwAvailable = true;
        assertEquals(
                RecognitionRoute.FailureClass.INTERNAL_ERROR,
                ((ProviderRegistry.ObservedUnavailable) provider.probe()).failureClass());

        assertThrows(
                IllegalArgumentException.class,
                () -> provider(RecognitionBackend.LOCAL_OFFLINE, backend, main));
    }

    @Test
    public void startRequestIsBoundedDefensivelyCopiedAndDiagnosticRedacted() {
        String secret = "prompt-secret-sentinel";
        List<String> mutableBiasingTerms = new ArrayList<>();
        mutableBiasingTerms.add(secret);
        StartRequest request = new StartRequest(
                SessionId.of("session-secret-sentinel"),
                "zh-CN",
                3,
                true,
                mutableBiasingTerms,
                30_000L);
        mutableBiasingTerms.clear();

        assertEquals(List.of(secret), request.biasingTerms());
        assertFalse(request.toString().contains(secret));
        assertFalse(request.toString().contains("session-secret-sentinel"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new StartRequest(
                        SessionId.of("zero"),
                        "",
                        1,
                        false,
                        List.of(),
                        0L));
        assertThrows(
                IllegalArgumentException.class,
                () -> new StartRequest(
                        SessionId.of("long"),
                        "",
                        1,
                        false,
                        List.of(),
                        ProviderCapabilities.APP_CAPTURE_LIMIT_MS + 1L));
        assertThrows(
                IllegalArgumentException.class,
                () -> new StartRequest(
                        SessionId.of("many"),
                        "",
                        1,
                        false,
                        java.util.Collections.nCopies(
                                AndroidSystemRecognitionProvider.MAX_BIASING_TERMS + 1,
                                "x"),
                        1L));

        StartRequest fromSnapshot = StartRequest.fromSnapshot(
                SessionId.of("snapshot"),
                new RecognitionRequest("zh-CN", "unused-caller", "unused-prompt", 3, true),
                new PersonalizationSnapshot(List.of(term("bias")), List.of()),
                1L);
        assertEquals(List.of("bias"), fromSnapshot.biasingTerms());
        assertFalse(fromSnapshot.toString().contains("unused-prompt"));
    }

    @Test
    public void emitsMonotonicValidatedEventsAndDropsEverythingAfterFinal() {
        ImmediateMainThread main = new ImmediateMainThread();
        FakeBackend backend = new FakeBackend(main);
        AndroidSystemRecognitionProvider provider = provider(
                RecognitionBackend.SYSTEM_DEFAULT, backend, main);
        RecordingSink sink = new RecordingSink(main);
        RecognitionProvider.Session session = provider.start(request("events", true), sink);

        backend.callback.onReady();
        backend.callback.onReady();
        backend.callback.onBeginningOfSpeech();
        backend.callback.onBeginningOfSpeech();
        backend.callback.onPartial("one");
        backend.callback.onPartial("two");
        backend.callback.onEndOfSpeech();
        backend.callback.onEndOfSpeech();
        backend.callback.onFinal("done");
        backend.callback.onPartial("late");
        backend.callback.onError(SpeechRecognizer.ERROR_SERVER, "late-secret");

        assertEquals(
                List.of(
                        "Preparing", "Ready", "SpeechStarted", "Partial", "Partial",
                        "Endpoint", "Final"),
                sink.kinds());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L), sink.sequences());
        RecognitionEvent.Partial second = (RecognitionEvent.Partial) sink.events.get(4);
        assertEquals(Long.valueOf(4L), second.revisionOf());
        assertEquals("done", ((RecognitionEvent.Final) sink.events.get(6)).text());

        RecognitionEventValidator validator = new RecognitionEventValidator(session.sessionId());
        for (RecognitionEvent event : sink.events) {
            assertEquals(
                    RecognitionEventValidator.Disposition.ACCEPTED,
                    validator.accept(event));
        }
        assertEquals(1, backend.startCount);
        assertTrue(backend.allLifecycleCallsOnMain);
        assertSessionReleased(session);
    }

    @Test
    public void synthesizesReadyAndEndpointAndSuppressesUnrequestedPartial() {
        ImmediateMainThread main = new ImmediateMainThread();
        FakeBackend backend = new FakeBackend(main);
        AndroidSystemRecognitionProvider provider = provider(
                RecognitionBackend.SYSTEM_DEFAULT, backend, main);
        RecordingSink sink = new RecordingSink(main);
        provider.start(request("synthesized", false), sink);

        backend.callback.onPartial("not requested");
        backend.callback.onBeginningOfSpeech();
        backend.callback.onFinal("final");

        assertEquals(
                List.of("Preparing", "Ready", "SpeechStarted", "Endpoint", "Final"),
                sink.kinds());
    }

    @Test
    public void repeatedStopWaitsForOneTerminalAndNeverCancelsSuccessfulFinal() {
        ImmediateMainThread main = new ImmediateMainThread();
        FakeBackend backend = new FakeBackend(main);
        AndroidSystemRecognitionProvider provider = provider(
                RecognitionBackend.SYSTEM_DEFAULT, backend, main);
        RecordingSink sink = new RecordingSink(main);
        RecognitionProvider.Session session = provider.start(request("stop", true), sink);

        session.stop();
        session.stop();
        assertEquals(1, backend.stopCount);
        assertEquals(List.of("Preparing"), sink.kinds());
        backend.callback.onReady();
        backend.callback.onPartial("late partial");
        backend.callback.onEndOfSpeech();
        backend.callback.onFinal("captured");
        session.stop();

        assertEquals(List.of("Preparing", "Endpoint", "Final"), sink.kinds());
        assertEquals(0, backend.cancelCount);
        assertEquals(1, backend.stopCount);
        assertSessionReleased(session);

        FakeBackend throwing = new FakeBackend(main);
        throwing.throwStop = true;
        AndroidSystemRecognitionProvider failing = provider(
                RecognitionBackend.SYSTEM_DEFAULT, throwing, main);
        RecordingSink failureSink = new RecordingSink(main);
        failing.start(request("stop-throw", true), failureSink).stop();
        assertFailure(failureSink, RecognitionRoute.FailureClass.INTERNAL_ERROR);
        assertEquals(1, throwing.cancelCount);
    }

    @Test
    public void cancelCloseAndProviderDestroyAreIdempotentAndDropStaleCallbacks() {
        ImmediateMainThread main = new ImmediateMainThread();
        FakeBackend backend = new FakeBackend(main);
        AndroidSystemRecognitionProvider provider = provider(
                RecognitionBackend.SYSTEM_DEFAULT, backend, main);
        RecordingSink first = new RecordingSink(main);
        RecognitionProvider.Session firstSession = provider.start(request("cancel-a", true), first);
        Backend.Callback stale = backend.callback;

        firstSession.cancel();
        firstSession.cancel();
        stale.onFinal("stale");
        assertEquals(List.of("Preparing", "Cancelled"), first.kinds());
        assertEquals(1, backend.cancelCount);
        assertSessionReleased(firstSession);

        RecordingSink second = new RecordingSink(main);
        RecognitionProvider.Session secondSession = provider.start(request("cancel-b", true), second);
        secondSession.close();
        assertEquals(List.of("Preparing", "Cancelled"), second.kinds());
        assertEquals(2, backend.cancelCount);

        RecordingSink third = new RecordingSink(main);
        provider.start(request("provider-close", true), third);
        provider.close();
        provider.close();
        assertEquals(List.of("Preparing", "Cancelled"), third.kinds());
        assertEquals(3, backend.cancelCount);
        assertEquals(1, backend.destroyCount);

        RecordingSink closed = new RecordingSink(main);
        provider.start(request("closed", true), closed);
        assertEquals(List.of("Failure"), closed.kinds());
        assertFailure(closed, RecognitionRoute.FailureClass.INTERNAL_ERROR);
        assertEquals(3, backend.startCount);
    }

    @Test
    public void busyStartFailsOnlyTheNewSessionAndLeavesTheActiveSessionAuthoritative() {
        ImmediateMainThread main = new ImmediateMainThread();
        FakeBackend backend = new FakeBackend(main);
        AndroidSystemRecognitionProvider provider = provider(
                RecognitionBackend.SYSTEM_DEFAULT, backend, main);
        RecordingSink active = new RecordingSink(main);
        RecordingSink rejected = new RecordingSink(main);

        provider.start(request("active", true), active);
        provider.start(request("busy", true), rejected);
        assertFailure(rejected, RecognitionRoute.FailureClass.RECOGNIZER_BUSY);
        assertEquals(1, backend.startCount);

        backend.callback.onFinal("active result");
        assertEquals(List.of("Preparing", "Endpoint", "Final"), active.kinds());
        assertEquals(List.of("Failure"), rejected.kinds());
    }

    @Test
    public void mapsAndroidErrorsToStableFailureClassesWithoutProviderMessages() {
        assertEquals(
                RecognitionRoute.FailureClass.OEM_MIC_BLOCKED,
                AndroidSystemRecognitionProvider.failureClass(
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                        SystemSpeechRecognizer.MICROPHONE_ACCESS_BLOCKED));
        assertEquals(
                RecognitionRoute.FailureClass.PERMISSION_DENIED,
                AndroidSystemRecognitionProvider.failureClass(
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                        "oem-secret-sentinel"));
        Object[][] cases = {
                {SpeechRecognizer.ERROR_AUDIO, RecognitionRoute.FailureClass.AUDIO_ERROR},
                {SpeechRecognizer.ERROR_NETWORK, RecognitionRoute.FailureClass.NETWORK_UNAVAILABLE},
                {SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                        RecognitionRoute.FailureClass.NETWORK_TIMEOUT},
                {SpeechRecognizer.ERROR_NO_MATCH, RecognitionRoute.FailureClass.NO_MATCH},
                {SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                        RecognitionRoute.FailureClass.RECOGNIZER_BUSY},
                {SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                        RecognitionRoute.FailureClass.SPEECH_TIMEOUT},
                {SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
                        RecognitionRoute.FailureClass.RATE_LIMITED},
                {SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                        RecognitionRoute.FailureClass.UNSUPPORTED_LANGUAGE},
                {SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                        RecognitionRoute.FailureClass.MODEL_MISSING},
                {SpeechRecognizer.ERROR_SERVER, RecognitionRoute.FailureClass.SERVER_ERROR},
                {SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
                        RecognitionRoute.FailureClass.SERVER_ERROR},
                {SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT,
                        RecognitionRoute.FailureClass.UNAVAILABLE},
                {SpeechRecognizer.ERROR_CLIENT, RecognitionRoute.FailureClass.INTERNAL_ERROR}
        };
        for (Object[] entry : cases) {
            assertEquals(
                    entry[1],
                    AndroidSystemRecognitionProvider.failureClass((Integer) entry[0], "secret"));
        }

        ImmediateMainThread main = new ImmediateMainThread();
        FakeBackend backend = new FakeBackend(main);
        RecordingSink sink = new RecordingSink(main);
        provider(RecognitionBackend.SYSTEM_DEFAULT, backend, main)
                .start(request("error", true), sink);
        backend.callback.onError(SpeechRecognizer.ERROR_SERVER, "provider-secret-sentinel");
        assertFailure(sink, RecognitionRoute.FailureClass.SERVER_ERROR);
        for (RecognitionEvent event : sink.events) {
            assertFalse(event.toString().contains("provider-secret-sentinel"));
        }
    }

    @Test
    public void malformedOversizedAndBlankProviderTextFailClosedOnce() {
        assertProtocolFailure("x".repeat(RecognitionEvent.MAX_TEXT_CODE_POINTS + 1));
        assertProtocolFailure("\uD800");

        ImmediateMainThread main = new ImmediateMainThread();
        FakeBackend backend = new FakeBackend(main);
        RecordingSink sink = new RecordingSink(main);
        provider(RecognitionBackend.SYSTEM_DEFAULT, backend, main)
                .start(request("blank", true), sink);
        backend.callback.onFinal("   ");
        backend.callback.onError(SpeechRecognizer.ERROR_SERVER, "late");
        assertFailure(sink, RecognitionRoute.FailureClass.NO_MATCH);
        assertEquals(0, backend.cancelCount);
    }

    @Test
    public void synchronousCallbacksAndAllLifecycleCallsAreMarshalledToMainThread() {
        ImmediateMainThread main = new ImmediateMainThread();
        FakeBackend backend = new FakeBackend(main);
        backend.onStart = () -> {
            backend.callback.onBeginningOfSpeech();
            backend.callback.onPartial("preview");
            backend.callback.onFinal("done");
        };
        RecordingSink sink = new RecordingSink(main);

        provider(RecognitionBackend.SYSTEM_DEFAULT, backend, main)
                .start(request("sync", true), sink);

        assertEquals(
                List.of(
                        "Preparing", "Ready", "SpeechStarted", "Partial", "Endpoint", "Final"),
                sink.kinds());
        assertTrue(backend.allLifecycleCallsOnMain);
        assertTrue(sink.allCallsOnMain);
    }

    @Test
    public void startAndConsumerFailuresReleaseAuthorityWithoutLeakingOrWedgingProvider() {
        ImmediateMainThread main = new ImmediateMainThread();
        FakeBackend throwing = new FakeBackend(main);
        throwing.throwStart = true;
        RecordingSink failed = new RecordingSink(main);
        AndroidSystemRecognitionProvider provider = provider(
                RecognitionBackend.SYSTEM_DEFAULT, throwing, main);
        provider.start(request("throw-start", true), failed);
        assertFailure(failed, RecognitionRoute.FailureClass.INTERNAL_ERROR);
        assertEquals(1, throwing.cancelCount);

        FakeBackend sinkBackend = new FakeBackend(main);
        AndroidSystemRecognitionProvider sinkProvider = provider(
                RecognitionBackend.SYSTEM_DEFAULT, sinkBackend, main);
        RecognitionProvider.Session abandoned = sinkProvider.start(
                request("sink-throw", true),
                event -> {
                    throw new IllegalStateException("consumer-secret-sentinel");
                });
        assertEquals(0, sinkBackend.startCount);
        assertSessionReleased(abandoned);

        RecordingSink recovered = new RecordingSink(main);
        sinkProvider.start(request("after-sink-throw", true), recovered);
        assertEquals(List.of("Preparing"), recovered.kinds());
        assertEquals(1, sinkBackend.startCount);
    }

    private static void assertProtocolFailure(String text) {
        ImmediateMainThread main = new ImmediateMainThread();
        FakeBackend backend = new FakeBackend(main);
        RecordingSink sink = new RecordingSink(main);
        provider(RecognitionBackend.SYSTEM_DEFAULT, backend, main)
                .start(request("protocol", true), sink);
        backend.callback.onPartial(text);
        backend.callback.onFinal("late");
        assertFailure(sink, RecognitionRoute.FailureClass.PROTOCOL_ERROR);
        assertEquals(1, backend.cancelCount);
    }

    private static void assertFailure(
            RecordingSink sink,
            RecognitionRoute.FailureClass expected) {
        RecognitionEvent last = sink.events.get(sink.events.size() - 1);
        assertTrue(last instanceof RecognitionEvent.Failure);
        assertEquals(expected, ((RecognitionEvent.Failure) last).failureClass());
    }

    private static void assertSessionReleased(RecognitionProvider.Session session) {
        try {
            Field request = session.getClass().getDeclaredField("request");
            Field sink = session.getClass().getDeclaredField("sink");
            request.setAccessible(true);
            sink.setAccessible(true);
            assertNull(request.get(session));
            assertNull(sink.get(session));
            assertFalse(session.toString().contains(session.sessionId().value()));
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static AndroidSystemRecognitionProvider provider(
            RecognitionBackend recognitionBackend,
            FakeBackend backend,
            ImmediateMainThread main) {
        return new AndroidSystemRecognitionProvider(recognitionBackend, backend, main);
    }

    private static StartRequest request(String id, boolean partialResults) {
        return new StartRequest(
                SessionId.of(id),
                "zh-CN",
                3,
                partialResults,
                List.of(),
                30_000L);
    }

    private static PersonalTerm term(String value) {
        return new PersonalTerm(1L, value, "", "", "", 0, true);
    }

    private static final class ImmediateMainThread
            implements AndroidSystemRecognitionProvider.MainThread {
        private int depth;

        @Override
        public void execute(Runnable action) {
            depth++;
            try {
                action.run();
            } finally {
                depth--;
            }
        }

        @Override
        public boolean isMainThread() {
            return depth > 0;
        }
    }

    private static final class FakeBackend implements Backend {
        private final ImmediateMainThread main;
        private boolean available = true;
        private boolean throwAvailable;
        private boolean throwStart;
        private boolean throwStop;
        private int startCount;
        private int stopCount;
        private int cancelCount;
        private int destroyCount;
        private boolean allLifecycleCallsOnMain = true;
        private Callback callback;
        private Runnable onStart;

        private FakeBackend(ImmediateMainThread main) {
            this.main = main;
        }

        @Override
        public boolean available(RecognitionBackend recognitionBackend) {
            if (throwAvailable) throw new IllegalStateException("availability-secret");
            return available;
        }

        @Override
        public void start(
                RecognitionBackend recognitionBackend,
                StartRequest request,
                Callback callback) {
            recordMain();
            startCount++;
            this.callback = callback;
            if (throwStart) throw new IllegalStateException("start-secret");
            if (onStart != null) onStart.run();
        }

        @Override
        public void stop() {
            recordMain();
            stopCount++;
            if (throwStop) throw new IllegalStateException("stop-secret");
        }

        @Override
        public void cancel() {
            recordMain();
            cancelCount++;
        }

        @Override
        public void destroy() {
            recordMain();
            destroyCount++;
        }

        private void recordMain() {
            allLifecycleCallsOnMain &= main.isMainThread();
        }
    }

    private static final class RecordingSink implements RecognitionProvider.EventSink {
        private final ImmediateMainThread main;
        private final List<RecognitionEvent> events = new ArrayList<>();
        private boolean allCallsOnMain = true;

        private RecordingSink(ImmediateMainThread main) {
            this.main = main;
        }

        @Override
        public void onEvent(RecognitionEvent event) {
            allCallsOnMain &= main.isMainThread();
            events.add(event);
        }

        private List<String> kinds() {
            return events.stream()
                    .map(event -> event.getClass().getSimpleName())
                    .toList();
        }

        private List<Long> sequences() {
            return events.stream().map(RecognitionEvent::sequence).toList();
        }
    }
}
