package com.opentypeless.android.speech.transform;

import com.opentypeless.android.personalization.PersonalizedTextProcessor;
import com.opentypeless.android.personalization.ProcessingResult;
import com.opentypeless.android.speech.core.RevisionOrigin;
import com.opentypeless.android.speech.core.RevisionStage;
import com.opentypeless.android.speech.core.SegmentRevision;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic post-ASR transforms. Every accepted text change becomes a monotonic full-segment
 * revision; every rejection/failure preserves the last accepted text and emits a redacted audit.
 */
public final class SegmentTransformPipeline {
    private SegmentTransformPipeline() {}

    public static SegmentTransformResult apply(SegmentTransformRequest request) {
        SegmentRevision current = request.source();
        long nextRevisionId = request.firstRevisionId();
        ArrayList<SegmentRevision> emitted = new ArrayList<>();
        ArrayList<TransformAudit> audits = new ArrayList<>();
        List<Long> termIds = List.of();
        List<Long> correctionIds = List.of();

        boolean punctuationEnabled = switch (request.phase()) {
            case LIVE -> false;
            case SOFT_BOUNDARY -> request.policy().provisionalPunctuation();
            case REFINED -> request.policy().refinedPunctuation();
        };
        if (punctuationEnabled) {
            PunctuationTransform.Decision decision = PunctuationTransform.apply(
                    current.fullText(), request.punctuationCandidate());
            if (decision.changed()) {
                RevisionStage stage = request.phase() == TransformPhase.SOFT_BOUNDARY
                        ? RevisionStage.PROVISIONAL
                        : RevisionStage.REFINED;
                current = revision(current, nextRevisionId++, stage, decision.text(),
                        RevisionOrigin.PUNCTUATION);
                emitted.add(current);
            }
            audits.add(new TransformAudit(
                    TransformKind.PUNCTUATION,
                    decision.rejected()
                            ? TransformDisposition.REJECTED_UNSAFE
                            : decision.changed()
                                    ? TransformDisposition.APPLIED
                                    : TransformDisposition.UNCHANGED,
                    decision.reason()));
        } else {
            audits.add(new TransformAudit(
                    TransformKind.PUNCTUATION,
                    TransformDisposition.SKIPPED_BY_POLICY,
                    request.phase() == TransformPhase.LIVE
                            ? "live revisions are not punctuated before a soft boundary"
                            : "punctuation is disabled"));
        }

        if (request.inverseTextNormalizationCandidate() != null) {
            // v2 deliberately has no general-purpose textual proof that a candidate such as
            // "twenty three" -> "23" preserved the spoken fact. Keep the candidate observable as
            // rejected until a locale/model adapter supplies explicit token evidence.
            audits.add(new TransformAudit(
                    TransformKind.INVERSE_TEXT_NORMALIZATION,
                    request.policy().inverseTextNormalization()
                            ? TransformDisposition.REJECTED_UNSAFE
                            : TransformDisposition.SKIPPED_BY_POLICY,
                    request.policy().inverseTextNormalization()
                            ? "ITN requires locale-specific evidence"
                            : "ITN is disabled"));
        } else {
            audits.add(new TransformAudit(
                    TransformKind.INVERSE_TEXT_NORMALIZATION,
                    TransformDisposition.SKIPPED_BY_POLICY,
                    "no ITN candidate"));
        }

        if (request.policy().personalization()) {
            try {
                ProcessingResult personalized = PersonalizedTextProcessor.apply(
                        current.fullText(), request.personalization());
                termIds = personalized.matchedTermIds();
                correctionIds = personalized.matchedCorrectionIds();
                if (!personalized.text().equals(current.fullText())) {
                    current = revision(
                            current,
                            nextRevisionId,
                            current.stage(),
                            personalized.text(),
                            RevisionOrigin.PERSONALIZATION);
                    emitted.add(current);
                    audits.add(new TransformAudit(
                            TransformKind.PERSONALIZATION,
                            TransformDisposition.APPLIED,
                            "confirmed deterministic mapping applied"));
                } else {
                    audits.add(new TransformAudit(
                            TransformKind.PERSONALIZATION,
                            TransformDisposition.UNCHANGED,
                            "no confirmed mapping changed text"));
                }
            } catch (RuntimeException rejected) {
                audits.add(new TransformAudit(
                        TransformKind.PERSONALIZATION,
                        TransformDisposition.FAILED_SOURCE_PRESERVED,
                        "personalization bounds or processing failure"));
            }
        } else {
            audits.add(new TransformAudit(
                    TransformKind.PERSONALIZATION,
                    TransformDisposition.SKIPPED_BY_POLICY,
                    "personalization is disabled"));
        }

        return new SegmentTransformResult(
                current, emitted, audits, termIds, correctionIds);
    }

    private static SegmentRevision revision(
            SegmentRevision source,
            long revisionId,
            RevisionStage stage,
            String text,
            RevisionOrigin origin) {
        return new SegmentRevision(
                source.sessionId(),
                source.segmentId(),
                revisionId,
                stage,
                text,
                List.of(),
                source.audioStartMs(),
                source.audioEndMs(),
                origin,
                source.providerFinal());
    }
}
