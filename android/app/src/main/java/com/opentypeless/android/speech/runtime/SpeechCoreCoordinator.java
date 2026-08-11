package com.opentypeless.android.speech.runtime;

import com.opentypeless.android.data.PersonalizationSnapshot;
import com.opentypeless.android.speech.core.CaptureState;
import com.opentypeless.android.speech.core.DeliveryState;
import com.opentypeless.android.speech.core.ReductionDisposition;
import com.opentypeless.android.speech.core.RevisionOrigin;
import com.opentypeless.android.speech.core.RevisionStage;
import com.opentypeless.android.speech.core.SegmentJoin;
import com.opentypeless.android.speech.core.SegmentRevision;
import com.opentypeless.android.speech.core.TerminalReason;
import com.opentypeless.android.speech.core.TokenEvidence;
import com.opentypeless.android.speech.core.VoiceDraft;
import com.opentypeless.android.speech.core.VoiceDraftEvent;
import com.opentypeless.android.speech.core.VoiceDraftLimits;
import com.opentypeless.android.speech.core.VoiceDraftReducer;
import com.opentypeless.android.speech.core.VoiceDraftReduction;
import com.opentypeless.android.speech.engine.EngineCapability;
import com.opentypeless.android.speech.engine.EngineDescriptor;
import com.opentypeless.android.speech.transform.SegmentTransformPipeline;
import com.opentypeless.android.speech.transform.SegmentTransformPolicy;
import com.opentypeless.android.speech.transform.SegmentTransformRequest;
import com.opentypeless.android.speech.transform.SegmentTransformResult;
import com.opentypeless.android.speech.transform.TransformAudit;
import com.opentypeless.android.speech.transform.TransformPhase;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Android-free orchestration authority for one Speech Core v2 generation.
 *
 * <p>Provider revision numbers are never trusted as document revision numbers. This coordinator
 * consumes them for duplicate/order checks and allocates its own monotonic revision IDs so
 * punctuation and personalization can coexist with later provider revisions without collisions.
 */
public final class SpeechCoreCoordinator {
    private static final int MAX_PROVIDER_TEXT_CODE_POINTS = 20_000;
    private static final int MAX_TOKEN_EVIDENCE = 4_096;

    private final SpeechSessionToken token;
    private final EngineDescriptor streamingEngine;
    private final Optional<EngineDescriptor> qualityEngine;
    private final RuntimeStrategyDecision runtimeStrategy;
    private final PersonalizationSnapshot personalization;
    private final SegmentTransformPolicy transformPolicy;
    private final VoiceDraftReducer reducer;
    private final QualityJobLedger qualityJobs;
    private final Map<Long, Long> providerSequenceBySegment = new HashMap<>();
    private final Map<Long, StreamingFingerprint> providerFingerprintBySegment = new HashMap<>();
    private final Map<Long, Long> nextRevisionBySegment = new HashMap<>();
    private VoiceDraft draft;

    public SpeechCoreCoordinator(
            SpeechSessionToken token,
            EngineDescriptor streamingEngine,
            EngineDescriptor qualityEngine,
            RuntimeStrategyDecision runtimeStrategy,
            PersonalizationSnapshot personalization,
            SegmentTransformPolicy transformPolicy) {
        this(
                token,
                streamingEngine,
                qualityEngine,
                runtimeStrategy,
                personalization,
                transformPolicy,
                VoiceDraftLimits.DEFAULT);
    }

