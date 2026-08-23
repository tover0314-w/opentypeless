package com.opentypeless.android.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CancellationException;

@RunWith(AndroidJUnit4.class)
public final class AudioCaptureInstrumentedTest {
    private static final AudioCapture.CaptureListener NO_EVENTS =
            new AudioCapture.CaptureListener() {};

    @Test
    public void attributedAdapterCreatesOpaqueAutomaticAndManualSessions() {
        Context context = ApplicationProvider.getApplicationContext();
        AudioCapture capture = new AndroidAudioCapture();
        capture.setAttributionContext(context);

        AudioCapture.Session automatic = capture.createSession(false);
        AudioCapture.Session manual = capture.createSession(true);

        assertFalse(automatic.userControlledEndpointing());
        assertTrue(manual.userControlledEndpointing());
        assertEquals("AudioCapture.Session", automatic.toString());
    }

    @Test
    public void stopAndCancelBeforeCaptureDoNotOpenTheMicrophone() {
        AudioCapture capture = new AndroidAudioCapture();
        AudioCapture.Session stopped = capture.createSession(false);
        capture.stop(stopped);
        assertThrows(
                IllegalStateException.class,
                () -> capture.record(stopped, 30, NO_EVENTS));

        AudioCapture.Session cancelled = capture.createSession(true);
        capture.cancel(cancelled);
        assertThrows(
                CancellationException.class,
                () -> capture.stream(
                        cancelled,
                        30,
                        NO_EVENTS,
                        (bytes, offset, length) -> {}));
    }

    @Test
    public void durationLimitRemainsBoundedOnTheAndroidRuntime() {
        assertEquals(5, AudioRecorder.boundedMaximumSeconds(Integer.MIN_VALUE));
        assertEquals(540, AudioRecorder.boundedMaximumSeconds(Integer.MAX_VALUE));
    }
}
