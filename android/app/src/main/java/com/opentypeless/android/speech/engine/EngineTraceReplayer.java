package com.opentypeless.android.speech.engine;

import com.opentypeless.android.speech.core.ReductionDisposition;
import com.opentypeless.android.speech.core.RevisionOrigin;
import com.opentypeless.android.speech.core.RevisionStage;
import com.opentypeless.android.speech.core.SegmentRevision;
import com.opentypeless.android.speech.core.TokenEvidence;
import com.opentypeless.android.speech.core.VoiceDraft;
import com.opentypeless.android.speech.core.VoiceDraftLimits;
import com.opentypeless.android.speech.core.VoiceDraftReducer;
import com.opentypeless.android.speech.core.VoiceDraftReduction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Validates provider claims, then replays normalized events through the authoritative reducer. */
public final class EngineTraceReplayer {
    private final EngineTraceLimits traceLimits;
    private final VoiceDraftReducer reducer;

    public EngineTraceReplayer() {
        this(EngineTraceLimits.DEFAULT, VoiceDraftLimits.DEFAULT);
    }

    public EngineTraceReplayer(
            EngineTraceLimits traceLimits, VoiceDraftLimits voiceDraftLimits) {
        this.traceLimits = Objects.requireNonNull(traceLimits, "traceLimits");
        reducer = new VoiceDraftReducer(Objects.requireNonNull(voiceDraftLimits, "voiceDraftLimits"));
    }

    public EngineReplayReport replay(EngineTrace trace) {
        Objects.requireNonNull(trace, "trace");
        if (trace.events().size() > traceLimits.maxEvents()) {
            throw new IllegalArgumentException("engine trace event limit exceeded");
        }

        VoiceDraft draft = VoiceDraft.initial(trace.sessionId());
        ArrayList<EngineReplayStep> steps = new ArrayList<>(trace.events().size());
        Map<Long, EngineEvent> seenSequences = new HashMap<>();
        long highestSequence = 0L;

        for (EngineEvent event : trace.events()) {
            EngineEvent seen = seenSequences.get(event.eventSequence());
            if (event.eventSequence() <= highestSequence) {
                if (event.equals(seen)) {
                    steps.add(step(
                            event,
                            ReplayDisposition.IGNORED,
                            Optional.empty(),
                            "duplicate source event"));
                } else {
                    steps.add(step(
                            event,
                            ReplayDisposition.REJECTED_SOURCE_ORDER,
                            Optional.empty(),
                            "event sequence is out of order or reused"));
                }
                continue;
            }
            highestSequence = event.eventSequence();
            seenSequences.put(event.eventSequence(), event);

            if (!trace.engine().engineId().equals(event.engineId())) {
                steps.add(step(
                        event,
                        ReplayDisposition.REJECTED_ENGINE,
                        Optional.empty(),
                        "event engine does not match actual route"));
                continue;
            }
            Optional<String> capabilityError = validateCapabilities(trace.engine(), event);
            if (capabilityError.isPresent()) {
                steps.add(step(
                        event,
                        ReplayDisposition.REJECTED_CAPABILITY,
                        Optional.empty(),
                        capabilityError.get()));
                continue;
            }

            VoiceDraftReduction reduction = reducer.reduce(draft, event.toCoreEvent());
            ReplayDisposition disposition = replayDisposition(reduction.disposition());
            steps.add(step(
                    event,
                    disposition,
                    Optional.of(reduction.disposition()),
                    reduction.detail()));
            draft = reduction.draft();
        }
        return new EngineReplayReport(trace.engine(), draft, steps);
    }