    public SpeechCoreCoordinator(
            SpeechSessionToken token,
            EngineDescriptor streamingEngine,
            EngineDescriptor qualityEngine,
            RuntimeStrategyDecision runtimeStrategy,
            PersonalizationSnapshot personalization,
            SegmentTransformPolicy transformPolicy,
            VoiceDraftLimits limits) {
        this.token = Objects.requireNonNull(token, "token");
        this.streamingEngine = Objects.requireNonNull(streamingEngine, "streamingEngine");
        this.qualityEngine = Optional.ofNullable(qualityEngine);
        this.personalization = personalization == null
                ? PersonalizationSnapshot.empty()
                : personalization;
        this.transformPolicy = transformPolicy == null
                ? SegmentTransformPolicy.DEFAULT
                : transformPolicy;
        this.runtimeStrategy = Objects.requireNonNull(runtimeStrategy, "runtimeStrategy");
        validateEngineContracts(streamingEngine, this.qualityEngine, this.runtimeStrategy);
        reducer = new VoiceDraftReducer(Objects.requireNonNull(limits, "limits"));
        qualityJobs = new QualityJobLedger(token.sessionId(), token.generation(), runtimeStrategy);
        draft = VoiceDraft.initial(token.sessionId());
    }

    public synchronized VoiceDraft draft() {
        return draft;
    }

    public SpeechSessionToken token() {
        return token;
    }

    public EngineDescriptor streamingEngine() {
        return streamingEngine;
    }

    public Optional<EngineDescriptor> qualityEngine() {
        return qualityEngine;
    }

    public RuntimeStrategyDecision runtimeStrategy() {
        return runtimeStrategy;
    }

    public synchronized CoordinatorUpdate prepare(SpeechSessionToken callbackToken) {
        return reduce(callbackToken, new VoiceDraftEvent.Prepare(token.sessionId()));
    }

    public synchronized CoordinatorUpdate ready(SpeechSessionToken callbackToken) {
        return reduce(callbackToken, new VoiceDraftEvent.Ready(token.sessionId()));
    }

    public synchronized CoordinatorUpdate stopRequested(SpeechSessionToken callbackToken) {
        return reduce(callbackToken, new VoiceDraftEvent.StopRequested(token.sessionId()));
    }

    public synchronized CoordinatorUpdate captureEnded(
            SpeechSessionToken callbackToken,
            TerminalReason reason) {
        return reduce(callbackToken, new VoiceDraftEvent.CaptureEnded(token.sessionId(), reason));
    }

    public synchronized CoordinatorUpdate captureFailed(
            SpeechSessionToken callbackToken,
            TerminalReason reason) {
        return reduce(callbackToken, new VoiceDraftEvent.CaptureFailed(token.sessionId(), reason));
    }

    public synchronized CoordinatorUpdate deliveryChanged(
            SpeechSessionToken callbackToken,
            long segmentId,
            DeliveryState deliveryState) {
        return reduce(
                callbackToken,
                new VoiceDraftEvent.DeliveryChanged(
                        token.sessionId(), segmentId, deliveryState));
    }

    public synchronized CoordinatorUpdate targetDetached(
            SpeechSessionToken callbackToken,
            long segmentId) {
        return reduce(
                callbackToken,
                new VoiceDraftEvent.TargetDetached(token.sessionId(), segmentId));
    }

    public synchronized CoordinatorUpdate openSegment(
            SpeechSessionToken callbackToken,
            long segmentId,
            SegmentJoin joinBefore) {
        return reduce(
                callbackToken,
                new VoiceDraftEvent.OpenSegment(token.sessionId(), segmentId, joinBefore));
    }

