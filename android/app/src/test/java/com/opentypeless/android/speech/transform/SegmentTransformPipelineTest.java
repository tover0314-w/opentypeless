package com.opentypeless.android.speech.transform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.data.CorrectionRule;
import com.opentypeless.android.data.PersonalTerm;
import com.opentypeless.android.data.PersonalizationSnapshot;
import com.opentypeless.android.speech.core.RevisionOrigin;
import com.opentypeless.android.speech.core.RevisionStage;
import com.opentypeless.android.speech.core.SegmentRevision;
import com.opentypeless.android.speech.core.SessionId;
import java.util.List;
import org.junit.Test;

public final class SegmentTransformPipelineTest {
    private static final SessionId SESSION = new SessionId("transform-session");

    @Test
    public void softBoundaryCreatesProvisionalPunctuationRevision() {
        SegmentRevision source = revision(1L, RevisionStage.LIVE, "你好世界", RevisionOrigin.STREAM_ASR);

        SegmentTransformResult result = SegmentTransformPipeline.apply(request(
                source, 2L, TransformPhase.SOFT_BOUNDARY, "你好，世界。", null,
                PersonalizationSnapshot.empty(), SegmentTransformPolicy.DEFAULT));

        assertEquals("你好，世界。", result.finalRevision().fullText());
        assertEquals(RevisionStage.PROVISIONAL, result.finalRevision().stage());
        assertEquals(RevisionOrigin.PUNCTUATION, result.finalRevision().origin());
        assertEquals(1, result.emittedRevisions().size());
        assertEquals(TransformDisposition.APPLIED, audit(result, TransformKind.PUNCTUATION));
    }

    @Test
    public void liveRevisionNeverGetsSpeculativePunctuation() {
        SegmentRevision source = revision(1L, RevisionStage.LIVE, "hello world", RevisionOrigin.STREAM_ASR);

        SegmentTransformResult result = SegmentTransformPipeline.apply(request(
                source, 2L, TransformPhase.LIVE, "hello, world.", null,
                PersonalizationSnapshot.empty(), SegmentTransformPolicy.DEFAULT));

        assertSame(source, result.finalRevision());
        assertTrue(result.emittedRevisions().isEmpty());
        assertEquals(
                TransformDisposition.SKIPPED_BY_POLICY,
                audit(result, TransformKind.PUNCTUATION));
    }

    @Test
    public void punctuationCandidateCannotChangeNumberOrUrl() {
        SegmentRevision source = refined(
                "支付 3.14 元，访问 https://a.example/x?a=1");

        SegmentTransformResult result = SegmentTransformPipeline.apply(request(
                source,
                2L,
                TransformPhase.REFINED,
                "支付 314 元，访问 https://a.example/x?a=1。",
                null,
                PersonalizationSnapshot.empty(),
                SegmentTransformPolicy.DEFAULT));

        assertEquals("支付 3.14 元，访问 https://a.example/x?a=1。", result.finalRevision().fullText());
        assertEquals(TransformDisposition.REJECTED_UNSAFE, audit(result, TransformKind.PUNCTUATION));
        assertFalse(result.finalRevision().fullText().contains("支付 314"));
    }

    @Test
    public void punctuationCandidateCannotFlattenParagraphs() {
        SegmentRevision source = refined("first paragraph\nsecond paragraph");

        SegmentTransformResult result = SegmentTransformPipeline.apply(request(
                source,
                2L,
                TransformPhase.REFINED,
                "First paragraph. Second paragraph.",
                null,
                PersonalizationSnapshot.empty(),
                SegmentTransformPolicy.DEFAULT));

        assertTrue(result.finalRevision().fullText().contains("\n"));
        assertEquals(TransformDisposition.REJECTED_UNSAFE, audit(result, TransformKind.PUNCTUATION));
    }

    @Test
    public void punctuationCandidateCannotRecaseEnglishNames() {
        SegmentRevision source = refined("OpenTypeless works");

        SegmentTransformResult result = SegmentTransformPipeline.apply(request(
                source,
                2L,
                TransformPhase.REFINED,
                "opentypeless, works.",
                null,
                PersonalizationSnapshot.empty(),
                SegmentTransformPolicy.DEFAULT));

        assertEquals("OpenTypeless works.", result.finalRevision().fullText());
        assertEquals(
                TransformDisposition.REJECTED_UNSAFE,
                audit(result, TransformKind.PUNCTUATION));
    }

