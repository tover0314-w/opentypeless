package com.opentypeless.android.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;

public final class AudioCaptureTest {
    private static final AudioCapture.CaptureListener NO_EVENTS =
            new AudioCapture.CaptureListener() {};

    @Test
    public void contractIsCaptureOnlyAndDoesNotExposeRecorderImplementation() {
        Set<String> methods = Arrays.stream(AudioCapture.class.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());
        assertEquals(Set.of(
                "setAttributionContext",
                "createSession",
                "record",
                "stream",
                "stop",
                "cancel"), methods);

        for (Method method : AudioCapture.class.getDeclaredMethods()) {
            String signature = method.toGenericString();
            assertFalse(signature, signature.contains("AudioRecorder"));
            assertFalse(signature, signature.contains("RecordingSession"));
            assertFalse(signature, signature.contains("InputConnection"));
            assertFalse(signature, signature.contains("okhttp"));
            assertFalse(signature, signature.contains("Repository"));
        }
        assertEquals(Set.of("userControlledEndpointing"),
                Arrays.stream(AudioCapture.Session.class.getDeclaredMethods())
                        .filter(method -> Modifier.isPublic(method.getModifiers()))
                        .map(Method::getName)
                        .collect(Collectors.toSet()));
    }

    @Test
    public void sessionIsOpaqueOwnedAndPreservesEndpointingPolicy() {
        AndroidAudioCapture capture = new AndroidAudioCapture();
        AudioCapture.Session automatic = capture.createSession(false);
        AudioCapture.Session manual = capture.createSession(true);

        assertFalse(automatic.userControlledEndpointing());
        assertTrue(manual.userControlledEndpointing());
        assertEquals("AudioCapture.Session", automatic.toString());
        assertThrows(IllegalArgumentException.class,
                () -> new AndroidAudioCapture().stop(automatic));
    }

    @Test
    public void stopBeforeCaptureIsStableAndDoesNotTouchAndroidMicrophone() {
        AndroidAudioCapture capture = new AndroidAudioCapture();
        AudioCapture.Session session = capture.createSession(false);

        capture.stop(session);
        capture.stop(session);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> capture.record(session, 30, NO_EVENTS));
        assertEquals("Recording stopped before audio capture started", error.getMessage());
    }

    @Test
    public void cancellationDominatesStopAndPropagatesWithoutStartingMicrophone() {
        AndroidAudioCapture capture = new AndroidAudioCapture();
        AudioCapture.Session session = capture.createSession(true);

        capture.stop(session);
        capture.cancel(session);
        capture.cancel(session);

        CancellationException error = assertThrows(
                CancellationException.class,
                () -> capture.stream(session, 30, NO_EVENTS, (bytes, offset, length) -> {}));
        assertEquals("Recording cancelled", error.getMessage());
    }

    @Test
    public void nullAndForeignSessionCapabilitiesFailBeforeCapture() {
        AndroidAudioCapture first = new AndroidAudioCapture();
        AndroidAudioCapture second = new AndroidAudioCapture();
        AudioCapture.Session session = first.createSession(false);

        assertThrows(IllegalArgumentException.class, () -> first.stop(null));
        assertThrows(IllegalArgumentException.class, () -> second.cancel(session));
        assertThrows(IllegalArgumentException.class,
                () -> first.record(session, 30, null));
        assertThrows(IllegalArgumentException.class,
                () -> first.stream(session, 30, NO_EVENTS, null));
    }
}
