package com.opentypeless.android.speech.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.data.PersonalTerm;
import com.opentypeless.android.data.PersonalizationSnapshot;
import com.opentypeless.android.speech.core.CaptureState;
import com.opentypeless.android.speech.core.RevisionOrigin;
import com.opentypeless.android.speech.core.SegmentJoin;
import com.opentypeless.android.speech.core.SegmentStage;
import com.opentypeless.android.speech.core.SessionId;
import com.opentypeless.android.speech.core.TerminalReason;
import com.opentypeless.android.speech.core.TokenEvidence;
import com.opentypeless.android.speech.engine.EngineCapabilities;
import com.opentypeless.android.speech.engine.EngineCapability;
import com.opentypeless.android.speech.engine.EngineDescriptor;
import com.opentypeless.android.speech.engine.ProcessingLocation;
import com.opentypeless.android.speech.transform.SegmentTransformPolicy;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import org.junit.Test;

public final class SpeechCoreCoordinatorTest {
    private static final SpeechSessionToken TOKEN =
            new SpeechSessionToken(new SessionId("coordinator-session"), 7L);

    @Test
    public void softPunctuationReopensThenQualitySealsMonotonicDocument() {
        SpeechCoreCoordinator coordinator = coordinator(concurrent(2, 4), personalization());
        start(coordinator);
        assertEquals(
                CoordinatorDisposition.APPLIED,
                coordinator.openSegment(TOKEN, 1L, SegmentJoin.NONE).disposition());

        CoordinatorUpdate first = coordinator.liveRevision(
                StreamingRevisionInput.text(TOKEN, 1L, 1L, "use open type less"));
        assertEquals("use OpenTypeless", first.draft().renderedText());
        assertTrue(first.projectionChanged());

        CoordinatorUpdate soft = coordinator.softBoundary(
                TOKEN, 1L, "Use open type less.");
        assertEquals("Use OpenTypeless.", soft.draft().renderedText());
        assertEquals(SegmentStage.SOFT_BOUNDARY, soft.draft().segment(1L).orElseThrow().stage());

        CoordinatorUpdate reopened = coordinator.reopenSegment(TOKEN, 1L);
        assertEquals("use OpenTypeless", reopened.draft().renderedText());
        assertEquals(SegmentStage.OPEN, reopened.draft().segment(1L).orElseThrow().stage());

        coordinator.liveRevision(
                StreamingRevisionInput.text(TOKEN, 1L, 2L, "use open type less daily"));
        CoordinatorUpdate hard = coordinator.hardBoundary(TOKEN, 1L);
        QualityJobToken quality = onlyJob(hard);
        assertEquals(SegmentStage.REFINING, hard.draft().segment(1L).orElseThrow().stage());

        CoordinatorUpdate finalUpdate = coordinator.qualitySucceeded(
                quality,
                "use open type less daily",
                "Use open type less daily.",
                null);

        assertEquals("Use OpenTypeless daily.", finalUpdate.draft().renderedText());
        assertEquals(SegmentStage.SEALED,
                finalUpdate.draft().segment(1L).orElseThrow().stage());
        assertEquals(
                RevisionOrigin.PERSONALIZATION,
                finalUpdate.draft().segment(1L).orElseThrow()
                        .visibleRevision().orElseThrow().origin());
    }

    @Test
    public void twoQualityJobsMayFinishBackwardsWithoutReorderingSegments() {
        SpeechCoreCoordinator coordinator = coordinator(concurrent(2, 4), null);
        start(coordinator);
        coordinator.openSegment(TOKEN, 1L, SegmentJoin.NONE);
        coordinator.liveRevision(StreamingRevisionInput.text(TOKEN, 1L, 1L, "first live"));
        QualityJobToken first = onlyJob(coordinator.hardBoundary(TOKEN, 1L));
        coordinator.openSegment(TOKEN, 2L, SegmentJoin.SPACE);
        coordinator.liveRevision(StreamingRevisionInput.text(TOKEN, 2L, 1L, "second live"));
        QualityJobToken second = onlyJob(coordinator.hardBoundary(TOKEN, 2L));

        CoordinatorUpdate secondDone = coordinator.qualitySucceeded(
                second, "second refined", "second refined.", null);
        assertEquals("first live second refined.", secondDone.draft().renderedText());
        assertEquals(SegmentStage.REFINING,
                secondDone.draft().segment(1L).orElseThrow().stage());
        assertEquals(SegmentStage.SEALED,
                secondDone.draft().segment(2L).orElseThrow().stage());

        CoordinatorUpdate firstDone = coordinator.qualitySucceeded(
                first, "first refined", "first refined.", null);
        assertEquals("first refined. second refined.", firstDone.draft().renderedText());
    }