    public synchronized CoordinatorUpdate liveRevision(StreamingRevisionInput input) {
        if (!owned(input.session())) return rejectedSession();
        if (!streamingEngine.capabilities().supports(EngineCapability.LIVE_REVISIONS)) {
            return update(
                    CoordinatorDisposition.REJECTED_CAPABILITY,
                    "streaming engine did not declare live revisions");
        }
        if (input.fullText().codePointCount(0, input.fullText().length())
                        > MAX_PROVIDER_TEXT_CODE_POINTS
                || input.tokenEvidence().size() > MAX_TOKEN_EVIDENCE) {
            return update(CoordinatorDisposition.REJECTED_BOUNDS, "streaming revision is oversized");
        }
        String capabilityError = validateEvidence(input.tokenEvidence());
        if (capabilityError != null) {
            return update(CoordinatorDisposition.REJECTED_CAPABILITY, capabilityError);
        }
        long previousSequence = providerSequenceBySegment.getOrDefault(input.segmentId(), 0L);
        StreamingFingerprint fingerprint = new StreamingFingerprint(
                input.providerRevisionSequence(),
                input.fullText(),
                input.tokenEvidence(),
                input.providerFinal());
        if (input.providerRevisionSequence() < previousSequence) {
            return update(CoordinatorDisposition.IGNORED_STALE, "provider revision is stale");
        }
        if (input.providerRevisionSequence() == previousSequence) {
            return fingerprint.equals(providerFingerprintBySegment.get(input.segmentId()))
                    ? update(CoordinatorDisposition.IGNORED_DUPLICATE, "provider revision duplicated")
                    : update(CoordinatorDisposition.REJECTED_STATE,
                            "provider reused a revision sequence with different content");
        }
        providerSequenceBySegment.put(input.segmentId(), input.providerRevisionSequence());
        providerFingerprintBySegment.put(input.segmentId(), fingerprint);

        long revisionId = allocateRevisionId(input.segmentId());
        SegmentRevision source = new SegmentRevision(
                token.sessionId(),
                input.segmentId(),
                revisionId,
                RevisionStage.LIVE,
                input.fullText(),
                input.tokenEvidence(),
                SegmentRevision.UNKNOWN_AUDIO_TIME,
                SegmentRevision.UNKNOWN_AUDIO_TIME,
                RevisionOrigin.STREAM_ASR,
                input.providerFinal());
        Batch batch = new Batch();
        if (!batch.reduce(new VoiceDraftEvent.RevisionArrived(source))) return batch.finish();
        applyTransforms(batch, source, TransformPhase.LIVE, null, null);
        return batch.finish();
    }

    public synchronized CoordinatorUpdate softBoundary(
            SpeechSessionToken callbackToken,
            long segmentId,
            String punctuationCandidate) {
        if (!owned(callbackToken)) return rejectedSession();
        Batch batch = new Batch();
        if (!batch.reduce(new VoiceDraftEvent.SoftBoundary(token.sessionId(), segmentId))) {
            return batch.finish();
        }
        Optional<SegmentRevision> transformSource = latestStreamingRevision(segmentId);
        if (transformSource.isEmpty()) transformSource = visibleRevision(segmentId);
        if (transformSource.isPresent()) {
            applyTransforms(
                    batch,
                    transformSource.get(),
                    TransformPhase.SOFT_BOUNDARY,
                    punctuationCandidate,
                    null);
        }
        return batch.finish();
    }

    public synchronized CoordinatorUpdate reopenSegment(
            SpeechSessionToken callbackToken,
            long segmentId) {
        return reduce(
                callbackToken,
                new VoiceDraftEvent.ReopenSegment(token.sessionId(), segmentId));
    }

    public synchronized CoordinatorUpdate hardBoundary(
            SpeechSessionToken callbackToken,
            long segmentId) {
        if (!owned(callbackToken)) return rejectedSession();
        Batch batch = new Batch();
        if (!batch.reduce(new VoiceDraftEvent.HardBoundary(token.sessionId(), segmentId))) {
            return batch.finish();
        }
        QualityJobUpdate queued = qualityJobs.enqueue(segmentId);
        if (queued.disposition() == QualityJobDisposition.APPLIED) {
            batch.qualityJobs.addAll(qualityJobs.claimAvailable());
            return batch.finish();
        }
        if (queued.disposition() == QualityJobDisposition.SKIPPED_STRATEGY
                || queued.disposition() == QualityJobDisposition.REJECTED_BOUNDS) {
            boolean sealed = sealStreamingFallback(batch, segmentId, null);
            if (sealed) batch.fallback = true;
            return batch.finish();
        }
        batch.override(
                CoordinatorDisposition.REJECTED_STATE,
                "quality scheduling rejected the closed segment");
        return batch.finish();
    }

