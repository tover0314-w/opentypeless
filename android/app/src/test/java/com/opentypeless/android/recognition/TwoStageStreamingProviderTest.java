package com.opentypeless.android.recognition;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.config.RecognitionRoute;
import com.opentypeless.android.recognition.TwoStageStreamingProvider.StartRequest;
import com.opentypeless.android.recognition.TwoStageStreamingProvider.StreamingSession;
import com.opentypeless.android.settings.RecognitionBackend;
import com.opentypeless.android.speech.core.SessionId;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class TwoStageStreamingProviderTest {
    @Test
    public void descriptorProbePrepareAndChildAvailabilityAreExactAndUnregistered() {
        FakeStreamingProvider streaming = new FakeStreamingProvider();
        FakeFinalProvider finalizer = new FakeFinalProvider();
        ManualWorker worker = new ManualWorker();
        TwoStageStreamingProvider provider = provider(streaming, finalizer, worker);
        StartRequest request = request("available", "zh-CN", true);

        assertTrue(Modifier.isFinal(TwoStageStreamingProvider.class.getModifiers()));
        assertFalse(Modifier.isPublic(TwoStageStreamingProvider.class.getModifiers()));
        assertEquals("builtin.local-two-stage", provider.descriptor().id());
        assertEquals(ProviderCapabilities.localTwoStage(), provider.descriptor().capabilities());
        assertTrue(provider.probe() instanceof ProviderRegistry.ObservedAvailable);
        assertTrue(provider.prepare(request) instanceof RecognitionProvider.Prepared);
        assertTrue(request.available());

        finalizer.availability = RecognitionRoute.FailureClass.MODEL_MISSING;
        assertEquals(
                RecognitionRoute.FailureClass.MODEL_MISSING,
                ((ProviderRegistry.ObservedUnavailable) provider.probe()).failureClass());
        assertEquals(
                RecognitionRoute.FailureClass.MODEL_MISSING,
                ((RecognitionProvider.NotPrepared) provider.prepare(request)).failureClass());
        finalizer.availability = null;
        streaming.availability = RecognitionRoute.FailureClass.PROTOCOL_ERROR;
        assertEquals(
                RecognitionRoute.FailureClass.PROTOCOL_ERROR,
                ((ProviderRegistry.ObservedUnavailable) provider.probe()).failureClass());

        provider.close();
        assertEquals(
                RecognitionRoute.FailureClass.UNAVAILABLE,
                ((ProviderRegistry.ObservedUnavailable) provider.probe()).failureClass());
        assertEquals(1, streaming.closeCount);
        assertEquals(1, finalizer.closeCount);
        assertEquals(1, worker.closeCount);
    }

    @Test
    public void streamingPreviewThenSenseVoiceProducesOneMonotonicFactSafeFinal()
            throws Exception {
        FakeStreamingProvider streaming = new FakeStreamingProvider();
        streaming.readyOnStart = true;
        FakeFinalProvider finalizer = new FakeFinalProvider();
        finalizer.autoFinal = "Call Alice at 12:30.";
        finalizer.metadata = new RecognitionMetadata("en-US", 0.9f, 1L);
        ManualWorker worker = new ManualWorker();
        TwoStageStreamingProvider provider = provider(streaming, finalizer, worker);
        RecordingSink sink = new RecordingSink();
        StreamingSession session = provider.start(request("safe", "en-US", true), sink);
        byte[] pcm = new byte[]{1, 2, 3, 4};

        streaming.emitPartial("Call Alice");
        streaming.emitPartial("Call Alice at 12:30");
        assertTrue(session.acceptPcm(pcm, pcm.length));
        Arrays.fill(pcm, (byte) 99);
        session.stop();
        worker.runAll();

        assertEquals(
                List.of("Preparing", "Ready", "Partial", "Partial", "Final"),
                sink.kinds());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), sink.sequences());
        RecognitionEvent.Final terminal = (RecognitionEvent.Final) sink.last();
        assertEquals("Call Alice at 12:30.", terminal.text());
        assertEquals(finalizer.metadata, terminal.metadata());
        assertEquals(4, session.acceptedPcmBytes());
        assertFalse(session.acceptPcm(new byte[]{1, 2}, 2));
        assertEquals(1, streaming.session.cancelCount);
        assertEquals(1, finalizer.startCount);
        assertArrayEquals("RIFF".getBytes(StandardCharsets.US_ASCII),
                Arrays.copyOfRange(finalizer.wav, 0, 4));
        assertArrayEquals(new byte[]{1, 2, 3, 4},
                Arrays.copyOfRange(finalizer.wav, 44, 48));
        assertChildSessionsReleased(session);
        assertFalse(session.toString().contains("Alice"));
        provider.close();
    }

    @Test
    public void changedFactsCannotOverwritePreviewButNoPreviewAcceptsFinalizerText() {
        FakeStreamingProvider streaming = new FakeStreamingProvider();
        streaming.readyOnStart = true;
        FakeFinalProvider finalizer = new FakeFinalProvider();
        finalizer.autoFinal = "Send 99 dollars to Bob";
        ManualWorker worker = new ManualWorker();
        TwoStageStreamingProvider provider = provider(streaming, finalizer, worker);
        RecordingSink guarded = new RecordingSink();
        StreamingSession first = provider.start(request("guarded", "", false), guarded);
        streaming.emitPartial("Send 12 dollars to Alice");
        first.acceptPcm(new byte[]{1, 2}, 2);
        first.stop();
        worker.runAll();

        assertEquals("Send 12 dollars to Alice",
                ((RecognitionEvent.Final) guarded.last()).text());

        finalizer.autoFinal = "Final without preview";
        RecordingSink noPreview = new RecordingSink();
        StreamingSession second = provider.start(request("no-preview", "", false), noPreview);
        second.acceptPcm(new byte[]{3, 4}, 2);
        second.stop();
        worker.runAll();
        assertEquals("Final without preview",
                ((RecognitionEvent.Final) noPreview.last()).text());
        provider.close();
    }

    @Test
    public void streamingRuntimeFailureDegradesToFinalOnlyWithoutExposingChildTerminal() {
        FakeStreamingProvider streaming = new FakeStreamingProvider();
        streaming.failureOnStart = RecognitionRoute.FailureClass.INTERNAL_ERROR;
        FakeFinalProvider finalizer = new FakeFinalProvider();
        finalizer.autoFinal = "离线终稿";
        ManualWorker worker = new ManualWorker();
        TwoStageStreamingProvider provider = provider(streaming, finalizer, worker);
        RecordingSink sink = new RecordingSink();
        StreamingSession session = provider.start(request("degrade", "zh-CN", false), sink);

        assertEquals(List.of("Preparing", "Ready"), sink.kinds());
        assertTrue(session.acceptPcm(new byte[]{1, 2}, 2));
        session.stop();
        worker.runAll();

        assertEquals(List.of("Preparing", "Ready", "Final"), sink.kinds());
        assertEquals("离线终稿", ((RecognitionEvent.Final) sink.last()).text());
        assertEquals(1, finalizer.startCount);
        provider.close();
    }

    @Test
    public void frameAndTotalAudioBoundsFailClosedBeforeFinalization() {
        FakeStreamingProvider streaming = new FakeStreamingProvider();
        streaming.readyOnStart = true;
        FakeFinalProvider finalizer = new FakeFinalProvider();
        ManualWorker worker = new ManualWorker();
        TwoStageStreamingProvider provider = provider(streaming, finalizer, worker);
        RecordingSink sink = new RecordingSink();
        StreamingSession session = provider.start(request("bounds", "", false), sink);

        assertThrows(IllegalArgumentException.class, () -> session.acceptPcm(new byte[0], 0));
        assertThrows(IllegalArgumentException.class, () -> session.acceptPcm(new byte[3], 3));
        assertThrows(IllegalArgumentException.class, () -> session.acceptPcm(
                new byte[TwoStageStreamingProvider.MAX_PCM_FRAME_BYTES + 2],
                TwoStageStreamingProvider.MAX_PCM_FRAME_BYTES + 2));
        byte[] frame = new byte[TwoStageStreamingProvider.MAX_PCM_FRAME_BYTES];
        int remaining = TwoStageStreamingProvider.MAX_TOTAL_PCM_BYTES;
        while (remaining > 0) {
            int length = Math.min(frame.length, remaining);
            assertTrue(session.acceptPcm(frame, length));
            remaining -= length;
        }
        assertFalse(session.acceptPcm(new byte[]{1, 2}, 2));
        assertFailure(sink, RecognitionRoute.FailureClass.AUDIO_ERROR);
        assertEquals(0, finalizer.startCount);
        assertEquals(TwoStageStreamingProvider.MAX_TOTAL_PCM_BYTES,
                session.acceptedPcmBytes());

        assertThrows(IllegalArgumentException.class,
                () -> request("bad-language", "\uD800", false));
        assertThrows(IllegalArgumentException.class,
                () -> request("long-language", "x".repeat(64), false));
        provider.close();
    }

    @Test
    public void emptyCancelCloseAndLateEventsRemainSingleTerminalAndZeroAudio() throws Exception {
        FakeStreamingProvider streaming = new FakeStreamingProvider();
        streaming.readyOnStart = true;
        FakeFinalProvider finalizer = new FakeFinalProvider();
        ManualWorker worker = new ManualWorker();
        TwoStageStreamingProvider provider = provider(streaming, finalizer, worker);
        RecordingSink emptySink = new RecordingSink();
        StreamingSession empty = provider.start(request("empty", "", false), emptySink);
        empty.stop();
        empty.stop();
        assertFailure(emptySink, RecognitionRoute.FailureClass.NO_MATCH);
        assertEquals(0, finalizer.startCount);

        RecordingSink cancelledSink = new RecordingSink();
        StreamingSession cancelled = provider.start(
                request("cancel-secret", "", false), cancelledSink);
        cancelled.acceptPcm(new byte[]{7, 8, 9, 10}, 4);
        streaming.emitPartial("private-preview");
        cancelled.cancel();
        cancelled.close();
        streaming.emitPartial("late-secret");
        worker.runAll();

        assertEquals(List.of("Preparing", "Ready", "Partial", "Cancelled"),
                cancelledSink.kinds());
        assertEquals(0, finalizer.startCount);
        assertAudioReleased(cancelled);
        assertFalse(cancelled.toString().contains("private-preview"));

        provider.close();
        provider.close();
        assertEquals(1, streaming.closeCount);
        assertEquals(1, finalizer.closeCount);
        assertEquals(1, worker.closeCount);
    }

    @Test
    public void finalizerFailureCancellationProtocolAndSinkFailureReleaseAuthority() {
        FakeStreamingProvider streaming = new FakeStreamingProvider();
        streaming.readyOnStart = true;
        FakeFinalProvider finalizer = new FakeFinalProvider();
        finalizer.autoFailure = RecognitionRoute.FailureClass.NO_MATCH;
        ManualWorker worker = new ManualWorker();
        TwoStageStreamingProvider provider = provider(streaming, finalizer, worker);
        RecordingSink failed = new RecordingSink();
        StreamingSession failure = provider.start(request("failure", "", false), failed);
        failure.acceptPcm(new byte[]{1, 2}, 2);
        failure.stop();
        worker.runAll();
        assertFailure(failed, RecognitionRoute.FailureClass.NO_MATCH);

        finalizer.autoFailure = null;
        finalizer.autoCancelled = true;
        RecordingSink cancelled = new RecordingSink();
        StreamingSession cancellation = provider.start(
                request("final-cancel", "", false), cancelled);
        cancellation.acceptPcm(new byte[]{1, 2}, 2);
        cancellation.stop();
        worker.runAll();
        assertTrue(cancelled.last() instanceof RecognitionEvent.Cancelled);

        finalizer.autoCancelled = false;
        finalizer.wrongSession = true;
        RecordingSink protocol = new RecordingSink();
        StreamingSession wrong = provider.start(request("wrong", "", false), protocol);
        wrong.acceptPcm(new byte[]{1, 2}, 2);
        wrong.stop();
        worker.runAll();
        assertFailure(protocol, RecognitionRoute.FailureClass.PROTOCOL_ERROR);

        finalizer.wrongSession = false;
        RecordingSink explosive = new RecordingSink();
        explosive.throwOnEvent = 1;
        StreamingSession detached = provider.start(
                request("sink-secret", "", false), explosive);
        assertFalse(detached.acceptPcm(new byte[]{1, 2}, 2));
        assertEquals(3, finalizer.startCount);
        provider.close();
    }

    @Test
    public void cancellingNeverHoldsCompositeLockWhileWaitingForChildLock()
            throws Exception {
        LockingStreamingProvider streaming = new LockingStreamingProvider();
        FakeFinalProvider finalizer = new FakeFinalProvider();
        ManualWorker worker = new ManualWorker();
        TwoStageStreamingProvider provider =
                new TwoStageStreamingProvider(streaming, finalizer, worker);
        RecordingSink sink = new RecordingSink();
        StreamingSession session = provider.start(request("lock-order", "", false), sink);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread emitter = daemonThread("str006-child-callback", () -> {
            try {
                streaming.emitPartialWhileHoldingChildLock();
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        });
        emitter.start();
        assertTrue(streaming.childLockHeld.await(2, TimeUnit.SECONDS));

        Thread canceller = daemonThread("str006-parent-cancel", () -> {
            try {
                session.cancel();
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        });
        canceller.start();
        assertTrue(streaming.cancelAttempted.await(2, TimeUnit.SECONDS));
        streaming.allowCallback.countDown();

        emitter.join(2_000L);
        canceller.join(2_000L);
        assertFalse("child callback must not deadlock", emitter.isAlive());
        assertFalse("parent cancellation must not deadlock", canceller.isAlive());
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertTrue(sink.last() instanceof RecognitionEvent.Cancelled);
        assertEquals(1, streaming.session.cancelCount);
        assertChildSessionsReleased(session);
        provider.close();
    }

    @Test
    public void requestIsOneUseAndBusyRejectionDoesNotConsumeAnotherRequest() {
        FakeStreamingProvider streaming = new FakeStreamingProvider();
        streaming.readyOnStart = true;
        FakeFinalProvider finalizer = new FakeFinalProvider();
        ManualWorker worker = new ManualWorker();
        TwoStageStreamingProvider provider = provider(streaming, finalizer, worker);
        StartRequest firstRequest = request("first-secret", "", false);
        StreamingSession first = provider.start(firstRequest, new RecordingSink());
        StartRequest busyRequest = request("busy-secret", "", false);
        RecordingSink busy = new RecordingSink();

        provider.start(busyRequest, busy);
        assertFailure(busy, RecognitionRoute.FailureClass.RECOGNIZER_BUSY);
        assertTrue(busyRequest.available());
        assertFalse(firstRequest.available());
        first.cancel();

        StreamingSession second = provider.start(busyRequest, new RecordingSink());
        assertFalse(busyRequest.available());
        second.cancel();
        RecordingSink reused = new RecordingSink();
        provider.start(busyRequest, reused);
        assertFailure(reused, RecognitionRoute.FailureClass.INTERNAL_ERROR);
        assertFalse(firstRequest.toString().contains("first-secret"));
        assertFalse(busyRequest.toString().contains("busy-secret"));
        provider.close();
    }

    @Test
    public void workerRejectionAndCancelBeforeQueuedFinalizationNeverStartFinalizer() {
        FakeStreamingProvider streaming = new FakeStreamingProvider();
        streaming.readyOnStart = true;
        FakeFinalProvider finalizer = new FakeFinalProvider();
        ManualWorker rejectedWorker = new ManualWorker();
        rejectedWorker.reject = true;
        TwoStageStreamingProvider rejectedProvider =
                provider(streaming, finalizer, rejectedWorker);
        RecordingSink rejectedSink = new RecordingSink();
        StreamingSession rejected = rejectedProvider.start(
                request("worker-reject", "", false), rejectedSink);
        rejected.acceptPcm(new byte[]{1, 2}, 2);
        rejected.stop();
        assertFailure(rejectedSink, RecognitionRoute.FailureClass.INTERNAL_ERROR);
        assertEquals(0, finalizer.startCount);
        rejectedProvider.close();

        FakeStreamingProvider secondStreaming = new FakeStreamingProvider();
        secondStreaming.readyOnStart = true;
        FakeFinalProvider secondFinalizer = new FakeFinalProvider();
        ManualWorker queuedWorker = new ManualWorker();
        TwoStageStreamingProvider queuedProvider =
                provider(secondStreaming, secondFinalizer, queuedWorker);
        RecordingSink cancelledSink = new RecordingSink();
        StreamingSession queued = queuedProvider.start(
                request("queued", "", false), cancelledSink);
        queued.acceptPcm(new byte[]{1, 2, 3, 4}, 4);
        queued.stop();
        queued.cancel();
        queuedWorker.runAll();
        assertTrue(cancelledSink.last() instanceof RecognitionEvent.Cancelled);
        assertEquals(0, secondFinalizer.startCount);
        queuedProvider.close();
    }

    private static TwoStageStreamingProvider provider(
            FakeStreamingProvider streaming,
            FakeFinalProvider finalizer,
            ManualWorker worker) {
        return new TwoStageStreamingProvider(streaming, finalizer, worker);
    }

    private static StartRequest request(String id, String language, boolean itn) {
        return new StartRequest(SessionId.of(id), language, itn);
    }

    private static void assertFailure(
            RecordingSink sink,
            RecognitionRoute.FailureClass failureClass) {
        assertTrue(sink.last() instanceof RecognitionEvent.Failure);
        assertEquals(failureClass,
                ((RecognitionEvent.Failure) sink.last()).failureClass());
    }

    private static void assertAudioReleased(StreamingSession session) throws Exception {
        Field audioField = session.getClass().getDeclaredField("audio");
        audioField.setAccessible(true);
        Object audio = audioField.get(session);
        Field bytesField = audio.getClass().getDeclaredField("bytes");
        bytesField.setAccessible(true);
        byte[] bytes = (byte[]) bytesField.get(audio);
        assertArrayEquals(new byte[bytes.length], bytes);
    }

    private static void assertChildSessionsReleased(StreamingSession session) throws Exception {
        for (String name : List.of("streamingSession", "finalizerSession")) {
            Field field = session.getClass().getDeclaredField(name);
            field.setAccessible(true);
            assertEquals(null, field.get(session));
        }
    }

    private static Thread daemonThread(String name, Runnable action) {
        Thread thread = new Thread(action, name);
        thread.setDaemon(true);
        return thread;
    }

    private static final class RecordingSink implements RecognitionProvider.EventSink {
        final List<RecognitionEvent> events = new ArrayList<>();
        int throwOnEvent = -1;

        @Override
        public void onEvent(RecognitionEvent event) {
            if (events.size() + 1 == throwOnEvent) {
                throw new IllegalStateException("sink-secret");
            }
            events.add(event);
        }

        RecognitionEvent last() {
            assertFalse(events.isEmpty());
            return events.get(events.size() - 1);
        }

        List<String> kinds() {
            List<String> kinds = new ArrayList<>();
            for (RecognitionEvent event : events) {
                kinds.add(event.getClass().getSimpleName());
            }
            return kinds;
        }

        List<Long> sequences() {
            List<Long> sequences = new ArrayList<>();
            for (RecognitionEvent event : events) sequences.add(event.sequence());
            return sequences;
        }
    }

    private static final class ManualWorker implements TwoStageStreamingProvider.Worker {
        final ArrayDeque<Runnable> actions = new ArrayDeque<>();
        boolean reject;
        int closeCount;

        @Override
        public void execute(Runnable action) {
            if (reject) throw new IllegalStateException("worker-secret");
            actions.add(action);
        }

        void runAll() {
            while (!actions.isEmpty()) actions.removeFirst().run();
        }

        @Override
        public void close() {
            closeCount++;
            actions.clear();
        }
    }

    private static final class FakeStreamingProvider
            implements RecognitionProvider<LocalStreamingProvider.StartRequest> {
        final ProviderDescriptor descriptor = new ProviderDescriptor(
                "test.streaming",
                "Test Streaming",
                ProviderCapabilities.localStreamingParaformer());
        RecognitionRoute.FailureClass availability;
        RecognitionRoute.FailureClass failureOnStart;
        boolean readyOnStart;
        int closeCount;
        long sequence;
        SessionId sessionId;
        EventSink sink;
        FakeStreamingSession session;

        @Override
        public ProviderDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public ProviderRegistry.ProbeObservation probe() {
            return availability == null
                    ? new ProviderRegistry.ObservedAvailable(descriptor.capabilities())
                    : new ProviderRegistry.ObservedUnavailable(availability);
        }

        @Override
        public PreparationResult prepare(LocalStreamingProvider.StartRequest request) {
            return availability == null ? new Prepared(descriptor) : new NotPrepared(availability);
        }

        @Override
        public LocalStreamingProvider.StreamingSession start(
                LocalStreamingProvider.StartRequest request,
                EventSink sink) {
            request.close();
            this.sessionId = request.sessionId();
            this.sink = sink;
            sequence = 0L;
            session = new FakeStreamingSession(sessionId);
            if (readyOnStart) emit(new RecognitionEvent.Ready(sessionId, ++sequence));
            if (failureOnStart != null) {
                emit(new RecognitionEvent.Failure(sessionId, ++sequence, failureOnStart));
            }
            return session;
        }

        void emitPartial(String text) {
            if (sink != null) emit(new RecognitionEvent.Partial(
                    sessionId, ++sequence, text, null, null));
        }

        private void emit(RecognitionEvent event) {
            sink.onEvent(event);
        }

        @Override
        public void close() {
            closeCount++;
        }

        private final class FakeStreamingSession
                implements LocalStreamingProvider.StreamingSession {
            private final SessionId id;
            int accepted;
            int cancelCount;

            private FakeStreamingSession(SessionId id) {
                this.id = id;
            }

            @Override
            public boolean acceptPcm(byte[] pcm, int length) {
                accepted += length;
                return true;
            }

            @Override
            public int acceptedPcmBytes() {
                return accepted;
            }

            @Override
            public SessionId sessionId() {
                return id;
            }

            @Override
            public void stop() {
                cancel();
            }

            @Override
            public void cancel() {
                cancelCount++;
            }

            @Override
            public void close() {
                cancel();
            }
        }
    }

    private static final class FakeFinalProvider
            implements RecognitionProvider<SenseVoiceFinalProvider.StartRequest> {
        final ProviderDescriptor descriptor =
                ProviderDescriptor.declaredForBackend(RecognitionBackend.LOCAL_OFFLINE);
        RecognitionRoute.FailureClass availability;
        RecognitionRoute.FailureClass autoFailure;
        String autoFinal;
        RecognitionMetadata metadata = new RecognitionMetadata(null, null, 1L);
        boolean autoCancelled;
        boolean wrongSession;
        int startCount;
        int closeCount;
        byte[] wav;

        @Override
        public ProviderDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public ProviderRegistry.ProbeObservation probe() {
            return availability == null
                    ? new ProviderRegistry.ObservedAvailable(descriptor.capabilities())
                    : new ProviderRegistry.ObservedUnavailable(availability);
        }

        @Override
        public PreparationResult prepare(SenseVoiceFinalProvider.StartRequest request) {
            return availability == null ? new Prepared(descriptor) : new NotPrepared(availability);
        }

        @Override
        public Session start(SenseVoiceFinalProvider.StartRequest request, EventSink sink) {
            startCount++;
            wav = copiedWav(request);
            SessionId requested = request.sessionId();
            SessionId emitted = wrongSession ? SessionId.of("foreign-session") : requested;
            sink.onEvent(new RecognitionEvent.Preparing(emitted, 1L));
            sink.onEvent(new RecognitionEvent.Ready(emitted, 2L));
            if (autoFailure != null) {
                sink.onEvent(new RecognitionEvent.Failure(emitted, 3L, autoFailure));
            } else if (autoCancelled) {
                sink.onEvent(new RecognitionEvent.Cancelled(emitted, 3L));
            } else {
                String result = autoFinal == null ? "default final" : autoFinal;
                sink.onEvent(new RecognitionEvent.Final(emitted, 3L, result, metadata));
            }
            return new FakeFinalSession(requested);
        }

        @Override
        public void close() {
            closeCount++;
        }

        private static byte[] copiedWav(SenseVoiceFinalProvider.StartRequest request) {
            try {
                Field field = SenseVoiceFinalProvider.StartRequest.class.getDeclaredField("wav");
                field.setAccessible(true);
                byte[] value = (byte[]) field.get(request);
                assertNotNull(value);
                return Arrays.copyOf(value, value.length);
            } catch (ReflectiveOperationException error) {
                throw new AssertionError(error);
            }
        }

        private static final class FakeFinalSession implements Session {
            private final SessionId sessionId;
            int cancelCount;

            private FakeFinalSession(SessionId sessionId) {
                this.sessionId = sessionId;
            }

            @Override
            public SessionId sessionId() {
                return sessionId;
            }

            @Override
            public void stop() {
                cancel();
            }

            @Override
            public void cancel() {
                cancelCount++;
            }

            @Override
            public void close() {
                cancel();
            }
        }
    }

    private static final class LockingStreamingProvider
            implements RecognitionProvider<LocalStreamingProvider.StartRequest> {
        private final Object childLock = new Object();
        private final ProviderDescriptor descriptor = new ProviderDescriptor(
                "test.locking-streaming",
                "Locking Streaming",
                ProviderCapabilities.localStreamingParaformer());
        private final CountDownLatch childLockHeld = new CountDownLatch(1);
        private final CountDownLatch cancelAttempted = new CountDownLatch(1);
        private final CountDownLatch allowCallback = new CountDownLatch(1);
        private SessionId sessionId;
        private EventSink sink;
        private LockingSession session;

        @Override
        public ProviderDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public ProviderRegistry.ProbeObservation probe() {
            return new ProviderRegistry.ObservedAvailable(descriptor.capabilities());
        }

        @Override
        public PreparationResult prepare(LocalStreamingProvider.StartRequest request) {
            return new Prepared(descriptor);
        }

        @Override
        public LocalStreamingProvider.StreamingSession start(
                LocalStreamingProvider.StartRequest request,
                EventSink eventSink) {
            sessionId = request.sessionId();
            sink = eventSink;
            session = new LockingSession(sessionId);
            return session;
        }

        private void emitPartialWhileHoldingChildLock() throws InterruptedException {
            synchronized (childLock) {
                childLockHeld.countDown();
                if (!allowCallback.await(2, TimeUnit.SECONDS)) {
                    throw new AssertionError("callback release timed out");
                }
                sink.onEvent(new RecognitionEvent.Partial(
                        sessionId, 1L, "bounded preview", null, null));
            }
        }

        @Override
        public void close() {}

        private final class LockingSession
                implements LocalStreamingProvider.StreamingSession {
            private final SessionId id;
            private int cancelCount;

            private LockingSession(SessionId id) {
                this.id = id;
            }

            @Override
            public boolean acceptPcm(byte[] pcm, int length) {
                return true;
            }

            @Override
            public int acceptedPcmBytes() {
                return 0;
            }

            @Override
            public SessionId sessionId() {
                return id;
            }

            @Override
            public void stop() {
                cancel();
            }

            @Override
            public void cancel() {
                cancelAttempted.countDown();
                synchronized (childLock) {
                    cancelCount++;
                }
            }

            @Override
            public void close() {
                cancel();
            }
        }
    }
}