    @Test
    public void qualityTimeoutSealsSafeStreamingFallback() {
        SpeechCoreCoordinator coordinator = coordinator(concurrent(1, 4), null);
        start(coordinator);
        coordinator.openSegment(TOKEN, 1L, SegmentJoin.NONE);
        coordinator.liveRevision(StreamingRevisionInput.text(TOKEN, 1L, 1L, "safe words"));
        QualityJobToken job = onlyJob(coordinator.hardBoundary(TOKEN, 1L));

        CoordinatorUpdate fallback = coordinator.qualityTimedOut(job);

        assertEquals(CoordinatorDisposition.APPLIED_STREAMING_FALLBACK, fallback.disposition());
        assertEquals("safe words.", fallback.draft().renderedText());
        assertEquals(SegmentStage.SEALED, fallback.draft().segment(1L).orElseThrow().stage());
        assertTrue(fallback.draft().segment(1L).orElseThrow().revisions().stream()
                .anyMatch(revision -> revision.origin() == RevisionOrigin.STREAMING_FALLBACK));
    }

    @Test
    public void streamingOnlyStrategySealsImmediatelyWithoutPretendingQualityRan() {
        SpeechCoreCoordinator coordinator = coordinator(streamingOnly(), null);
        start(coordinator);
        coordinator.openSegment(TOKEN, 1L, SegmentJoin.NONE);
        coordinator.liveRevision(StreamingRevisionInput.text(TOKEN, 1L, 1L, "offline text"));

        CoordinatorUpdate hard = coordinator.hardBoundary(TOKEN, 1L);

        assertEquals(CoordinatorDisposition.APPLIED_STREAMING_FALLBACK, hard.disposition());
        assertTrue(hard.qualityJobsToStart().isEmpty());
        assertEquals("offline text.", hard.draft().renderedText());
        assertEquals(SegmentStage.SEALED, hard.draft().segment(1L).orElseThrow().stage());
    }

    @Test
    public void duplicateStaleAndConflictingProviderSequencesAreSideEffectFree() {
        SpeechCoreCoordinator coordinator = coordinator(streamingOnly(), null);
        start(coordinator);
        coordinator.openSegment(TOKEN, 1L, SegmentJoin.NONE);
        StreamingRevisionInput first =
                StreamingRevisionInput.text(TOKEN, 1L, 3L, "hello");
        coordinator.liveRevision(first);
        String accepted = coordinator.draft().renderedText();

        assertEquals(
                CoordinatorDisposition.IGNORED_DUPLICATE,
                coordinator.liveRevision(first).disposition());
        assertEquals(
                CoordinatorDisposition.IGNORED_STALE,
                coordinator.liveRevision(
                        StreamingRevisionInput.text(TOKEN, 1L, 2L, "older"))
                        .disposition());
        assertEquals(
                CoordinatorDisposition.REJECTED_STATE,
                coordinator.liveRevision(
                        StreamingRevisionInput.text(TOKEN, 1L, 3L, "conflict"))
                        .disposition());
        assertEquals(accepted, coordinator.draft().renderedText());
    }

    @Test
    public void staleGenerationAndUndeclaredEvidenceAreRejected() {
        SpeechCoreCoordinator coordinator = coordinator(streamingOnly(), null);
        start(coordinator);
        coordinator.openSegment(TOKEN, 1L, SegmentJoin.NONE);
        SpeechSessionToken stale = new SpeechSessionToken(TOKEN.sessionId(), TOKEN.generation() - 1L);
        assertEquals(
                CoordinatorDisposition.REJECTED_SESSION,
                coordinator.liveRevision(
                        StreamingRevisionInput.text(stale, 1L, 1L, "stale"))
                        .disposition());

        TokenEvidence confidence = new TokenEvidence(
                "hello",
                0,
                5,
                OptionalDouble.of(0.9),
                Optional.empty(),
                OptionalLong.empty(),
                OptionalLong.empty());
        assertEquals(
                CoordinatorDisposition.REJECTED_CAPABILITY,
                coordinator.liveRevision(new StreamingRevisionInput(
                        TOKEN, 1L, 1L, "hello", List.of(confidence), false))
                        .disposition());
        assertEquals("", coordinator.draft().renderedText());
    }

    @Test
    public void captureMayEndBeforeLateOwnedQualityResult() {
        SpeechCoreCoordinator coordinator = coordinator(concurrent(1, 4), null);
        start(coordinator);
        coordinator.openSegment(TOKEN, 1L, SegmentJoin.NONE);
        coordinator.liveRevision(StreamingRevisionInput.text(TOKEN, 1L, 1L, "live tail"));
        QualityJobToken job = onlyJob(coordinator.hardBoundary(TOKEN, 1L));
        coordinator.stopRequested(TOKEN);
        coordinator.captureEnded(TOKEN, TerminalReason.USER_FINISH);
        assertEquals(CaptureState.ENDED, coordinator.draft().captureState());

        CoordinatorUpdate finalUpdate = coordinator.qualitySucceeded(
                job, "quality tail", "quality tail.", null);

        assertEquals("quality tail.", finalUpdate.draft().renderedText());
        assertEquals(CaptureState.ENDED, finalUpdate.draft().captureState());
    }

