package com.opentypeless.android.speech.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.speech.core.ReductionDisposition;
import com.opentypeless.android.speech.core.RevisionOrigin;
import com.opentypeless.android.speech.core.RevisionStage;
import com.opentypeless.android.speech.core.SegmentJoin;
import com.opentypeless.android.speech.core.SegmentRevision;
import com.opentypeless.android.speech.core.SessionId;
import com.opentypeless.android.speech.core.TerminalReason;
import com.opentypeless.android.speech.core.TokenEvidence;
import com.opentypeless.android.speech.core.VoiceDraftLimits;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import org.junit.Test;

public final class EngineTraceReplayerTest {
    private static final SessionId SESSION = SessionId.of("engine-replay-session");
    private static final String ENGINE_ID = "local-two-pass";

    @Test
    public void normalizedTwoPassTraceReplaysIntoOneAuditableDraft() {
        EngineTrace trace = completeTrace(fullLocalEngine());

        EngineReplayReport report = new EngineTraceReplayer().replay(trace);

        assertEquals("你好世界。", report.draft().renderedText());
        assertEquals(TerminalReason.USER_FINISH, report.draft().terminalReason());
        assertEquals(0L, report.rejectedCount());
        assertEquals(ENGINE_ID, report.actualEngine().engineId());
        assertEquals(ProcessingLocation.ON_DEVICE, report.actualEngine().processingLocation());
        assertTrue(report.actualEngine().capabilities().supports(EngineCapability.LIVE_REVISIONS));
        assertFalse(report.actualEngine().capabilities().supports(EngineCapability.TOKEN_STABILITY));
    }

    @Test
    public void duplicateAndOutOfOrderSourceEventsNeverReachCoreTwice() {
        EngineDescriptor engine = fullLocalEngine();
        EngineEvent transcript = transcript(
                4L,
                SegmentRevision.text(
                        SESSION,
                        1L,
                        1L,
                        RevisionStage.LIVE,
                        "first",
                        RevisionOrigin.STREAM_ASR,
                        false));
        List<EngineEvent> events = new ArrayList<>();
        events.add(new EngineEvent.Prepare(SESSION, ENGINE_ID, 1L));
        events.add(new EngineEvent.Ready(SESSION, ENGINE_ID, 2L));
        events.add(new EngineEvent.OpenSegment(SESSION, ENGINE_ID, 3L, 1L, SegmentJoin.NONE));
        events.add(transcript);
        events.add(transcript);
        events.add(transcript(
                3L,
                SegmentRevision.text(
                        SESSION,
                        1L,
                        2L,
                        RevisionStage.LIVE,
                        "must be rejected",
                        RevisionOrigin.STREAM_ASR,
                        false)));
        events.add(transcript(
                5L,
                SegmentRevision.text(
                        SESSION,
                        1L,
                        3L,
                        RevisionStage.LIVE,
                        "latest",
                        RevisionOrigin.STREAM_ASR,
                        false)));

        EngineReplayReport report =
                new EngineTraceReplayer().replay(EngineTrace.of(engine, SESSION, events));

        assertEquals("latest", report.draft().renderedText());
        assertEquals(ReplayDisposition.IGNORED, report.steps().get(4).disposition());
        assertEquals(
                ReplayDisposition.REJECTED_SOURCE_ORDER,
                report.steps().get(5).disposition());
        assertEquals(1L, report.rejectedCount());
    }

    @Test
    public void missingLiveCapabilityIsRejectedInsteadOfInferredFromName() {
        EngineDescriptor finalOnly = new EngineDescriptor(
                "famous-streaming-name",
                "Famous Streaming Engine",
                "unknown",
                ProcessingLocation.NETWORK,
                EngineCapabilities.of(EngineCapability.SEGMENT_FINALS));
        List<EngineEvent> events = bootstrap("famous-streaming-name");
        events.add(new EngineEvent.Transcript(
                "famous-streaming-name",
                4L,
                SegmentRevision.text(
                        SESSION,
                        1L,
                        1L,
                        RevisionStage.LIVE,
                        "not final",
                        RevisionOrigin.STREAM_ASR,
                        false)));

        EngineReplayReport report =
                new EngineTraceReplayer().replay(EngineTrace.of(finalOnly, SESSION, events));

        assertEquals("", report.draft().renderedText());
        assertEquals(
                ReplayDisposition.REJECTED_CAPABILITY,
                report.steps().get(3).disposition());
    }

