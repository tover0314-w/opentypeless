package com.opentypeless.android.speech.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Random;
import org.junit.Test;

public final class VoiceDraftReducerTest {
    private static final SessionId SESSION = SessionId.of("speech-core-v2-test");
    private final VoiceDraftReducer reducer = new VoiceDraftReducer();

    @Test
    public void captureLifecycleIsIndependentAndIdempotent() {
        VoiceDraft initial = VoiceDraft.initial(SESSION);

        VoiceDraft preparing = applied(initial, new VoiceDraftEvent.Prepare(SESSION));
        assertEquals(CaptureState.PREPARING, preparing.captureState());
        assertEquals(
                ReductionDisposition.IGNORED_DUPLICATE,
                reducer.reduce(preparing, new VoiceDraftEvent.Prepare(SESSION)).disposition());

        VoiceDraft listening = applied(preparing, new VoiceDraftEvent.Ready(SESSION));
        VoiceDraft stopping = applied(listening, new VoiceDraftEvent.StopRequested(SESSION));
        VoiceDraft ended = applied(
                stopping,
                new VoiceDraftEvent.CaptureEnded(SESSION, TerminalReason.USER_FINISH));

        assertEquals(CaptureState.ENDED, ended.captureState());
        assertEquals(TerminalReason.USER_FINISH, ended.terminalReason());
        assertEquals(
                ReductionDisposition.REJECTED_TRANSITION,
                reducer.reduce(ended, new VoiceDraftEvent.Ready(SESSION)).disposition());
    }

    @Test
    public void r01MonotonicLiveRevisionsReplaceWholeSegment() {
        VoiceDraft draft = openListeningSegment(1L, SegmentJoin.NONE);
        draft = applied(draft, revision(1L, 1L, RevisionStage.LIVE, "你", false));
        draft = applied(draft, revision(1L, 2L, RevisionStage.LIVE, "你好", false));
        draft = applied(draft, revision(1L, 3L, RevisionStage.LIVE, "你好世界", false));

        assertEquals("你好世界", draft.renderedText());
        assertEquals(3L, draft.segment(1L).orElseThrow().lastRevisionId());
        assertEquals(3, draft.segment(1L).orElseThrow().revisions().size());
    }

    @Test
    public void r02DuplicateRevisionLeavesByteEquivalentState() {
        VoiceDraft draft = openListeningSegment(1L, SegmentJoin.NONE);
        SegmentRevision revision = textRevision(
                1L, 1L, RevisionStage.LIVE, "OpenTypeless", RevisionOrigin.STREAM_ASR, false);
        draft = applied(draft, new VoiceDraftEvent.RevisionArrived(revision));

        VoiceDraftReduction duplicate = reducer.reduce(
                draft, new VoiceDraftEvent.RevisionArrived(revision));

        assertEquals(ReductionDisposition.IGNORED_DUPLICATE, duplicate.disposition());
        assertSame(draft, duplicate.draft());
    }

    @Test
    public void reusedRevisionIdWithDifferentPayloadIsRejectedAsConflict() {
        VoiceDraft draft = openListeningSegment(1L, SegmentJoin.NONE);
        draft = applied(draft, revision(1L, 1L, RevisionStage.LIVE, "one", false));

        VoiceDraftReduction conflict = reducer.reduce(
                draft, revision(1L, 1L, RevisionStage.LIVE, "different", false));

        assertEquals(ReductionDisposition.REJECTED_CONFLICT, conflict.disposition());
        assertSame(draft, conflict.draft());
        assertEquals("one", conflict.draft().renderedText());
    }

    @Test
    public void r03OutOfOrderRevisionIsIgnored() {
        VoiceDraft draft = openListeningSegment(1L, SegmentJoin.NONE);
        draft = applied(draft, revision(1L, 3L, RevisionStage.LIVE, "newest", false));

        VoiceDraftReduction stale = reducer.reduce(
                draft, revision(1L, 2L, RevisionStage.LIVE, "older", false));

        assertEquals(ReductionDisposition.IGNORED_STALE, stale.disposition());
        assertSame(draft, stale.draft());
        assertEquals("newest", stale.draft().renderedText());
    }