    public synchronized CoordinatorUpdate qualitySucceeded(
            QualityJobToken job,
            String qualityText,
            String punctuationCandidate,
            String inverseTextNormalizationCandidate) {
        QualityJobUpdate claimed = qualityJobs.complete(job);
        if (claimed.disposition() != QualityJobDisposition.APPLIED) {
            return qualityRejected(claimed);
        }
        Batch batch = new Batch();
        if (qualityText == null
                || qualityText.isBlank()
                || qualityText.codePointCount(0, qualityText.length())
                        > MAX_PROVIDER_TEXT_CODE_POINTS) {
            if (sealStreamingFallback(batch, job.segmentId(), punctuationCandidate)) {
                batch.fallback = true;
            }
            batch.qualityJobs.addAll(qualityJobs.claimAvailable());
            return batch.finish();
        }
        SegmentRevision quality = SegmentRevision.text(
                token.sessionId(),
                job.segmentId(),
                allocateRevisionId(job.segmentId()),
                RevisionStage.REFINED,
                qualityText,
                RevisionOrigin.QUALITY_ASR,
                true);
        if (!batch.reduce(new VoiceDraftEvent.RevisionArrived(quality))) {
            sealStreamingFallback(batch, job.segmentId(), punctuationCandidate);
            batch.fallback = true;
            batch.qualityJobs.addAll(qualityJobs.claimAvailable());
            return batch.finish();
        }
        applyTransforms(
                batch,
                quality,
                TransformPhase.REFINED,
                punctuationCandidate,
                inverseTextNormalizationCandidate);
        batch.reduce(new VoiceDraftEvent.SealSegment(token.sessionId(), job.segmentId()));
        batch.qualityJobs.addAll(qualityJobs.claimAvailable());
        return batch.finish();
    }

    public synchronized CoordinatorUpdate qualityFailed(QualityJobToken job) {
        QualityJobUpdate claimed = qualityJobs.fail(job);
        if (claimed.disposition() != QualityJobDisposition.APPLIED) {
            return qualityRejected(claimed);
        }
        return qualityFallback(job.segmentId(), "quality pass failed");
    }

    public synchronized CoordinatorUpdate qualityTimedOut(QualityJobToken job) {
        QualityJobUpdate claimed = qualityJobs.timeout(job);
        if (claimed.disposition() != QualityJobDisposition.APPLIED) {
            return qualityRejected(claimed);
        }
        return qualityFallback(job.segmentId(), "quality pass timed out");
    }

    public synchronized CoordinatorUpdate explicitDiscard(SpeechSessionToken callbackToken) {
        if (!owned(callbackToken)) return rejectedSession();
        qualityJobs.cancelAll();
        providerSequenceBySegment.clear();
        providerFingerprintBySegment.clear();
        nextRevisionBySegment.clear();
        return reduce(callbackToken, new VoiceDraftEvent.ExplicitDiscard(token.sessionId()));
    }

    private CoordinatorUpdate qualityFallback(long segmentId, String reason) {
        Batch batch = new Batch();
        if (sealStreamingFallback(batch, segmentId, null)) batch.fallback = true;
        batch.detail = reason + "; safe streaming text retained";
        batch.qualityJobs.addAll(qualityJobs.claimAvailable());
        return batch.finish();
    }

    private boolean sealStreamingFallback(
            Batch batch,
            long segmentId,
            String punctuationCandidate) {
        Optional<SegmentRevision> visible = visibleRevision(segmentId);
        if (visible.isEmpty() || visible.get().fullText().isBlank()) {
            batch.override(
                    CoordinatorDisposition.REJECTED_STATE,
                    "closed segment has no safe streaming text to retain");
            return false;
        }
        SegmentRevision source = visible.get();
        SegmentRevision fallback = new SegmentRevision(
                token.sessionId(),
                segmentId,
                allocateRevisionId(segmentId),
                RevisionStage.REFINED,
                source.fullText(),
                source.tokenEvidence(),
                source.audioStartMs(),
                source.audioEndMs(),
                RevisionOrigin.STREAMING_FALLBACK,
                true);
        if (!batch.reduce(new VoiceDraftEvent.RevisionArrived(fallback))) return false;
        applyTransforms(
                batch,
                fallback,
                TransformPhase.REFINED,
                punctuationCandidate,
                null);
        return batch.reduce(new VoiceDraftEvent.SealSegment(token.sessionId(), segmentId));
    }

