package com.opentypeless.android.ime;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.diagnostics.RecognitionRoute;
import com.opentypeless.android.settings.ProcessingMode;
import com.opentypeless.android.settings.RecognitionBackend;

import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class VoiceControllerTest {
    @Test
    public void controllerSurfaceIsClosedAndDoesNotExposeUiDatabaseOrEditorCapabilities() {
        assertArrayEquals(
                new String[] {"cancel", "start", "state", "stop"},
                declaredMethodNames(VoiceController.class));
        assertArrayEquals(
                new String[] {
                    "onBeginningOfSpeech",
                    "onError",
                    "onReadyForSpeech",
                    "onResult",
                    "onRoute",
                    "onState",
                    "onTranscript"
                },
                declaredMethodNames(VoiceController.Events.class));
        assertArrayEquals(
                new VoiceController.State[] {
                    VoiceController.State.IDLE,
                    VoiceController.State.RECORDING,
                    VoiceController.State.TRANSCRIBING,
                    VoiceController.State.POLISHING
                },
                VoiceController.State.values());

        for (Class<?> type : List.of(VoiceController.class, VoiceController.Events.class)) {
            for (Method method : type.getDeclaredMethods()) {
                assertCapabilityFree(method.getReturnType());
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertCapabilityFree(parameter);
                }
            }
        }
        assertTrue(VoiceController.class.isInterface());
        assertTrue(VoiceController.Events.class.isInterface());
        assertFalse(java.io.Serializable.class.isAssignableFrom(VoiceController.class));
    }

    @Test
    public void adapterMapsEveryLegacyStateWithoutCollapsingTransitions() {
        assertSame(
                VoiceController.State.IDLE,
                VoicePipelineAdapter.controllerState(VoicePipeline.State.IDLE));
        assertSame(
                VoiceController.State.RECORDING,
                VoicePipelineAdapter.controllerState(VoicePipeline.State.RECORDING));
        assertSame(
                VoiceController.State.TRANSCRIBING,
                VoicePipelineAdapter.controllerState(VoicePipeline.State.TRANSCRIBING));
        assertSame(
                VoiceController.State.POLISHING,
                VoicePipelineAdapter.controllerState(VoicePipeline.State.POLISHING));
        assertThrows(
                NullPointerException.class,
                () -> VoicePipelineAdapter.controllerState(null));
        assertThrows(NullPointerException.class, () -> new VoicePipelineAdapter(null));
    }

    @Test
    public void adapterForwardsEveryEventAndPreservesPayloadIdentity() {
        RecordingEvents events = new RecordingEvents();
        VoicePipeline.Listener bridge = VoicePipelineAdapter.listenerFor(events);
        RecognitionRoute route = RecognitionRoute.direct(RecognitionBackend.LOCAL_OFFLINE);
        TranscriptUpdate update = TranscriptUpdate.unstable(
                7L, "partial", TranscriptUpdate.Source.SPEECH_CORE_V2);
        VoiceResult voiceResult = VoiceResult.processed(
                "raw",
                "personalized",
                "final",
                "final",
                StageProvenance.Disposition.SKIPPED,
                StageProvenance.Disposition.APPLIED,
                StageProvenance.Disposition.ACCEPTED,
                StageProvenance.Disposition.PUBLISHED);
        DictationResult result = new DictationResult(
                voiceResult,
                DictationResult.Outcome.SMART_EDITED,
                ProcessingMode.VERBATIM,
                RecognitionBackend.LOCAL_OFFLINE,
                12L,
                false,
                false,
                List.of(),
                List.of(),
                "");

        bridge.onState(VoicePipeline.State.TRANSCRIBING, "state-message");
        bridge.onRoute(route);
        bridge.onReadyForSpeech();
        bridge.onBeginningOfSpeech();
        bridge.onTranscript(update);
        bridge.onResult(result);
        bridge.onError("error-message");

        assertSame(VoiceController.State.TRANSCRIBING, events.state);
        assertEquals("state-message", events.stateMessage);
        assertSame(route, events.route);
        assertTrue(events.ready);
        assertTrue(events.beginning);
        assertSame(update, events.update);
        assertSame(result, events.result);
        assertEquals("error-message", events.error);
        assertEquals(
                List.of("state", "route", "ready", "beginning", "transcript", "result", "error"),
                events.order);
        assertThrows(NullPointerException.class, () -> VoicePipelineAdapter.listenerFor(null));
    }

    private static String[] declaredMethodNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .map(Method::getName)
                .sorted()
                .toArray(String[]::new);
    }

    private static void assertCapabilityFree(Class<?> type) {
        String name = type.getName();
        assertFalse(name, name.startsWith("android.view."));
        assertFalse(name, name.startsWith("android.widget."));
        assertFalse(name, name.startsWith("android.database."));
        assertFalse(name, name.contains("InputConnection"));
        assertFalse(name, name.contains("Repository"));
        assertFalse(name, name.contains("Store"));
        assertFalse(name, name.contains("Activity"));
        assertFalse(name, name.contains("Service"));
        assertFalse(name, Modifier.isPublic(type.getModifiers()) && name.contains("Context"));
    }

    private static final class RecordingEvents implements VoiceController.Events {
        private final List<String> order = new ArrayList<>();
        private VoiceController.State state;
        private String stateMessage;
        private RecognitionRoute route;
        private boolean ready;
        private boolean beginning;
        private TranscriptUpdate update;
        private DictationResult result;
        private String error;

        @Override
        public void onState(VoiceController.State state, String message) {
            order.add("state");
            this.state = state;
            stateMessage = message;
        }

        @Override
        public void onRoute(RecognitionRoute route) {
            order.add("route");
            this.route = route;
        }

        @Override
        public void onReadyForSpeech() {
            order.add("ready");
            ready = true;
        }

        @Override
        public void onBeginningOfSpeech() {
            order.add("beginning");
            beginning = true;
        }

        @Override
        public void onTranscript(TranscriptUpdate update) {
            order.add("transcript");
            this.update = update;
        }

        @Override
        public void onResult(DictationResult result) {
            order.add("result");
            this.result = result;
        }

        @Override
        public void onError(String message) {
            order.add("error");
            error = message;
        }
    }
}