    @Test
    public void r04BlankModelRevisionNeverErasesVisibleText() {
        VoiceDraft draft = openListeningSegment(1L, SegmentJoin.NONE);
        draft = applied(draft, revision(1L, 1L, RevisionStage.LIVE, "保留我", false));

        VoiceDraftReduction empty = reducer.reduce(
                draft, revision(1L, 2L, RevisionStage.LIVE, "", false));
        VoiceDraftReduction whitespace = reducer.reduce(
                draft, revision(1L, 3L, RevisionStage.LIVE, " \n\t", false));

        assertEquals(ReductionDisposition.IGNORED_BLANK, empty.disposition());
        assertEquals(ReductionDisposition.IGNORED_BLANK, whitespace.disposition());
        assertEquals("保留我", whitespace.draft().renderedText());
    }

    @Test
    public void r05SoftBoundaryPunctuationRollsBackWhenSpeechResumes() {
        VoiceDraft draft = openListeningSegment(1L, SegmentJoin.NONE);
        draft = applied(draft, revision(1L, 1L, RevisionStage.LIVE, "我们开始", false));
        draft = applied(draft, new VoiceDraftEvent.SoftBoundary(SESSION, 1L));
        draft = applied(draft, textRevisionEvent(
                1L,
                2L,
                RevisionStage.PROVISIONAL,
                "我们开始。",
                RevisionOrigin.PUNCTUATION,
                false));
        assertEquals("我们开始。", draft.renderedText());

        draft = applied(draft, new VoiceDraftEvent.ReopenSegment(SESSION, 1L));
        assertEquals("我们开始", draft.renderedText());
        assertEquals(2L, draft.segment(1L).orElseThrow().lastRevisionId());

        draft = applied(draft, revision(1L, 3L, RevisionStage.LIVE, "我们开始继续测试", false));
        assertEquals("我们开始继续测试", draft.renderedText());
    }

    @Test
    public void r06HardBoundaryRejectsUnstableLiveButAcceptsProviderFinalFallback() {
        VoiceDraft draft = openListeningSegment(1L, SegmentJoin.NONE);
        draft = applied(draft, revision(1L, 1L, RevisionStage.LIVE, "draft", false));
        draft = applied(draft, new VoiceDraftEvent.HardBoundary(SESSION, 1L));

        VoiceDraftReduction unstable = reducer.reduce(
                draft, revision(1L, 2L, RevisionStage.LIVE, "late unstable", false));
        assertEquals(ReductionDisposition.REJECTED_TRANSITION, unstable.disposition());

        draft = applied(
                draft, revision(1L, 2L, RevisionStage.LIVE, "provider final", true));
        assertEquals("provider final", draft.renderedText());
        assertEquals(SegmentStage.REFINING, draft.segment(1L).orElseThrow().stage());
    }

    @Test
    public void r07QualityJobsMayCompleteBackwardsWithoutReorderingDocument() {
        VoiceDraft draft = openListeningSegment(1L, SegmentJoin.NONE);
        draft = applied(draft, revision(1L, 1L, RevisionStage.LIVE, "第一断", false));
        draft = applied(draft, new VoiceDraftEvent.HardBoundary(SESSION, 1L));
        draft = applied(draft, new VoiceDraftEvent.OpenSegment(SESSION, 2L, SegmentJoin.SPACE));
        draft = applied(draft, revision(2L, 1L, RevisionStage.LIVE, "sekond", false));
        draft = applied(draft, new VoiceDraftEvent.HardBoundary(SESSION, 2L));

        draft = applied(draft, refined(2L, 2L, "second"));
        assertEquals("第一断 second", draft.renderedText());
        draft = applied(draft, refined(1L, 2L, "第一段"));
        assertEquals("第一段 second", draft.renderedText());
    }