    @Test
    public void finalOnlyEngineMayEmitProviderFinalAfterHardBoundary() {
        String engineId = "batch-final";
        EngineDescriptor finalOnly = new EngineDescriptor(
                engineId,
                "Batch final",
                "v1",
                ProcessingLocation.NETWORK,
                EngineCapabilities.of(EngineCapability.SEGMENT_FINALS));
        List<EngineEvent> events = bootstrap(engineId);
        events.add(new EngineEvent.HardBoundary(SESSION, engineId, 4L, 1L));
        events.add(new EngineEvent.Transcript(
                engineId,
                5L,
                SegmentRevision.text(
                        SESSION,
                        1L,
                        1L,
                        RevisionStage.LIVE,
                        "batch final",
                        RevisionOrigin.STREAM_ASR,
                        true)));
        events.add(new EngineEvent.SealSegment(SESSION, engineId, 6L, 1L));

        EngineReplayReport report =
                new EngineTraceReplayer().replay(EngineTrace.of(finalOnly, SESSION, events));

        assertEquals("batch final", report.draft().renderedText());
        assertEquals(0L, report.rejectedCount());
    }

    @Test
    public void tokenStabilityTimestampAndConfidenceNeedIndependentClaims() {
        EngineDescriptor noEvidence = new EngineDescriptor(
                "plain-live",
                "Plain live",
                "v1",
                ProcessingLocation.NETWORK,
                EngineCapabilities.of(EngineCapability.LIVE_REVISIONS));
        TokenEvidence token = new TokenEvidence(
                "hello",
                0,
                5,
                OptionalDouble.of(0.9d),
                Optional.of(true),
                OptionalLong.of(0L),
                OptionalLong.of(200L));
        SegmentRevision revision = new SegmentRevision(
                SESSION,
                1L,
                1L,
                RevisionStage.LIVE,
                "hello",
                List.of(token),
                0L,
                200L,
                RevisionOrigin.STREAM_ASR,
                false);
        List<EngineEvent> events = bootstrap("plain-live");
        events.add(new EngineEvent.Transcript("plain-live", 4L, revision));

        EngineReplayReport report =
                new EngineTraceReplayer().replay(EngineTrace.of(noEvidence, SESSION, events));

        assertEquals(
                ReplayDisposition.REJECTED_CAPABILITY,
                report.steps().get(3).disposition());
        assertTrue(report.steps().get(3).detail().contains("confidence"));
    }

    @Test
    public void engineCannotSmuggleUserOrPersonalizationRevision() {
        List<EngineEvent> events = bootstrap(ENGINE_ID);
        events.add(new EngineEvent.Transcript(
                ENGINE_ID,
                4L,
                SegmentRevision.text(
                        SESSION,
                        1L,
                        1L,
                        RevisionStage.REFINED,
                        "oracle correction",
                        RevisionOrigin.PERSONALIZATION,
                        true)));

        EngineReplayReport report = new EngineTraceReplayer()
                .replay(EngineTrace.of(fullLocalEngine(), SESSION, events));

        assertEquals(
                ReplayDisposition.REJECTED_CAPABILITY,
                report.steps().get(3).disposition());
        assertEquals("", report.draft().renderedText());
    }

    @Test
    public void revisionOriginMustMatchStageAndDeclaredTransformCapability() {
        List<EngineEvent> wrongStage = bootstrap(ENGINE_ID);
        wrongStage.add(new EngineEvent.Transcript(
                ENGINE_ID,
                4L,
                SegmentRevision.text(
                        SESSION,
                        1L,
                        1L,
                        RevisionStage.LIVE,
                        "quality pretending to be live",
                        RevisionOrigin.QUALITY_ASR,
                        false)));
        EngineReplayReport stageReport = new EngineTraceReplayer()
                .replay(EngineTrace.of(fullLocalEngine(), SESSION, wrongStage));
        assertEquals(
                ReplayDisposition.REJECTED_CAPABILITY,
                stageReport.steps().get(3).disposition());

        List<EngineEvent> itnWithoutClaim = bootstrap(ENGINE_ID);
        itnWithoutClaim.add(new EngineEvent.HardBoundary(SESSION, ENGINE_ID, 4L, 1L));
        itnWithoutClaim.add(new EngineEvent.Transcript(
                ENGINE_ID,
                5L,
                SegmentRevision.text(
                        SESSION,
                        1L,
                        1L,
                        RevisionStage.REFINED,
                        "10:30",
                        RevisionOrigin.INVERSE_TEXT_NORMALIZATION,
                        true)));
        EngineReplayReport itnReport = new EngineTraceReplayer()
                .replay(EngineTrace.of(fullLocalEngine(), SESSION, itnWithoutClaim));
        assertEquals(
                ReplayDisposition.REJECTED_CAPABILITY,
                itnReport.steps().get(4).disposition());
    }

