package com.opentypeless.android.speech.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.speech.core.RevisionOrigin;
import com.opentypeless.android.speech.core.RevisionStage;
import com.opentypeless.android.speech.core.SegmentJoin;
import com.opentypeless.android.speech.core.SegmentRevision;
import com.opentypeless.android.speech.core.SessionId;
import com.opentypeless.android.speech.core.TerminalReason;
import com.opentypeless.android.speech.core.TokenEvidence;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import org.junit.Test;

public final class EngineTraceJsonTest {
    private static final SessionId SESSION = SessionId.of("json-session");
    private static final String ENGINE_ID = "system-on-device";

    @Test
    public void deterministicRoundTripPreservesRouteCapabilitiesAndEvidence() {
        EngineTrace trace = evidenceTrace();

        String first = EngineTraceJson.encode(trace);
        String second = EngineTraceJson.encode(trace);
        EngineTrace decoded = EngineTraceJson.decode(first);

        assertEquals(first, second);
        assertEquals(trace, decoded);
        assertTrue(first.contains("\"processingLocation\":\"ANDROID_SYSTEM_SERVICE\""));
        assertTrue(first.contains("\"TOKEN_STABILITY\""));
        assertTrue(first.contains("\"stable\":true"));
    }

    @Test
    public void utf8ByteLimitIsAppliedBeforeParsing() {
        String multiByte = "{\"value\":\"中文中文中文\"}";
        EngineTraceLimits tiny = new EngineTraceLimits(20, 10, 100, 10);

        assertTrue(multiByte.length() < 20);
        assertThrows(
                IllegalArgumentException.class,
                () -> EngineTraceJson.decode(multiByte, tiny));
    }

    @Test
    public void unknownSchemaEventAndCapabilityFailClosed() {
        assertThrows(
                IllegalArgumentException.class,
                () -> EngineTraceJson.decode(
                        "{\"schemaVersion\":2,\"engine\":{},\"sessionId\":\"s\",\"events\":[]}"));

        String unknownCapability = EngineTraceJson.encode(evidenceTrace())
                .replace("TOKEN_STABILITY", "MAGICAL_STABILITY");
        assertThrows(
                IllegalArgumentException.class,
                () -> EngineTraceJson.decode(unknownCapability));

        String unknownEvent = EngineTraceJson.encode(evidenceTrace())
                .replaceFirst("\"prepare\"", "\"mystery\"");
        assertThrows(IllegalArgumentException.class, () -> EngineTraceJson.decode(unknownEvent));
    }

    @Test
    public void textTokenAndEventBoundsAreEnforcedOnEncodeAndDecode() {
        EngineTraceLimits eventLimit = new EngineTraceLimits(100_000, 2, 100, 100);
        assertThrows(
                IllegalArgumentException.class,
                () -> EngineTraceJson.encode(evidenceTrace(), eventLimit));

        EngineTraceLimits textLimit = new EngineTraceLimits(100_000, 100, 3, 100);
        assertThrows(
                IllegalArgumentException.class,
                () -> EngineTraceJson.encode(evidenceTrace(), textLimit));

        String json = EngineTraceJson.encode(evidenceTrace());
        assertThrows(
                IllegalArgumentException.class,
                () -> EngineTraceJson.decode(json, textLimit));
    }

    @Test
    public void malformedTokenSpansCannotEnterReplayFixture() {
        String json = EngineTraceJson.encode(evidenceTrace())
                .replace("\"endCodePoint\":5", "\"endCodePoint\":99");
        assertThrows(IllegalArgumentException.class, () -> EngineTraceJson.decode(json));
    }

    @Test
    public void emptyCapabilitiesRemainEmptyRatherThanReceivingDefaults() {
        EngineDescriptor batch = new EngineDescriptor(
                "batch",
                "Batch",
                "v1",
                ProcessingLocation.NETWORK,
                EngineCapabilities.NONE);
        EngineTrace trace = EngineTrace.of(
                batch,
                SESSION,
                List.of(new EngineEvent.Prepare(SESSION, "batch", 1L)));

        EngineTrace decoded = EngineTraceJson.decode(EngineTraceJson.encode(trace));

        assertTrue(decoded.engine().capabilities().available().isEmpty());
    }

    private static EngineTrace evidenceTrace() {
        EngineDescriptor engine = new EngineDescriptor(
                ENGINE_ID,
                "Android on-device recognizer",
                "component@build",
                ProcessingLocation.ANDROID_SYSTEM_SERVICE,
                EngineCapabilities.of(
                        EngineCapability.LIVE_REVISIONS,
                        EngineCapability.SEGMENT_FINALS,
                        EngineCapability.TOKEN_TIMESTAMPS,
                        EngineCapability.TOKEN_STABILITY,
                        EngineCapability.CONFIDENCE,
                        EngineCapability.ON_DEVICE));
        TokenEvidence token = new TokenEvidence(
                "hello",
                0,
                5,
                OptionalDouble.of(0.75d),
                Optional.of(true),
                OptionalLong.of(10L),
                OptionalLong.of(220L));
        SegmentRevision revision = new SegmentRevision(
                SESSION,
                1L,
                1L,
                RevisionStage.LIVE,
                "hello",
                List.of(token),
                0L,
                300L,
                RevisionOrigin.STREAM_ASR,
                false);
        return EngineTrace.of(
                engine,
                SESSION,
                List.of(
                        new EngineEvent.Prepare(SESSION, ENGINE_ID, 1L),
                        new EngineEvent.Ready(SESSION, ENGINE_ID, 2L),
                        new EngineEvent.OpenSegment(
                                SESSION, ENGINE_ID, 3L, 1L, SegmentJoin.NONE),
                        new EngineEvent.Transcript(ENGINE_ID, 4L, revision),
                        new EngineEvent.HardBoundary(SESSION, ENGINE_ID, 5L, 1L),
                        new EngineEvent.SealSegment(SESSION, ENGINE_ID, 6L, 1L),
                        new EngineEvent.StopRequested(SESSION, ENGINE_ID, 7L),
                        new EngineEvent.CaptureEnded(
                                SESSION, ENGINE_ID, 8L, TerminalReason.USER_FINISH)));
    }
}