    @Test
    public void r08SealedSegmentRejectsEveryLateModelRevision() {
        VoiceDraft draft = refiningSegment("原始", 1L);
        draft = applied(draft, refined(1L, 2L, "终稿"));
        draft = applied(draft, new VoiceDraftEvent.SealSegment(SESSION, 1L));

        VoiceDraftReduction late = reducer.reduce(draft, refined(1L, 3L, "晚到错误"));

        assertEquals(ReductionDisposition.REJECTED_TRANSITION, late.disposition());
        assertEquals("终稿", late.draft().renderedText());
    }

    @Test
    public void r09UserLockedRevisionWinsOverLaterModelEvents() {
        VoiceDraft draft = refiningSegment("Open Type less", 1L);
        draft = applied(draft, refined(1L, 2L, "OpenTypeless"));
        draft = applied(draft, new VoiceDraftEvent.SealSegment(SESSION, 1L));
        draft = applied(draft, textRevisionEvent(
                1L,
                3L,
                RevisionStage.USER_LOCKED,
                "OpenTypeless Core",
                RevisionOrigin.USER,
                true));

        VoiceDraftReduction late = reducer.reduce(draft, refined(1L, 4L, "Open Type Less"));

        assertEquals(ReductionDisposition.REJECTED_LOCKED, late.disposition());
        assertEquals("OpenTypeless Core", late.draft().renderedText());
        assertTrue(late.draft().segment(1L).orElseThrow().userLocked());
    }

    @Test
    public void userEditCanLockAnOpenSegmentAndCaptureMayContinueInANewSegment() {
        VoiceDraft draft = openListeningSegment(1L, SegmentJoin.NONE);
        draft = applied(draft, revision(1L, 1L, RevisionStage.LIVE, "错误名字", false));
        draft = applied(draft, textRevisionEvent(
                1L,
                2L,
                RevisionStage.USER_LOCKED,
                "正确名字",
                RevisionOrigin.USER,
                true));

        assertTrue(draft.activeSegment().isEmpty());
        assertEquals(SegmentStage.SEALED, draft.segment(1L).orElseThrow().stage());
        draft = applied(draft, new VoiceDraftEvent.OpenSegment(SESSION, 2L, SegmentJoin.NONE));
        draft = applied(draft, revision(2L, 1L, RevisionStage.LIVE, "继续说", false));
        assertEquals("正确名字继续说", draft.renderedText());
    }

    @Test
    public void r10OtherSessionCanNeverMutateDraft() {
        VoiceDraft draft = openListeningSegment(1L, SegmentJoin.NONE);
        SessionId other = SessionId.of("other-generation");
        SegmentRevision stale = SegmentRevision.text(
                other,
                1L,
                1L,
                RevisionStage.LIVE,
                "leak",
                RevisionOrigin.STREAM_ASR,
                false);

        VoiceDraftReduction result = reducer.reduce(
                draft, new VoiceDraftEvent.RevisionArrived(stale));

        assertEquals(ReductionDisposition.REJECTED_SESSION, result.disposition());
        assertSame(draft, result.draft());
        assertEquals("", result.draft().renderedText());
    }