    @Test
    public void wrongActualEngineIdIsRejectedAndRouteMetadataSurvives() {
        List<EngineEvent> events = bootstrap(ENGINE_ID);
        events.add(new EngineEvent.Transcript(
                "different-engine",
                4L,
                SegmentRevision.text(
                        SESSION,
                        1L,
                        1L,
                        RevisionStage.LIVE,
                        "leak",
                        RevisionOrigin.STREAM_ASR,
                        false)));

        EngineReplayReport report = new EngineTraceReplayer()
                .replay(EngineTrace.of(fullLocalEngine(), SESSION, events));

        assertEquals(ReplayDisposition.REJECTED_ENGINE, report.steps().get(3).disposition());
        assertEquals("local-two-pass", report.actualEngine().engineId());
        assertEquals("paraformer+sensevoice@pinned", report.actualEngine().modelRevision());
    }

    @Test
    public void directTraceLimitsAlsoRejectOversizedEvents() {
        EngineTraceLimits tiny = new EngineTraceLimits(10_000, 2, 4, 2);
        EngineTrace trace = EngineTrace.of(fullLocalEngine(), SESSION, bootstrap(ENGINE_ID));
        assertThrows(
                IllegalArgumentException.class,
                () -> new EngineTraceReplayer(tiny, VoiceDraftLimits.DEFAULT).replay(trace));
    }

    @Test
    public void processingLocationAndOnDeviceClaimCannotContradict() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new EngineDescriptor(
                        "bad",
                        "Bad",
                        "v1",
                        ProcessingLocation.NETWORK,
                        EngineCapabilities.of(EngineCapability.ON_DEVICE)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new EngineDescriptor(
                        "bad-local",
                        "Bad local",
                        "v1",
                        ProcessingLocation.ON_DEVICE,
                        EngineCapabilities.NONE));
    }

    private static EngineTrace completeTrace(EngineDescriptor descriptor) {
        List<EngineEvent> events = new ArrayList<>();
        events.add(new EngineEvent.Prepare(SESSION, ENGINE_ID, 1L));
        events.add(new EngineEvent.Ready(SESSION, ENGINE_ID, 2L));
        events.add(new EngineEvent.OpenSegment(SESSION, ENGINE_ID, 3L, 1L, SegmentJoin.NONE));
        events.add(transcript(
                4L,
                SegmentRevision.text(
                        SESSION,
                        1L,
                        1L,
                        RevisionStage.LIVE,
                        "你好世借",
                        RevisionOrigin.STREAM_ASR,
                        false)));
        events.add(new EngineEvent.SoftBoundary(SESSION, ENGINE_ID, 5L, 1L));
        events.add(transcript(
                6L,
                SegmentRevision.text(
                        SESSION,
                        1L,
                        2L,
                        RevisionStage.PROVISIONAL,
                        "你好世借。",
                        RevisionOrigin.PUNCTUATION,
                        false)));
        events.add(new EngineEvent.ReopenSegment(SESSION, ENGINE_ID, 7L, 1L));
        events.add(transcript(
                8L,
                SegmentRevision.text(
                        SESSION,
                        1L,
                        3L,
                        RevisionStage.LIVE,
                        "你好世界",
                        RevisionOrigin.STREAM_ASR,
                        true)));
        events.add(new EngineEvent.HardBoundary(SESSION, ENGINE_ID, 9L, 1L));
        events.add(transcript(
                10L,
                SegmentRevision.text(
                        SESSION,
                        1L,
                        4L,
                        RevisionStage.REFINED,
                        "你好世界。",
                        RevisionOrigin.QUALITY_ASR,
                        true)));
        events.add(new EngineEvent.SealSegment(SESSION, ENGINE_ID, 11L, 1L));
        events.add(new EngineEvent.StopRequested(SESSION, ENGINE_ID, 12L));
        events.add(new EngineEvent.CaptureEnded(
                SESSION, ENGINE_ID, 13L, TerminalReason.USER_FINISH));
        return EngineTrace.of(descriptor, SESSION, events);
    }

    private static EngineDescriptor fullLocalEngine() {
        return new EngineDescriptor(
                ENGINE_ID,
                "OpenTypeless local two-pass",
                "paraformer+sensevoice@pinned",
                ProcessingLocation.ON_DEVICE,
                EngineCapabilities.of(
                        EngineCapability.LIVE_REVISIONS,
                        EngineCapability.SEGMENT_FINALS,
                        EngineCapability.AUTOMATIC_PUNCTUATION,
                        EngineCapability.ON_DEVICE));
    }

    private static List<EngineEvent> bootstrap(String engineId) {
        ArrayList<EngineEvent> events = new ArrayList<>();
        events.add(new EngineEvent.Prepare(SESSION, engineId, 1L));
        events.add(new EngineEvent.Ready(SESSION, engineId, 2L));
        events.add(new EngineEvent.OpenSegment(
                SESSION, engineId, 3L, 1L, SegmentJoin.NONE));
        return events;
    }

    private static EngineEvent.Transcript transcript(
            long eventSequence, SegmentRevision revision) {
        return new EngineEvent.Transcript(ENGINE_ID, eventSequence, revision);
    }
}
