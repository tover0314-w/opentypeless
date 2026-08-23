package com.opentypeless.android.recognition;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.config.RecognitionRoute;
import com.opentypeless.android.recognition.RecognitionFailureMapper.LocalAvailability;
import com.opentypeless.android.recognition.PrefixReplayPreviewProvider.PreviewSession;
import com.opentypeless.android.recognition.PrefixReplayPreviewProvider.StartRequest;
import com.opentypeless.android.speech.core.SessionId;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class PrefixReplayPreviewProviderTest {
    @Test
    public void descriptorProbeAndCapabilitiesDeclareBoundedPrefixReplayNotStreaming() {
        FakeBackend backend = new FakeBackend();
        PrefixReplayPreviewProvider provider = new PrefixReplayPreviewProvider(backend);
        ProviderCapabilities capabilities = provider.descriptor().capabilities();

        assertTrue(Modifier.isFinal(PrefixReplayPreviewProvider.class.getModifiers()));
        assertFalse(Modifier.isPublic(PrefixReplayPreviewProvider.class.getModifiers()));
        assertEquals("builtin.local-prefix-replay", provider.descriptor().id());
        assertFalse(capabilities.supportsStreaming());
        assertTrue(capabilities.supportsPartialRevision());
        assertFalse(capabilities.supportsEndpointing());
        assertTrue(capabilities.supportsOnDevice());
        assertFalse(capabilities.supportsAudioUpload());
        assertEquals(
                ProviderCapabilities.ImplementationKind.PREFIX_REPLAY,
                capabilities.implementationKind());
        assertEquals(Long.valueOf(30_000L), capabilities.maxAudioDurationMs());
        assertTrue(provider.probe() instanceof ProviderRegistry.ObservedAvailable);
        assertTrue(provider.prepare(request("ready", "zh-CN"))
                instanceof RecognitionProvider.Prepared);
    }

    @Test
    public void partialsAreFullyRevisableAndStopNeverClaimsStreamingOrFinal() {
        FakeBackend backend = new FakeBackend();
        PrefixReplayPreviewProvider provider = new PrefixReplayPreviewProvider(backend);
        RecordingSink sink = new RecordingSink();

        PreviewSession session = provider.start(request("preview", "zh-CN"), sink);
        backend.engine.emit("草稿一");
        backend.engine.emit("完全改写的草稿二");
        session.stop();
        backend.engine.emit("late secret");

        assertEquals(
                List.of("Preparing", "Ready", "Partial", "Partial", "Cancelled"),
                sink.kinds());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), sink.sequences());
        RecognitionEvent.Partial first = (RecognitionEvent.Partial) sink.events.get(2);
        RecognitionEvent.Partial second = (RecognitionEvent.Partial) sink.events.get(3);
        assertEquals(Integer.valueOf(0), first.stablePrefixLength());
        assertNull(first.revisionOf());
        assertEquals(Integer.valueOf(0), second.stablePrefixLength());
        assertEquals(Long.valueOf(first.sequence()), second.revisionOf());
        assertFalse(sink.events.stream().anyMatch(RecognitionEvent.Final.class::isInstance));
        assertFalse(sink.events.stream().anyMatch(RecognitionEvent.Endpoint.class::isInstance));
        assertEquals(1, backend.engine.cancelCount);
        assertReleased(session);
    }

    @Test
    public void pcmIsCopiedEvenAlignedZeroedAndCappedAtThirtySeconds() {
        FakeBackend backend = new FakeBackend();
        PrefixReplayPreviewProvider provider = new PrefixReplayPreviewProvider(backend);
        PreviewSession session = provider.start(request("audio", ""), new RecordingSink());
        byte[] caller = new byte[PrefixReplayPreviewProvider.MAX_PCM_BYTES + 9];
        Arrays.fill(caller, (byte) 7);

        session.acceptPcm(caller, caller.length);
        caller[0] = 99;
        session.acceptPcm(new byte[]{1, 2, 3, 4}, 4);

        assertEquals(1, backend.engine.acceptCount);
        assertEquals(PrefixReplayPreviewProvider.MAX_PCM_BYTES, session.acceptedPcmBytes());
        assertEquals(PrefixReplayPreviewProvider.MAX_PCM_BYTES, backend.engine.lastCopy.length);
        assertEquals(7, backend.engine.lastCopy[0]);
        assertArrayEquals(new byte[backend.engine.lastReference.length], backend.engine.lastReference);

        FakeBackend oddBackend = new FakeBackend();
        PreviewSession odd = new PrefixReplayPreviewProvider(oddBackend).start(
                request("odd", ""), new RecordingSink());
        odd.acceptPcm(new byte[]{1, 2, 3, 4, 5}, 5);
        assertArrayEquals(new byte[]{1, 2, 3, 4}, oddBackend.engine.lastCopy);
        assertEquals(4, odd.acceptedPcmBytes());
    }

    @Test
    public void requestIsOneUseAndBusyStartLeavesRejectedRequestAvailable() {
        FakeBackend backend = new FakeBackend();
        PrefixReplayPreviewProvider provider = new PrefixReplayPreviewProvider(backend);
        PreviewSession active = provider.start(request("active", ""), new RecordingSink());
        StartRequest busy = request("busy", "en-US");
        RecordingSink busySink = new RecordingSink();

        provider.start(busy, busySink);

        assertFailure(busySink, RecognitionRoute.FailureClass.RECOGNIZER_BUSY);
        assertTrue(busy.available());
        active.cancel();
        provider.start(busy, new RecordingSink());
        assertFalse(busy.available());
        RecordingSink reused = new RecordingSink();
        provider.start(busy, reused);
        assertFailure(reused, RecognitionRoute.FailureClass.RECOGNIZER_BUSY);
    }

    @Test
    public void everyAvailabilityFailureIsStableAndDoesNotOpenOrConsume() {
        assertAvailability(LocalAvailability.MODEL_MISSING,
                RecognitionRoute.FailureClass.MODEL_MISSING);
        assertAvailability(LocalAvailability.MODEL_CORRUPT,
                RecognitionRoute.FailureClass.PROTOCOL_ERROR);
        assertAvailability(LocalAvailability.LOW_MEMORY,
                RecognitionRoute.FailureClass.UNAVAILABLE);
        assertAvailability(LocalAvailability.UNSUPPORTED_ABI,
                RecognitionRoute.FailureClass.UNAVAILABLE);
        assertAvailability(LocalAvailability.SYSTEM_UNAVAILABLE,
                RecognitionRoute.FailureClass.UNAVAILABLE);

        FakeBackend backend = new FakeBackend();
        backend.throwAvailability = true;
        PrefixReplayPreviewProvider provider = new PrefixReplayPreviewProvider(backend);
        assertEquals(
                RecognitionRoute.FailureClass.INTERNAL_ERROR,
                ((ProviderRegistry.ObservedUnavailable) provider.probe()).failureClass());
    }

    @Test
    public void malformedOrOversizedPartialFailsOnceWithoutLeakingText() {
        for (String invalid : List.of(
                "\uD800",
                "x".repeat(RecognitionEvent.MAX_TEXT_CODE_POINTS + 1))) {
            FakeBackend backend = new FakeBackend();
            PrefixReplayPreviewProvider provider = new PrefixReplayPreviewProvider(backend);
            RecordingSink sink = new RecordingSink();
            provider.start(request("invalid", ""), sink);

            backend.engine.emit("   ");
            backend.engine.emit(invalid);
            backend.engine.emit("late-secret");

            assertEquals(List.of("Preparing", "Ready", "Failure"), sink.kinds());
            assertFailure(sink, RecognitionRoute.FailureClass.PROTOCOL_ERROR);
            assertEquals(1, backend.engine.cancelCount);
            assertFalse(sink.events.toString().contains("late-secret"));
            assertFalse(provider.toString().contains(invalid));
        }
    }

    @Test
    public void backendAndSinkFailuresRevokeSessionAndRemainContentFree() {
        FakeBackend openBackend = new FakeBackend();
        openBackend.openFailure = new IllegalStateException("open-secret");
        RecordingSink openSink = new RecordingSink();
        new PrefixReplayPreviewProvider(openBackend).start(
                request("open", ""), openSink);
        assertFailure(openSink, RecognitionRoute.FailureClass.INTERNAL_ERROR);

        FakeBackend acceptBackend = new FakeBackend();
        PrefixReplayPreviewProvider acceptProvider =
                new PrefixReplayPreviewProvider(acceptBackend);
        RecordingSink acceptSink = new RecordingSink();
        PreviewSession acceptSession = acceptProvider.start(
                request("accept", ""), acceptSink);
        acceptBackend.engine.acceptFailure = new IllegalStateException("pcm-secret");
        acceptSession.acceptPcm(new byte[]{1, 2}, 2);
        assertFailure(acceptSink, RecognitionRoute.FailureClass.INTERNAL_ERROR);
        assertEquals(1, acceptBackend.engine.cancelCount);
        assertFalse(acceptSink.events.toString().contains("secret"));

        FakeBackend sinkBackend = new FakeBackend();
        PrefixReplayPreviewProvider sinkProvider = new PrefixReplayPreviewProvider(sinkBackend);
        PreviewSession rejected = sinkProvider.start(
                request("sink", ""),
                event -> {
                    throw new IllegalStateException("sink-secret");
                });
        assertEquals(0, sinkBackend.openCount);
        assertReleased(rejected);
        RecordingSink next = new RecordingSink();
        sinkProvider.start(request("next", ""), next);
        assertEquals(List.of("Preparing", "Ready"), next.kinds());
    }

    @Test
    public void cancellationCloseAndLateCallbacksAreIdempotentAndRedacted() {
        FakeBackend backend = new FakeBackend();
        PrefixReplayPreviewProvider provider = new PrefixReplayPreviewProvider(backend);
        RecordingSink sink = new RecordingSink();
        PreviewSession session = provider.start(request("cancel-secret", "fr-FR"), sink);

        session.cancel();
        session.close();
        provider.close();
        provider.close();
        backend.engine.emit("late-secret");

        assertEquals(List.of("Preparing", "Ready", "Cancelled"), sink.kinds());
        assertEquals(1, backend.engine.cancelCount);
        assertEquals(1, backend.closeCount);
        assertFalse(session.toString().contains("cancel-secret"));
        assertFalse(provider.toString().contains("late-secret"));
        assertEquals(
                RecognitionRoute.FailureClass.UNAVAILABLE,
                ((ProviderRegistry.ObservedUnavailable) provider.probe()).failureClass());
    }

    @Test
    public void requestValidatesLanguageAndRedactsIdentity() {
        StartRequest request = request("language-secret", "zh-CN");
        assertFalse(request.toString().contains("language-secret"));
        assertFalse(request.toString().contains("zh-CN"));
        assertThrows(
                IllegalArgumentException.class,
                () -> request("bad-language", "\uD800"));
        assertThrows(
                IllegalArgumentException.class,
                () -> request("long-language", "a".repeat(36)));
        request.close();
        assertFalse(request.available());
    }

    private static void assertAvailability(
            LocalAvailability availability,
            RecognitionRoute.FailureClass expected) {
        FakeBackend backend = new FakeBackend();
        backend.availability = availability;
        PrefixReplayPreviewProvider provider = new PrefixReplayPreviewProvider(backend);
        StartRequest request = request("availability", "");
        assertEquals(
                expected,
                ((ProviderRegistry.ObservedUnavailable) provider.probe()).failureClass());
        assertEquals(
                expected,
                ((RecognitionProvider.NotPrepared) provider.prepare(request)).failureClass());
        RecordingSink sink = new RecordingSink();
        provider.start(request, sink);
        assertFailure(sink, expected);
        assertTrue(request.available());
        assertEquals(0, backend.openCount);
    }

    private static StartRequest request(String id, String language) {
        return new StartRequest(SessionId.of(id), language);
    }

    private static void assertFailure(
            RecordingSink sink,
            RecognitionRoute.FailureClass expected) {
        RecognitionEvent terminal = sink.events.get(sink.events.size() - 1);
        assertTrue(terminal instanceof RecognitionEvent.Failure);
        assertEquals(expected, ((RecognitionEvent.Failure) terminal).failureClass());
        assertEquals(1, sink.events.stream().filter(RecognitionEvent::terminal).count());
    }

    private static void assertReleased(PreviewSession session) {
        try {
            for (String fieldName : List.of("sink", "engine")) {
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
    }

    private static final class FakeBackend implements PrefixReplayPreviewProvider.Backend {
        private LocalAvailability availability = LocalAvailability.READY;
        private boolean throwAvailability;
        private RuntimeException openFailure;
        private FakeEngine engine;
        private int openCount;
        private int closeCount;

        @Override
        public LocalAvailability availability() {
            if (throwAvailability) throw new IllegalStateException("availability-secret");
            return availability;
        }

        @Override
        public PrefixReplayPreviewProvider.PreviewEngine open(
                String language,
                PrefixReplayPreviewProvider.PartialSink sink) {
            openCount++;
            if (openFailure != null) throw openFailure;
            engine = new FakeEngine(sink);
            return engine;
        }

        @Override
        public void close() {
            closeCount++;
        }
    }

    private static final class FakeEngine implements PrefixReplayPreviewProvider.PreviewEngine {
        private final PrefixReplayPreviewProvider.PartialSink sink;
        private RuntimeException acceptFailure;
        private byte[] lastReference;
        private byte[] lastCopy;
        private int acceptCount;
        private int cancelCount;

        private FakeEngine(PrefixReplayPreviewProvider.PartialSink sink) {
            this.sink = sink;
        }

        @Override
        public void accept(byte[] pcm, int length) {
            acceptCount++;
            lastReference = pcm;
            lastCopy = Arrays.copyOf(pcm, length);
            if (acceptFailure != null) throw acceptFailure;
        }

        @Override
        public void cancel() {
            cancelCount++;
        }

        void emit(String text) {
            sink.onPartial(text);
        }
    }
}