    private void applyTransforms(
            Batch batch,
            SegmentRevision source,
            TransformPhase phase,
            String punctuationCandidate,
            String itnCandidate) {
        SegmentTransformResult transformed = SegmentTransformPipeline.apply(
                new SegmentTransformRequest(
                        source,
                        nextRevisionId(source.segmentId()),
                        phase,
                        punctuationCandidate,
                        itnCandidate,
                        personalization,
                        transformPolicy));
        batch.audits.addAll(transformed.audits());
        for (SegmentRevision revision : transformed.emittedRevisions()) {
            if (!batch.reduce(new VoiceDraftEvent.RevisionArrived(revision))) break;
            nextRevisionBySegment.put(revision.segmentId(), revision.revisionId() + 1L);
        }
    }

    private Optional<SegmentRevision> visibleRevision(long segmentId) {
        return draft.segment(segmentId).flatMap(segment -> segment.visibleRevision());
    }

    private Optional<SegmentRevision> latestStreamingRevision(long segmentId) {
        return draft.segment(segmentId).flatMap(segment -> {
            List<SegmentRevision> revisions = segment.revisions();
            for (int index = revisions.size() - 1; index >= 0; index--) {
                SegmentRevision revision = revisions.get(index);
                if (revision.stage() == RevisionStage.LIVE
                        && revision.origin() == RevisionOrigin.STREAM_ASR) {
                    return Optional.of(revision);
                }
            }
            return Optional.empty();
        });
    }

    private CoordinatorUpdate reduce(
            SpeechSessionToken callbackToken,
            VoiceDraftEvent event) {
        if (!owned(callbackToken)) return rejectedSession();
        Batch batch = new Batch();
        batch.reduce(event);
        return batch.finish();
    }

    private boolean owned(SpeechSessionToken callbackToken) {
        return token.equals(callbackToken);
    }

    private long allocateRevisionId(long segmentId) {
        long next = nextRevisionId(segmentId);
        nextRevisionBySegment.put(segmentId, Math.addExact(next, 1L));
        return next;
    }

    private long nextRevisionId(long segmentId) {
        return nextRevisionBySegment.getOrDefault(segmentId, 1L);
    }

    private String validateEvidence(List<TokenEvidence> evidence) {
        for (TokenEvidence tokenEvidence : evidence) {
            if (tokenEvidence.confidence().isPresent()
                    && !streamingEngine.capabilities().supports(EngineCapability.CONFIDENCE)) {
                return "engine supplied undeclared confidence evidence";
            }
            if (tokenEvidence.stable().isPresent()
                    && !streamingEngine.capabilities().supports(EngineCapability.TOKEN_STABILITY)) {
                return "engine supplied undeclared stability evidence";
            }
            if (tokenEvidence.audioStartMs().isPresent()
                    && !streamingEngine.capabilities().supports(EngineCapability.TOKEN_TIMESTAMPS)) {
                return "engine supplied undeclared timestamp evidence";
            }
        }
        return null;
    }

    private CoordinatorUpdate qualityRejected(QualityJobUpdate update) {
        CoordinatorDisposition disposition = switch (update.disposition()) {
            case REJECTED_SESSION -> CoordinatorDisposition.REJECTED_SESSION;
            case IGNORED_DUPLICATE -> CoordinatorDisposition.IGNORED_DUPLICATE;
            case REJECTED_BOUNDS -> CoordinatorDisposition.REJECTED_BOUNDS;
            default -> CoordinatorDisposition.REJECTED_STATE;
        };
        return update(disposition, update.detail());
    }

    private CoordinatorUpdate rejectedSession() {
        return update(CoordinatorDisposition.REJECTED_SESSION, "callback session generation is stale");
    }