    @Test
    public void r11BoundsFailClosedAndPreserveAcceptedPrefix() {
        VoiceDraftReducer bounded = new VoiceDraftReducer(new VoiceDraftLimits(2, 2, 4, 8));
        VoiceDraft draft = listening(bounded);
        draft = applied(bounded, draft, new VoiceDraftEvent.OpenSegment(SESSION, 1L, SegmentJoin.NONE));
        draft = applied(bounded, draft, revision(1L, 1L, RevisionStage.LIVE, "abcd", false));

        VoiceDraftReduction tooLong = bounded.reduce(
                draft, revision(1L, 2L, RevisionStage.LIVE, "abcde", false));
        assertEquals(ReductionDisposition.REJECTED_BOUNDS, tooLong.disposition());
        assertEquals("abcd", tooLong.draft().renderedText());

        draft = applied(bounded, draft, revision(1L, 2L, RevisionStage.LIVE, "ab", false));
        VoiceDraftReduction tooManyRevisions = bounded.reduce(
                draft, revision(1L, 3L, RevisionStage.LIVE, "abc", false));
        assertEquals(ReductionDisposition.REJECTED_BOUNDS, tooManyRevisions.disposition());
        assertEquals("ab", tooManyRevisions.draft().renderedText());

        draft = applied(bounded, draft, new VoiceDraftEvent.HardBoundary(SESSION, 1L));
        draft = applied(bounded, draft, new VoiceDraftEvent.OpenSegment(SESSION, 2L, SegmentJoin.SPACE));
        VoiceDraftReduction totalOverflow = bounded.reduce(
                draft, revision(2L, 1L, RevisionStage.LIVE, "wxyz", false));
        assertEquals(ReductionDisposition.APPLIED, totalOverflow.disposition());
        assertEquals("ab wxyz", totalOverflow.draft().renderedText());

        VoiceDraft secondClosed = applied(
                bounded,
                totalOverflow.draft(),
                new VoiceDraftEvent.HardBoundary(SESSION, 2L));
        VoiceDraftReduction thirdSegment = bounded.reduce(
                secondClosed, new VoiceDraftEvent.OpenSegment(SESSION, 3L, SegmentJoin.SPACE));
        assertEquals(ReductionDisposition.REJECTED_BOUNDS, thirdSegment.disposition());
    }

    @Test
    public void r12ExplicitDiscardIsTheOnlyDestructiveTerminalAndMakesLateEventsNoOps() {
        VoiceDraft draft = openListeningSegment(1L, SegmentJoin.NONE);
        draft = applied(draft, revision(1L, 1L, RevisionStage.LIVE, "private draft", false));
        draft = applied(draft, new VoiceDraftEvent.ExplicitDiscard(SESSION));

        assertEquals(CaptureState.DISCARDED, draft.captureState());
        assertEquals(TerminalReason.EXPLICIT_DISCARD, draft.terminalReason());
        assertEquals("", draft.renderedText());
        assertTrue(draft.segments().isEmpty());

        VoiceDraftReduction late = reducer.reduce(
                draft, revision(1L, 2L, RevisionStage.LIVE, "must not return", false));
        assertEquals(ReductionDisposition.IGNORED_TERMINAL, late.disposition());
        assertSame(draft, late.draft());
    }

    @Test
    public void recognitionFinalityAndEditorDeliveryAreOrthogonal() {
        VoiceDraft draft = openListeningSegment(1L, SegmentJoin.NONE);
        draft = applied(draft, revision(1L, 1L, RevisionStage.LIVE, "draft", false));
        draft = applied(
                draft,
                new VoiceDraftEvent.DeliveryChanged(SESSION, 1L, DeliveryState.COMPOSING));
        assertEquals(SegmentStage.OPEN, draft.segment(1L).orElseThrow().stage());
        assertEquals(DeliveryState.COMPOSING, draft.segment(1L).orElseThrow().deliveryState());

        draft = applied(draft, new VoiceDraftEvent.HardBoundary(SESSION, 1L));
        draft = applied(draft, refined(1L, 2L, "refined"));
        draft = applied(draft, new VoiceDraftEvent.SealSegment(SESSION, 1L));
        assertEquals(SegmentStage.SEALED, draft.segment(1L).orElseThrow().stage());
        assertEquals(DeliveryState.COMPOSING, draft.segment(1L).orElseThrow().deliveryState());

        draft = applied(
                draft,
                new VoiceDraftEvent.DeliveryChanged(SESSION, 1L, DeliveryState.COMMITTED));
        assertEquals(DeliveryState.COMMITTED, draft.segment(1L).orElseThrow().deliveryState());
    }

