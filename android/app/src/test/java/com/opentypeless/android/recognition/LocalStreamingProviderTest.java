package com.opentypeless.android.recognition;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.config.RecognitionRoute;
import com.opentypeless.android.recognition.LocalStreamingProvider.StartRequest;
import com.opentypeless.android.recognition.LocalStreamingProvider.StreamingSession;
import com.opentypeless.android.recognition.RecognitionFailureMapper.LocalAvailability;
import com.opentypeless.android.speech.core.SessionId;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;

public final class LocalStreamingProviderTest {
    @Test
    public void descriptorCapabilitiesAndAvailabilityAreExactAndContentFree() {
        FakeBackend backend = new FakeBackend();
        LocalStreamingProvider provider = provider(backend, new ManualWorker(), new ManualTimer());
        ProviderCapabilities capabilities = provider.descriptor().capabilities();

        assertTrue(Modifier.isFinal(LocalStreamingProvider.class.getModifiers()));
        assertFalse(Modifier.isPublic(LocalStreamingProvider.class.getModifiers()));
        assertEquals("builtin.local-streaming-paraformer", provider.descriptor().id());
        assertTrue(capabilities.supportsStreaming());
        assertTrue(capabilities.supportsPartialRevision());
        assertFalse(capabilities.supportsEndpointing());
        assertTrue(capabilities.supportsOnDevice());
        assertFalse(capabilities.supportsAudioUpload());
        assertEquals(
                ProviderCapabilities.ImplementationKind.NATIVE_STREAMING,
                capabilities.implementationKind());
        assertEquals(
                RecognitionRoute.PrivacyClass.ON_DEVICE,
                capabilities.privacyClass());
        assertEquals(Long.valueOf(540_000L), capabilities.maxAudioDurationMs());
        assertTrue(provider.probe() instanceof ProviderRegistry.ObservedAvailable);
        assertTrue(provider.prepare(request("available")) instanceof RecognitionProvider.Prepared);
        assertFalse(provider.toString().contains("available"));

        Object[][] unavailable = {
                {LocalAvailability.MODEL_MISSING, RecognitionRoute.FailureClass.MODEL_MISSING},
                {LocalAvailability.MODEL_CORRUPT, RecognitionRoute.FailureClass.PROTOCOL_ERROR},
                {LocalAvailability.LOW_MEMORY, RecognitionRoute.FailureClass.UNAVAILABLE},
                {LocalAvailability.UNSUPPORTED_ABI, RecognitionRoute.FailureClass.UNAVAILABLE},
                {LocalAvailability.SYSTEM_UNAVAILABLE, RecognitionRoute.FailureClass.UNAVAILABLE}
        };
        for (int index = 0; index < unavailable.length; index++) {
            FakeBackend unavailableBackend = new FakeBackend();
            unavailableBackend.availability = (LocalAvailability) unavailable[index][0];
            LocalStreamingProvider unavailableProvider = provider(
                    unavailableBackend, new ManualWorker(), new ManualTimer());
            StartRequest request = request("unavailable-" + index);
            RecordingSink sink = new RecordingSink();
            unavailableProvider.start(request, sink);
            assertFailure(sink, (RecognitionRoute.FailureClass) unavailable[index][1]);
            assertTrue(request.available());
            assertEquals(0, unavailableBackend.openCount);
            unavailableProvider.close();
        }
        provider.close();
    }

