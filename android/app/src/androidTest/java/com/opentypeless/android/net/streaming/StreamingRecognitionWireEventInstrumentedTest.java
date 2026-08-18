package com.opentypeless.android.net.streaming;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.opentypeless.android.recognition.RecognitionEvent;
import com.opentypeless.android.recognition.RecognitionMetadata;
import com.opentypeless.android.speech.core.SessionId;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Android-runtime proof for the transport-neutral STR-001 wire contract. */
@RunWith(AndroidJUnit4.class)
public final class StreamingRecognitionWireEventInstrumentedTest {
    private static final SessionId SESSION = SessionId.of("xiaomi-wire-session");

    @Test
    public void androidJsonRuntimeRoundTripsUnicodeFinalEvent() {
        RecognitionEvent.Final event = new RecognitionEvent.Final(
                SESSION,
                Long.MAX_VALUE,
                "小米😀stream",
                new RecognitionMetadata("zh-Hans-CN", 0.75f, 1_234L));

        String encoded = StreamingRecognitionWireEvent.encode(event);

        assertEquals(event, StreamingRecognitionWireEvent.decode(encoded));
        assertTrue(encoded.length() < StreamingRecognitionWireEvent.MAX_JSON_UTF16_UNITS);
    }

    @Test
    public void sessionStreamRejectsForeignAndPostTerminalEventsWithoutPoisoning() {
        StreamingRecognitionWireEvent.Stream stream =
                new StreamingRecognitionWireEvent.Stream(SESSION);
        RecognitionEvent.Ready ready = new RecognitionEvent.Ready(SESSION, 1L);
        RecognitionEvent.Final terminal = new RecognitionEvent.Final(
                SESSION, 2L, "done", RecognitionMetadata.empty());

        assertTrue(stream.accept(StreamingRecognitionWireEvent.encode(ready))
                instanceof StreamingRecognitionWireEvent.Accepted);
        assertEquals(
                new StreamingRecognitionWireEvent.Rejected(
                        StreamingRecognitionWireEvent.Rejection.FOREIGN_SESSION),
                stream.accept(StreamingRecognitionWireEvent.encode(
                        new RecognitionEvent.Ready(SessionId.of("foreign"), 3L))));
        assertTrue(stream.accept(StreamingRecognitionWireEvent.encode(terminal))
                instanceof StreamingRecognitionWireEvent.Accepted);
        assertEquals(
                new StreamingRecognitionWireEvent.Rejected(
                        StreamingRecognitionWireEvent.Rejection.AFTER_TERMINAL),
                stream.accept(StreamingRecognitionWireEvent.encode(
                        new RecognitionEvent.Cancelled(SESSION, 4L))));
    }
}