    @Test
    public void targetDetachFreezesCompositionWithoutEndingRecognition() {
        VoiceDraft draft = openListeningSegment(1L, SegmentJoin.NONE);
        draft = applied(draft, revision(1L, 1L, RevisionStage.LIVE, "visible", false));
        draft = applied(
                draft,
                new VoiceDraftEvent.DeliveryChanged(SESSION, 1L, DeliveryState.COMPOSING));

        draft = applied(draft, new VoiceDraftEvent.TargetDetached(SESSION, 1L));

        assertEquals(DeliveryState.FROZEN, draft.segment(1L).orElseThrow().deliveryState());
        assertEquals(SegmentStage.OPEN, draft.segment(1L).orElseThrow().stage());
        assertEquals(CaptureState.LISTENING, draft.captureState());
    }

    @Test
    public void terminalCaptureStillAllowsPendingQualityAndRecoveryDelivery() {
        VoiceDraft draft = refiningSegment("raw", 1L);
        draft = applied(draft, new VoiceDraftEvent.StopRequested(SESSION));
        draft = applied(
                draft,
                new VoiceDraftEvent.CaptureEnded(SESSION, TerminalReason.USER_FINISH));

        draft = applied(draft, refined(1L, 2L, "quality final"));
        draft = applied(draft, new VoiceDraftEvent.SealSegment(SESSION, 1L));
        draft = applied(
                draft,
                new VoiceDraftEvent.DeliveryChanged(SESSION, 1L, DeliveryState.RECOVERABLE));

        assertEquals(CaptureState.ENDED, draft.captureState());
        assertEquals("quality final", draft.renderedText());
        assertEquals(DeliveryState.RECOVERABLE, draft.segment(1L).orElseThrow().deliveryState());
    }

    @Test
    public void duplicateAndOutOfOrderPermutationAlwaysConvergesToHighestRevision() {
        for (int seed = 0; seed < 100; seed++) {
            VoiceDraft draft = openListeningSegment(1L, SegmentJoin.NONE);
            List<VoiceDraftEvent> events = new ArrayList<>();
            for (int revisionId = 1; revisionId <= 20; revisionId++) {
                VoiceDraftEvent event = revision(
                        1L,
                        revisionId,
                        RevisionStage.LIVE,
                        "revision-" + revisionId,
                        false);
                events.add(event);
                if ((revisionId & 1) == 0) {
                    events.add(event);
                }
            }
            Collections.shuffle(events, new Random(seed));
            for (VoiceDraftEvent event : events) {
                draft = reducer.reduce(draft, event).draft();
            }
            assertEquals("seed=" + seed, "revision-20", draft.renderedText());
            assertEquals("seed=" + seed, 20L, draft.segment(1L).orElseThrow().lastRevisionId());
        }
    }

    @Test
    public void explicitSeparatorsMakeMultiSegmentRenderingDeterministic() {
        VoiceDraft draft = openListeningSegment(1L, SegmentJoin.NONE);
        draft = applied(draft, revision(1L, 1L, RevisionStage.LIVE, "第一段", false));
        draft = applied(draft, new VoiceDraftEvent.HardBoundary(SESSION, 1L));
        draft = applied(draft, new VoiceDraftEvent.OpenSegment(SESSION, 2L, SegmentJoin.NEWLINE));
        draft = applied(draft, revision(2L, 1L, RevisionStage.LIVE, "second", false));

        assertEquals("第一段\nsecond", draft.renderedText());
    }

    @Test
    public void invalidFirstSegmentSeparatorIsRejected() {
        VoiceDraft draft = listening(reducer);
        VoiceDraftReduction result = reducer.reduce(
                draft, new VoiceDraftEvent.OpenSegment(SESSION, 1L, SegmentJoin.SPACE));
        assertEquals(ReductionDisposition.REJECTED_TRANSITION, result.disposition());
        assertSame(draft, result.draft());
    }