    private Optional<String> validateCapabilities(
            EngineDescriptor descriptor, EngineEvent event) {
        if (!(event instanceof EngineEvent.Transcript transcript)) {
            return Optional.empty();
        }
        SegmentRevision revision = transcript.revision();
        int textCodePoints = revision.fullText().codePointCount(0, revision.fullText().length());
        if (textCodePoints > traceLimits.maxTextCodePoints()) {
            return Optional.of("transcript text exceeds trace limit");
        }
        if (revision.tokenEvidence().size() > traceLimits.maxTokensPerRevision()) {
            return Optional.of("token evidence exceeds trace limit");
        }
        EngineCapabilities capabilities = descriptor.capabilities();
        if (revision.stage() == RevisionStage.USER_LOCKED
                || revision.origin() == RevisionOrigin.USER
                || revision.origin() == RevisionOrigin.PERSONALIZATION
                || revision.origin() == RevisionOrigin.STREAMING_FALLBACK) {
            return Optional.of("engine cannot emit core-owned revisions");
        }
        if ((revision.origin() == RevisionOrigin.STREAM_ASR
                        && revision.stage() != RevisionStage.LIVE)
                || (revision.origin() == RevisionOrigin.QUALITY_ASR
                        && revision.stage() != RevisionStage.REFINED)
                || (revision.origin() == RevisionOrigin.PUNCTUATION
                        && revision.stage() != RevisionStage.PROVISIONAL
                        && revision.stage() != RevisionStage.REFINED)
                || (revision.origin() == RevisionOrigin.INVERSE_TEXT_NORMALIZATION
                        && revision.stage() != RevisionStage.REFINED)) {
            return Optional.of("revision origin and stability stage are inconsistent");
        }
        if (revision.stage() == RevisionStage.LIVE) {
            boolean supported = revision.providerFinal()
                    ? capabilities.supports(EngineCapability.LIVE_REVISIONS)
                            || capabilities.supports(EngineCapability.SEGMENT_FINALS)
                    : capabilities.supports(EngineCapability.LIVE_REVISIONS);
            if (!supported) {
                return Optional.of("engine did not declare live or final transcript capability");
            }
        }
        if (revision.stage() == RevisionStage.PROVISIONAL
                && !capabilities.supports(EngineCapability.LIVE_REVISIONS)) {
            return Optional.of("engine did not declare provisional live revisions");
        }
        if (revision.stage() == RevisionStage.REFINED
                && !capabilities.supports(EngineCapability.SEGMENT_FINALS)) {
            return Optional.of("engine did not declare segment finals");
        }
        if (revision.origin() == RevisionOrigin.PUNCTUATION
                && !capabilities.supports(EngineCapability.AUTOMATIC_PUNCTUATION)) {
            return Optional.of("engine did not declare automatic punctuation");
        }
        if (revision.origin() == RevisionOrigin.INVERSE_TEXT_NORMALIZATION
                && !capabilities.supports(EngineCapability.INVERSE_TEXT_NORMALIZATION)) {
            return Optional.of("engine did not declare inverse text normalization");
        }
        for (TokenEvidence token : revision.tokenEvidence()) {
            if (token.confidence().isPresent()
                    && !capabilities.supports(EngineCapability.CONFIDENCE)) {
                return Optional.of("engine did not declare confidence evidence");
            }
            if (token.stable().isPresent()
                    && !capabilities.supports(EngineCapability.TOKEN_STABILITY)) {
                return Optional.of("engine did not declare token stability");
            }
            if (token.audioStartMs().isPresent()
                    && !capabilities.supports(EngineCapability.TOKEN_TIMESTAMPS)) {
                return Optional.of("engine did not declare token timestamps");
            }
        }
        return Optional.empty();
    }

    private static ReplayDisposition replayDisposition(ReductionDisposition disposition) {
        if (disposition == ReductionDisposition.APPLIED) {
            return ReplayDisposition.APPLIED;
        }
        if (disposition.name().startsWith("IGNORED_")) {
            return ReplayDisposition.IGNORED;
        }
        return ReplayDisposition.REJECTED_CORE;
    }

    private static EngineReplayStep step(
            EngineEvent event,
            ReplayDisposition disposition,
            Optional<ReductionDisposition> coreDisposition,
            String detail) {
        return new EngineReplayStep(
                event.eventSequence(), disposition, coreDisposition, detail);
    }
}