    private CoordinatorUpdate update(CoordinatorDisposition disposition, String detail) {
        return new CoordinatorUpdate(
                draft, disposition, detail, List.of(), List.of(), List.of(), false);
    }

    private static void validateEngineContracts(
            EngineDescriptor streaming,
            Optional<EngineDescriptor> quality,
            RuntimeStrategyDecision strategy) {
        if (!streaming.capabilities().supports(EngineCapability.LIVE_REVISIONS)) {
            throw new IllegalArgumentException("v2 streaming engine must declare live revisions");
        }
        boolean qualityStrategy = strategy.strategy() == RuntimeStrategy.SEQUENTIAL_TWO_PASS
                || strategy.strategy() == RuntimeStrategy.CONCURRENT_TWO_PASS;
        if (qualityStrategy
                && (quality.isEmpty()
                        || !quality.get().capabilities()
                                .supports(EngineCapability.SEGMENT_FINALS))) {
            throw new IllegalArgumentException(
                    "two-pass strategy requires a segment-final quality engine");
        }
    }

    private final class Batch {
        private final ArrayList<VoiceDraftEvent> events = new ArrayList<>();
        private final ArrayList<TransformAudit> audits = new ArrayList<>();
        private final ArrayList<QualityJobToken> qualityJobs = new ArrayList<>();
        private CoordinatorDisposition disposition = CoordinatorDisposition.APPLIED;
        private String detail = "state advanced";
        private boolean projectionChanged;
        private boolean fallback;

        private boolean reduce(VoiceDraftEvent event) {
            VoiceDraft before = draft;
            VoiceDraftReduction reduction = reducer.reduce(draft, event);
            draft = reduction.draft();
            if (reduction.disposition() == ReductionDisposition.APPLIED) {
                events.add(event);
                projectionChanged |= projectionChanged(before, draft);
                detail = reduction.detail();
                return true;
            }
            override(map(reduction.disposition()), reduction.detail());
            return false;
        }

        private void override(CoordinatorDisposition updated, String updatedDetail) {
            disposition = updated;
            detail = updatedDetail;
        }

        private CoordinatorUpdate finish() {
            if (fallback && disposition == CoordinatorDisposition.APPLIED) {
                disposition = CoordinatorDisposition.APPLIED_STREAMING_FALLBACK;
            }
            return new CoordinatorUpdate(
                    draft,
                    disposition,
                    detail,
                    events,
                    audits,
                    qualityJobs,
                    projectionChanged);
        }
    }

    private static boolean projectionChanged(VoiceDraft before, VoiceDraft after) {
        return !before.renderedText().equals(after.renderedText())
                || !before.segments().equals(after.segments())
                || (before.captureState() == CaptureState.DISCARDED
                        && after.captureState() != CaptureState.DISCARDED)
                || (before.captureState() != CaptureState.DISCARDED
                        && after.captureState() == CaptureState.DISCARDED);
    }

    private static CoordinatorDisposition map(ReductionDisposition disposition) {
        return switch (disposition) {
            case IGNORED_DUPLICATE -> CoordinatorDisposition.IGNORED_DUPLICATE;
            case IGNORED_STALE -> CoordinatorDisposition.IGNORED_STALE;
            case REJECTED_SESSION -> CoordinatorDisposition.REJECTED_SESSION;
            case REJECTED_BOUNDS -> CoordinatorDisposition.REJECTED_BOUNDS;
            case IGNORED_BLANK,
                    IGNORED_TERMINAL,
                    REJECTED_TRANSITION,
                    REJECTED_MISSING_SEGMENT,
                    REJECTED_LOCKED,
                    REJECTED_CONFLICT -> CoordinatorDisposition.REJECTED_STATE;
            case APPLIED -> CoordinatorDisposition.APPLIED;
        };
    }

    private record StreamingFingerprint(
            long providerSequence,
            String fullText,
            List<TokenEvidence> evidence,
            boolean providerFinal) {
        private StreamingFingerprint {
            evidence = List.copyOf(evidence);
        }
    }
}