    @Test
    public void tokenEvidenceKeepsCapabilityAbsenceExplicitAndValidatesSpans() {
        TokenEvidence evidence = TokenEvidence.textOnly("好", 1, 2);
        SegmentRevision revision = new SegmentRevision(
                SESSION,
                1L,
                1L,
                RevisionStage.LIVE,
                "你好",
                List.of(evidence),
                0L,
                300L,
                RevisionOrigin.STREAM_ASR,
                false);

        assertTrue(revision.tokenEvidence().get(0).confidence().isEmpty());
        assertTrue(revision.tokenEvidence().get(0).stable().isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> new SegmentRevision(
                        SESSION,
                        1L,
                        1L,
                        RevisionStage.LIVE,
                        "你",
                        List.of(TokenEvidence.textOnly("越界", 0, 2)),
                        0L,
                        100L,
                        RevisionOrigin.STREAM_ASR,
                        false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TokenEvidence(
                        "bad confidence",
                        0,
                        1,
                        OptionalDouble.of(1.5d),
                        Optional.empty(),
                        OptionalLong.empty(),
                        OptionalLong.empty()));
    }

    @Test
    public void userOriginAndLockedStageCannotBeForgedIndependently() {
        assertThrows(
                IllegalArgumentException.class,
                () -> textRevision(
                        1L,
                        1L,
                        RevisionStage.USER_LOCKED,
                        "text",
                        RevisionOrigin.QUALITY_ASR,
                        true));
        assertThrows(
                IllegalArgumentException.class,
                () -> textRevision(
                        1L,
                        1L,
                        RevisionStage.REFINED,
                        "text",
                        RevisionOrigin.USER,
                        true));
    }

    private VoiceDraft openListeningSegment(long segmentId, SegmentJoin joinBefore) {
        VoiceDraft draft = listening(reducer);
        return applied(draft, new VoiceDraftEvent.OpenSegment(SESSION, segmentId, joinBefore));
    }

    private VoiceDraft refiningSegment(String raw, long segmentId) {
        VoiceDraft draft = openListeningSegment(segmentId, SegmentJoin.NONE);
        draft = applied(draft, revision(segmentId, 1L, RevisionStage.LIVE, raw, false));
        return applied(draft, new VoiceDraftEvent.HardBoundary(SESSION, segmentId));
    }

    private static VoiceDraft listening(VoiceDraftReducer reducer) {
        VoiceDraft draft = VoiceDraft.initial(SESSION);
        draft = applied(reducer, draft, new VoiceDraftEvent.Prepare(SESSION));
        return applied(reducer, draft, new VoiceDraftEvent.Ready(SESSION));
    }

    private VoiceDraft applied(VoiceDraft draft, VoiceDraftEvent event) {
        return applied(reducer, draft, event);
    }

    private static VoiceDraft applied(
            VoiceDraftReducer reducer, VoiceDraft draft, VoiceDraftEvent event) {
        VoiceDraftReduction reduction = reducer.reduce(draft, event);
        assertEquals(reduction.detail(), ReductionDisposition.APPLIED, reduction.disposition());
        return reduction.draft();
    }

    private static VoiceDraftEvent revision(
            long segmentId,
            long revisionId,
            RevisionStage stage,
            String text,
            boolean providerFinal) {
        return textRevisionEvent(
                segmentId,
                revisionId,
                stage,
                text,
                RevisionOrigin.STREAM_ASR,
                providerFinal);
    }

    private static VoiceDraftEvent refined(long segmentId, long revisionId, String text) {
        return textRevisionEvent(
                segmentId,
                revisionId,
                RevisionStage.REFINED,
                text,
                RevisionOrigin.QUALITY_ASR,
                true);
    }

    private static VoiceDraftEvent textRevisionEvent(
            long segmentId,
            long revisionId,
            RevisionStage stage,
            String text,
            RevisionOrigin origin,
            boolean providerFinal) {
        return new VoiceDraftEvent.RevisionArrived(
                textRevision(segmentId, revisionId, stage, text, origin, providerFinal));
    }

    private static SegmentRevision textRevision(
            long segmentId,
            long revisionId,
            RevisionStage stage,
            String text,
            RevisionOrigin origin,
            boolean providerFinal) {
        return SegmentRevision.text(
                SESSION, segmentId, revisionId, stage, text, origin, providerFinal);
    }
}