    @Test
    public void callerNeverBlocksOnOpenAndPcmPrecedesReadyInWorkerOrder() {
        FakeBackend backend = new FakeBackend();
        ManualWorker worker = new ManualWorker();
        ManualTimer timer = new ManualTimer();
        LocalStreamingProvider provider = provider(backend, worker, timer);
        RecordingSink sink = new RecordingSink();
        StreamingSession session = provider.start(request("stream-order"), sink);
        byte[] caller = {1, 2, 3, 4};

        assertEquals(List.of("Preparing"), sink.kinds());
        assertEquals(0, backend.openCount);
        assertTrue(session.acceptPcm(caller, caller.length));
        caller[0] = 99;
        assertEquals(2, worker.pending());

        worker.runNext();
        assertEquals(1, backend.openCount);
        FakeConnection connection = backend.connection();
        connection.ready();
        worker.runAll();
        assertArrayEquals(new byte[]{1, 2, 3, 4}, connection.frames.get(0));
        assertArrayEquals(new byte[4], connection.lastReference);

        connection.partial("hello");
        connection.partial("hello world");
        connection.partial("rewritten");
        connection.finishText = "final text";
        session.stop();
        worker.runAll();

        assertEquals(
                List.of("Preparing", "Ready", "Partial", "Partial", "Partial", "Final"),
                sink.kinds());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), sink.sequences());
        assertNull(((RecognitionEvent.Partial) sink.events.get(2)).revisionOf());
        assertNull(((RecognitionEvent.Partial) sink.events.get(3)).revisionOf());
        assertEquals(
                Long.valueOf(4L),
                ((RecognitionEvent.Partial) sink.events.get(4)).revisionOf());
        RecognitionEvent.Final terminal = (RecognitionEvent.Final) sink.events.get(5);
        assertEquals(Long.valueOf(1L), terminal.metadata().audioDurationMs());
        assertEquals(4, session.acceptedPcmBytes());
        assertFalse(session.acceptPcm(new byte[]{1, 2}, 2));
        assertReleased(session);
        provider.close();
    }

    @Test
    public void requestIsOneUseAndBusyRejectionDoesNotConsumeAnotherRequest() {
        FakeBackend backend = new FakeBackend();
        ManualWorker worker = new ManualWorker();
        LocalStreamingProvider provider = provider(backend, worker, new ManualTimer());
        StartRequest firstRequest = request("first-secret");
        StreamingSession first = provider.start(firstRequest, new RecordingSink());
        StartRequest busyRequest = request("busy-secret");
        RecordingSink busy = new RecordingSink();

        provider.start(busyRequest, busy);
        assertFailure(busy, RecognitionRoute.FailureClass.RECOGNIZER_BUSY);
        assertTrue(busyRequest.available());
        assertTrue(provider.prepare(firstRequest) instanceof RecognitionProvider.NotPrepared);
        first.cancel();

        StreamingSession second = provider.start(busyRequest, new RecordingSink());
        assertFalse(busyRequest.available());
        second.cancel();
        RecordingSink reused = new RecordingSink();
        provider.start(busyRequest, reused);
        assertFailure(reused, RecognitionRoute.FailureClass.INTERNAL_ERROR);
        assertFalse(firstRequest.toString().contains("first-secret"));
        provider.close();
    }

    @Test
    public void frameQueueAndSessionDurationBoundsFailClosedAndCancelTheBackend() {
        FakeBackend queuedBackend = new FakeBackend();
        ManualWorker queuedWorker = new ManualWorker();
        LocalStreamingProvider queuedProvider = provider(
                queuedBackend, queuedWorker, new ManualTimer());
        RecordingSink queuedSink = new RecordingSink();
        StreamingSession queued = queuedProvider.start(request("queue"), queuedSink);

        assertThrows(IllegalArgumentException.class, () -> queued.acceptPcm(new byte[0], 0));
        assertThrows(IllegalArgumentException.class, () -> queued.acceptPcm(new byte[3], 3));
        assertThrows(
                IllegalArgumentException.class,
                () -> queued.acceptPcm(
                        new byte[LocalStreamingProvider.MAX_PCM_FRAME_BYTES + 2],
                        LocalStreamingProvider.MAX_PCM_FRAME_BYTES + 2));
        byte[] frame = new byte[LocalStreamingProvider.MAX_PCM_FRAME_BYTES];
        for (int index = 0; index < 4; index++) assertTrue(queued.acceptPcm(frame, frame.length));
        assertFalse(queued.acceptPcm(new byte[]{1, 2}, 2));
        assertFailure(queuedSink, RecognitionRoute.FailureClass.AUDIO_ERROR);
        queuedWorker.runAll();
        assertEquals(0, queuedBackend.openCount);

        FakeBackend totalBackend = new FakeBackend();
        totalBackend.readyOnOpen = true;
        LocalStreamingProvider totalProvider = provider(
                totalBackend, new DirectWorker(), new ManualTimer());
        RecordingSink totalSink = new RecordingSink();
        StreamingSession total = totalProvider.start(request("total"), totalSink);
        int remaining = LocalStreamingProvider.MAX_TOTAL_PCM_BYTES;
        while (remaining > 0) {
            int length = Math.min(frame.length, remaining);
            assertTrue(total.acceptPcm(frame, length));
            remaining -= length;
        }
        assertEquals(LocalStreamingProvider.MAX_TOTAL_PCM_BYTES, total.acceptedPcmBytes());
        assertFalse(total.acceptPcm(new byte[]{1, 2}, 2));
        assertFailure(totalSink, RecognitionRoute.FailureClass.AUDIO_ERROR);
        assertEquals(1, totalBackend.connection().cancelCount);
        queuedProvider.close();
        totalProvider.close();
    }

    @Test
    public void readyAndFinishTimeoutsAreSingleTerminalAndReleaseAuthority() {
        FakeBackend backend = new FakeBackend();
        ManualWorker worker = new ManualWorker();
        ManualTimer timer = new ManualTimer();
        LocalStreamingProvider provider = provider(backend, worker, timer);
        RecordingSink readySink = new RecordingSink();
        provider.start(request("ready-timeout"), readySink);
        worker.runNext();
        timer.run(LocalStreamingProvider.READY_TIMEOUT_MS);
        timer.run(LocalStreamingProvider.READY_TIMEOUT_MS);
        assertFailure(readySink, RecognitionRoute.FailureClass.UNAVAILABLE);
        assertEquals(1, backend.connections.get(0).cancelCount);

        RecordingSink finishSink = new RecordingSink();
        StreamingSession finish = provider.start(request("finish-timeout"), finishSink);
        worker.runNext();
        FakeConnection connection = backend.connection();
        connection.ready();
        assertTrue(finish.acceptPcm(new byte[]{1, 2}, 2));
        worker.runAll();
        finish.stop();
        timer.run(LocalStreamingProvider.FINISH_TIMEOUT_MS);
        worker.runAll();
        assertFailure(finishSink, RecognitionRoute.FailureClass.UNAVAILABLE);
        assertEquals(0, connection.finishCount);
        assertEquals(1, connection.cancelCount);
        provider.close();
    }

    @Test
    public void emptyAudioCancelCloseAndLateCallbacksHaveOneTerminal() {
        FakeBackend backend = new FakeBackend();
        ManualWorker worker = new ManualWorker();
        ManualTimer timer = new ManualTimer();
        LocalStreamingProvider provider = provider(backend, worker, timer);
        RecordingSink emptySink = new RecordingSink();
        StreamingSession empty = provider.start(request("empty"), emptySink);
        empty.stop();
        empty.stop();
        worker.runAll();
        assertFailure(emptySink, RecognitionRoute.FailureClass.NO_MATCH);
        assertEquals(0, backend.openCount);

        RecordingSink cancelSink = new RecordingSink();
        StreamingSession cancelled = provider.start(request("cancel-secret"), cancelSink);
        worker.runNext();
        FakeConnection connection = backend.connection();
        connection.ready();
        cancelled.cancel();
        cancelled.close();
        connection.partial("late-secret");
        assertEquals(List.of("Preparing", "Ready", "Cancelled"), cancelSink.kinds());
        assertEquals(1, connection.cancelCount);
        assertFalse(cancelled.toString().contains("cancel-secret"));

        backend.closeFailure = new IllegalStateException("backend-secret");
        worker.closeFailure = new IllegalStateException("worker-secret");
        timer.closeFailure = new IllegalStateException("timer-secret");
        provider.close();
        provider.close();
        assertEquals(1, backend.closeCount);
        assertEquals(1, worker.closeCount);
        assertEquals(1, timer.closeCount);
        RecordingSink closed = new RecordingSink();
        provider.start(request("closed"), closed);
        assertFailure(closed, RecognitionRoute.FailureClass.UNAVAILABLE);
    }

    @Test
    public void runtimeAndProtocolFailuresAreStableRedactedAndReleaseConnections() {
        FakeBackend openBackend = new FakeBackend();
        openBackend.openFailure = new CancellationException("open-secret");
        ManualWorker openWorker = new ManualWorker();
        RecordingSink openSink = new RecordingSink();
        provider(openBackend, openWorker, new ManualTimer()).start(
                request("open-secret"), openSink);
        openWorker.runAll();
        assertEquals(List.of("Preparing", "Cancelled"), openSink.kinds());

        FakeBackend acceptBackend = new FakeBackend();
        ManualWorker acceptWorker = new ManualWorker();
        LocalStreamingProvider acceptProvider = provider(
                acceptBackend, acceptWorker, new ManualTimer());
        RecordingSink acceptSink = new RecordingSink();
        StreamingSession accept = acceptProvider.start(request("accept-secret"), acceptSink);
        acceptWorker.runNext();
        acceptBackend.connection().ready();
        acceptBackend.connection().acceptFailure = new IllegalStateException("pcm-secret");
        assertTrue(accept.acceptPcm(new byte[]{1, 2}, 2));
        acceptWorker.runAll();
        assertFailure(acceptSink, RecognitionRoute.FailureClass.INTERNAL_ERROR);
        assertEquals(1, acceptBackend.connection().cancelCount);

        FakeBackend finalBackend = new FakeBackend();
        ManualWorker finalWorker = new ManualWorker();
        LocalStreamingProvider finalProvider = provider(
                finalBackend, finalWorker, new ManualTimer());
        RecordingSink finalSink = new RecordingSink();
        StreamingSession invalidFinal = finalProvider.start(request("final-secret"), finalSink);
        finalWorker.runNext();
        finalBackend.connection().ready();
        assertTrue(invalidFinal.acceptPcm(new byte[]{1, 2}, 2));
        finalWorker.runAll();
        finalBackend.connection().finishText = "\uD800";
        invalidFinal.stop();
        finalWorker.runAll();
        assertFailure(finalSink, RecognitionRoute.FailureClass.PROTOCOL_ERROR);
        assertEquals(1, finalBackend.connection().cancelCount);
        assertFalse(finalSink.events.toString().contains("secret"));
        acceptProvider.close();
        finalProvider.close();
    }

    @Test
    public void sinkFailureRevokesBackendAndAllowsAFreshSession() {
        FakeBackend backend = new FakeBackend();
        ManualWorker worker = new ManualWorker();
        LocalStreamingProvider provider = provider(backend, worker, new ManualTimer());
        StreamingSession preparingFailure = provider.start(
                request("preparing-sink"),
                event -> {
                    throw new IllegalStateException("sink-secret");
                });
        assertEquals(0, worker.pending());
        assertReleased(preparingFailure);

        StreamingSession readyFailure = provider.start(
                request("ready-sink"),
                event -> {
                    if (event instanceof RecognitionEvent.Ready) {
                        throw new IllegalStateException("ready-secret");
                    }
                });
        worker.runNext();
        FakeConnection readyConnection = backend.connection();
        readyConnection.ready();
        assertEquals(1, readyConnection.cancelCount);
        assertReleased(readyFailure);

        RecordingSink fresh = new RecordingSink();
        StreamingSession freshSession = provider.start(request("fresh"), fresh);
        worker.runNext();
        backend.connection().ready();
        assertEquals(List.of("Preparing", "Ready"), fresh.kinds());
        freshSession.cancel();
        provider.close();
    }

    @Test
    public void exactTextBoundaryAndPartialRevisionRemainBounded() {
        FakeBackend validBackend = new FakeBackend();
        ManualWorker validWorker = new ManualWorker();
        LocalStreamingProvider validProvider = provider(
                validBackend, validWorker, new ManualTimer());
        RecordingSink validSink = new RecordingSink();
        StreamingSession valid = validProvider.start(request("long"), validSink);
        validWorker.runNext();
        FakeConnection connection = validBackend.connection();
        connection.ready();
        connection.partial("draft");
        connection.partial("");
        connection.finishText = "x".repeat(RecognitionEvent.MAX_TEXT_CODE_POINTS);
        assertTrue(valid.acceptPcm(new byte[]{1, 2}, 2));
        validWorker.runAll();
        valid.stop();
        validWorker.runAll();
        assertTrue(validSink.events.get(validSink.events.size() - 1)
                instanceof RecognitionEvent.Final);
        assertEquals(1L, validSink.events.stream().filter(RecognitionEvent::terminal).count());

        FakeBackend oversizedBackend = new FakeBackend();
        ManualWorker oversizedWorker = new ManualWorker();
        LocalStreamingProvider oversizedProvider = provider(
                oversizedBackend, oversizedWorker, new ManualTimer());
        RecordingSink oversizedSink = new RecordingSink();
        StreamingSession oversized = oversizedProvider.start(request("oversized"), oversizedSink);
        oversizedWorker.runNext();
        oversizedBackend.connection().ready();
        oversizedBackend.connection().finishText =
                "x".repeat(RecognitionEvent.MAX_TEXT_CODE_POINTS + 1);
        assertTrue(oversized.acceptPcm(new byte[]{1, 2}, 2));
        oversizedWorker.runAll();
        oversized.stop();
        oversizedWorker.runAll();
        assertFailure(oversizedSink, RecognitionRoute.FailureClass.PROTOCOL_ERROR);
        validProvider.close();
        oversizedProvider.close();
    }

    private static LocalStreamingProvider provider(
            FakeBackend backend,
            LocalStreamingProvider.Worker worker,
            ManualTimer timer) {
        return new LocalStreamingProvider(backend, worker, timer);
    }

    private static StartRequest request(String id) {
        return new StartRequest(SessionId.of(id));
    }

    private static void assertFailure(
            RecordingSink sink,
            RecognitionRoute.FailureClass expected) {
        RecognitionEvent terminal = sink.events.get(sink.events.size() - 1);
        assertTrue(terminal instanceof RecognitionEvent.Failure);
        assertEquals(expected, ((RecognitionEvent.Failure) terminal).failureClass());
        assertEquals(1L, sink.events.stream().filter(RecognitionEvent::terminal).count());
    }

    private static void assertReleased(StreamingSession session) {
        try {
            for (String fieldName : List.of("sink", "connection")) {
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

        private List<String> kinds() {
            return events.stream().map(event -> event.getClass().getSimpleName()).toList();
        }

        private List<Long> sequences() {
            return events.stream().map(RecognitionEvent::sequence).toList();
        }
    }

    private static final class FakeBackend implements LocalStreamingProvider.Backend {
        private final List<FakeConnection> connections = new ArrayList<>();
        private LocalAvailability availability = LocalAvailability.READY;
        private RuntimeException openFailure;
        private RuntimeException closeFailure;
        private boolean readyOnOpen;
        private int openCount;
        private int closeCount;

        @Override
        public LocalAvailability availability() {
            return availability;
        }

        @Override
        public LocalStreamingProvider.Connection open(
                LocalStreamingProvider.BackendListener listener) {
            openCount++;
            if (openFailure != null) throw openFailure;
            FakeConnection connection = new FakeConnection(listener);
            connections.add(connection);
            if (readyOnOpen) connection.ready();
            return connection;
        }

        @Override
        public void close() {
            closeCount++;
            if (closeFailure != null) throw closeFailure;
        }

        private FakeConnection connection() {
            return connections.get(connections.size() - 1);
        }
    }

    private static final class FakeConnection implements LocalStreamingProvider.Connection {
        private final LocalStreamingProvider.BackendListener listener;
        private final List<byte[]> frames = new ArrayList<>();
        private byte[] lastReference;
        private RuntimeException acceptFailure;
        private RuntimeException finishFailure;
        private String finishText = "final";
        private int finishCount;
        private int cancelCount;

        private FakeConnection(LocalStreamingProvider.BackendListener listener) {
            this.listener = listener;
        }

        @Override
        public void acceptPcm(byte[] pcm, int length) {
            lastReference = pcm;
            frames.add(Arrays.copyOf(pcm, length));
            if (acceptFailure != null) throw acceptFailure;
        }

        @Override
        public String finish() {
            finishCount++;
            if (finishFailure != null) throw finishFailure;
            return finishText;
        }

        @Override
        public void cancel() {
            cancelCount++;
        }

        private void ready() {
            listener.onReady();
        }

        private void partial(String text) {
            listener.onPartial(text);
        }
    }

    private static class ManualWorker implements LocalStreamingProvider.Worker {
        private final ArrayDeque<Runnable> actions = new ArrayDeque<>();
        private RuntimeException closeFailure;
        private int closeCount;

        @Override
        public void execute(Runnable action) {
            actions.addLast(action);
        }

        @Override
        public void close() {
            closeCount++;
            if (closeFailure != null) throw closeFailure;
            actions.clear();
        }

        private int pending() {
            return actions.size();
        }

        private void runNext() {
            Runnable action = actions.pollFirst();
            if (action == null) throw new AssertionError("no worker action");
            action.run();
        }

        private void runAll() {
            while (!actions.isEmpty()) runNext();
        }
    }

    private static final class DirectWorker extends ManualWorker {
        @Override
        public void execute(Runnable action) {
            action.run();
        }
    }

    private static final class ManualTimer implements LocalStreamingProvider.Timer {
        private final List<TimerEntry> entries = new ArrayList<>();
        private RuntimeException closeFailure;
        private int closeCount;

        @Override
        public LocalStreamingProvider.Ticket schedule(Runnable action, long delayMillis) {
            TimerEntry entry = new TimerEntry(action, delayMillis);
            entries.add(entry);
            return () -> entry.cancelled = true;
        }

        @Override
        public void close() {
            closeCount++;
            if (closeFailure != null) throw closeFailure;
            entries.clear();
        }

        private void run(long delayMillis) {
            List<TimerEntry> snapshot = new ArrayList<>(entries);
            for (TimerEntry entry : snapshot) {
                if (!entry.cancelled && !entry.ran && entry.delayMillis == delayMillis) {
                    entry.ran = true;
                    entry.action.run();
                }
            }
        }
    }

    private static final class TimerEntry {
        private final Runnable action;
        private final long delayMillis;
        private boolean cancelled;
        private boolean ran;

        private TimerEntry(Runnable action, long delayMillis) {
            this.action = action;
            this.delayMillis = delayMillis;
        }
    }
}