    @Test
    public void explicitDiscardInvalidatesLateQualityAndClearsDocument() {
        SpeechCoreCoordinator coordinator = coordinator(concurrent(1, 4), null);
        start(coordinator);
        coordinator.openSegment(TOKEN, 1L, SegmentJoin.NONE);
        coordinator.liveRevision(StreamingRevisionInput.text(TOKEN, 1L, 1L, "private draft"));
        QualityJobToken job = onlyJob(coordinator.hardBoundary(TOKEN, 1L));

        CoordinatorUpdate discarded = coordinator.explicitDiscard(TOKEN);
        CoordinatorUpdate late = coordinator.qualitySucceeded(job, "must not return", null, null);

        assertEquals(CaptureState.DISCARDED, discarded.draft().captureState());
        assertEquals("", discarded.draft().renderedText());
        assertEquals(CoordinatorDisposition.REJECTED_STATE, late.disposition());
        assertEquals("", coordinator.draft().renderedText());
    }

    @Test
    public void queuePressureSealsOnlyOverflowSegmentAsStreamingFallback() {
        SpeechCoreCoordinator coordinator = coordinator(concurrent(1, 1), null);
        start(coordinator);
        coordinator.openSegment(TOKEN, 1L, SegmentJoin.NONE);
        coordinator.liveRevision(StreamingRevisionInput.text(TOKEN, 1L, 1L, "one"));
        onlyJob(coordinator.hardBoundary(TOKEN, 1L));

        coordinator.openSegment(TOKEN, 2L, SegmentJoin.SPACE);
        coordinator.liveRevision(StreamingRevisionInput.text(TOKEN, 2L, 1L, "two"));
        CoordinatorUpdate overflow = coordinator.hardBoundary(TOKEN, 2L);

        assertEquals(CoordinatorDisposition.APPLIED_STREAMING_FALLBACK, overflow.disposition());
        assertEquals(SegmentStage.REFINING,
                overflow.draft().segment(1L).orElseThrow().stage());
        assertEquals(SegmentStage.SEALED,
                overflow.draft().segment(2L).orElseThrow().stage());
        assertEquals("one two.", overflow.draft().renderedText());
    }

    @Test
    public void constructorFailsClosedForFalseCapabilityClaims() {
        EngineDescriptor finalOnly = new EngineDescriptor(
                "final-only",
                "Final only",
                "1",
                ProcessingLocation.ON_DEVICE,
                EngineCapabilities.of(
                        EngineCapability.SEGMENT_FINALS,
                        EngineCapability.ON_DEVICE));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SpeechCoreCoordinator(
                        TOKEN,
                        finalOnly,
                        qualityEngine(),
                        streamingOnly(),
                        PersonalizationSnapshot.empty(),
                        SegmentTransformPolicy.DEFAULT));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SpeechCoreCoordinator(
                        TOKEN,
                        streamingEngine(),
                        null,
                        concurrent(1, 4),
                        PersonalizationSnapshot.empty(),
                        SegmentTransformPolicy.DEFAULT));
    }

    private static SpeechCoreCoordinator coordinator(
            RuntimeStrategyDecision strategy,
            PersonalizationSnapshot snapshot) {
        return new SpeechCoreCoordinator(
                TOKEN,
                streamingEngine(),
                qualityEngine(),
                strategy,
                snapshot == null ? PersonalizationSnapshot.empty() : snapshot,
                SegmentTransformPolicy.DEFAULT);
    }

    private static void start(SpeechCoreCoordinator coordinator) {
        assertEquals(CoordinatorDisposition.APPLIED, coordinator.prepare(TOKEN).disposition());
        assertEquals(CoordinatorDisposition.APPLIED, coordinator.ready(TOKEN).disposition());
    }

    private static QualityJobToken onlyJob(CoordinatorUpdate update) {
        assertEquals(1, update.qualityJobsToStart().size());
        return update.qualityJobsToStart().get(0);
    }

    private static PersonalizationSnapshot personalization() {
        return new PersonalizationSnapshot(
                List.of(new PersonalTerm(
                        1L, "OpenTypeless", "", "open type less", "", 0, true)),
                List.of());
    }

    private static EngineDescriptor streamingEngine() {
        return new EngineDescriptor(
                "paraformer-stream",
                "Streaming Paraformer",
                "int8-v1",
                ProcessingLocation.ON_DEVICE,
                EngineCapabilities.of(
                        EngineCapability.LIVE_REVISIONS,
                        EngineCapability.ON_DEVICE));
    }

    private static EngineDescriptor qualityEngine() {
        return new EngineDescriptor(
                "sensevoice-quality",
                "SenseVoice quality",
                "int8-v1",
                ProcessingLocation.ON_DEVICE,
                EngineCapabilities.of(
                        EngineCapability.SEGMENT_FINALS,
                        EngineCapability.AUTOMATIC_PUNCTUATION,
                        EngineCapability.ON_DEVICE));
    }

    private static RuntimeStrategyDecision concurrent(int jobs, int pending) {
        return new RuntimeStrategyDecision(
                RuntimeStrategy.CONCURRENT_TWO_PASS,
                jobs,
                pending,
                1_200L,
                List.of("test"));
    }

    private static RuntimeStrategyDecision streamingOnly() {
        return new RuntimeStrategyDecision(
                RuntimeStrategy.STREAMING_ONLY, 0, 0, 0L, List.of("test"));
    }
}