    @Test
    public void confirmedPersonalizationRunsAfterPunctuationAsSeparateRevision() {
        PersonalizationSnapshot snapshot = new PersonalizationSnapshot(
                List.of(new PersonalTerm(
                        9L, "OpenTypeless", "open type less", "open type less", "", 0, true)),
                List.of(new CorrectionRule(7L, "雪昭", "学昭", "", 0, true)));
        SegmentRevision source = refined("我叫雪昭 and open type less");

        SegmentTransformResult result = SegmentTransformPipeline.apply(request(
                source,
                2L,
                TransformPhase.REFINED,
                "我叫雪昭， and open type less。",
                null,
                snapshot,
                SegmentTransformPolicy.DEFAULT));

        assertEquals("我叫学昭， and OpenTypeless。", result.finalRevision().fullText());
        assertEquals(2, result.emittedRevisions().size());
        assertEquals(RevisionOrigin.PUNCTUATION, result.emittedRevisions().get(0).origin());
        assertEquals(RevisionOrigin.PERSONALIZATION, result.emittedRevisions().get(1).origin());
        assertEquals(List.of(9L), result.matchedTermIds());
        assertEquals(List.of(7L), result.matchedCorrectionIds());
    }

    @Test
    public void repeatedPersonalizationIsIdempotent() {
        PersonalizationSnapshot snapshot = new PersonalizationSnapshot(
                List.of(new PersonalTerm(
                        9L, "OpenTypeless", "", "open type less", "", 0, true)),
                List.of());
        SegmentRevision source = refined("Use OpenTypeless.");

        SegmentTransformResult result = SegmentTransformPipeline.apply(request(
                source,
                2L,
                TransformPhase.REFINED,
                "Use OpenTypeless.",
                null,
                snapshot,
                SegmentTransformPolicy.DEFAULT));

        assertSame(source, result.finalRevision());
        assertTrue(result.emittedRevisions().isEmpty());
        assertEquals(TransformDisposition.UNCHANGED, audit(result, TransformKind.PERSONALIZATION));
        assertEquals(List.of(9L), result.matchedTermIds());
    }

    @Test
    public void personalizationFailurePreservesRecognizedText() {
        String tooLong = "字".repeat(20_001);
        SegmentRevision source = revision(
                1L, RevisionStage.LIVE, tooLong, RevisionOrigin.STREAM_ASR);

        SegmentTransformResult result = SegmentTransformPipeline.apply(request(
                source,
                2L,
                TransformPhase.LIVE,
                null,
                null,
                new PersonalizationSnapshot(
                        List.of(),
                        List.of(new CorrectionRule(1L, "字", "词", "", 0, true))),
                SegmentTransformPolicy.DEFAULT));

        assertSame(source, result.finalRevision());
        assertEquals(
                TransformDisposition.FAILED_SOURCE_PRESERVED,
                audit(result, TransformKind.PERSONALIZATION));
    }

    @Test
    public void itnCandidateIsNeverAppliedWithoutLocaleEvidence() {
        SegmentRevision source = refined("会议在二十三日");
        SegmentTransformPolicy requested = new SegmentTransformPolicy(true, true, true, true);

        SegmentTransformResult result = SegmentTransformPipeline.apply(request(
                source,
                2L,
                TransformPhase.REFINED,
                "会议在二十三日。",
                "会议在23日。",
                PersonalizationSnapshot.empty(),
                requested));

        assertEquals("会议在二十三日。", result.finalRevision().fullText());
        assertEquals(
                TransformDisposition.REJECTED_UNSAFE,
                audit(result, TransformKind.INVERSE_TEXT_NORMALIZATION));
    }

    @Test
    public void unsafeCandidateDoesNotCreateDuplicateFallbackRevisionWhenAlreadyTerminal() {
        SegmentRevision source = refined("total 3.14.");

        SegmentTransformResult result = SegmentTransformPipeline.apply(request(
                source,
                2L,
                TransformPhase.REFINED,
                "total 314.",
                null,
                PersonalizationSnapshot.empty(),
                SegmentTransformPolicy.DEFAULT));

        assertSame(source, result.finalRevision());
        assertTrue(result.emittedRevisions().isEmpty());
        assertEquals(TransformDisposition.REJECTED_UNSAFE, audit(result, TransformKind.PUNCTUATION));
    }

    private static SegmentTransformRequest request(
            SegmentRevision source,
            long firstRevisionId,
            TransformPhase phase,
            String punctuation,
            String itn,
            PersonalizationSnapshot snapshot,
            SegmentTransformPolicy policy) {
        return new SegmentTransformRequest(
                source, firstRevisionId, phase, punctuation, itn, snapshot, policy);
    }

    private static SegmentRevision refined(String text) {
        return revision(1L, RevisionStage.REFINED, text, RevisionOrigin.QUALITY_ASR);
    }

    private static SegmentRevision revision(
            long revisionId,
            RevisionStage stage,
            String text,
            RevisionOrigin origin) {
        return SegmentRevision.text(SESSION, 1L, revisionId, stage, text, origin, true);
    }

    private static TransformDisposition audit(
            SegmentTransformResult result,
            TransformKind kind) {
        return result.audits().stream()
                .filter(audit -> audit.kind() == kind)
                .findFirst()
                .orElseThrow()
                .disposition();
    }
}
