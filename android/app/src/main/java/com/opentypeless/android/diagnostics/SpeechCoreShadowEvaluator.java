package com.opentypeless.android.diagnostics;

import com.opentypeless.android.data.PersonalizationSnapshot;
import com.opentypeless.android.ime.TranscriptUpdate;
import com.opentypeless.android.speech.core.CaptureState;
import com.opentypeless.android.speech.core.SegmentJoin;
import com.opentypeless.android.speech.core.SessionId;
import com.opentypeless.android.speech.core.TerminalReason;
import com.opentypeless.android.speech.engine.EngineCapabilities;
import com.opentypeless.android.speech.engine.EngineCapability;
import com.opentypeless.android.speech.engine.EngineDescriptor;
import com.opentypeless.android.speech.engine.ProcessingLocation;
import com.opentypeless.android.speech.runtime.CoordinatorDisposition;
import com.opentypeless.android.speech.runtime.CoordinatorUpdate;
import com.opentypeless.android.speech.runtime.RuntimeStrategy;
import com.opentypeless.android.speech.runtime.RuntimeStrategyDecision;
import com.opentypeless.android.speech.runtime.SpeechCoreCoordinator;
import com.opentypeless.android.speech.runtime.SpeechSessionToken;
import com.opentypeless.android.speech.runtime.StreamingRevisionInput;
import com.opentypeless.android.speech.transform.SegmentTransformPolicy;
import java.util.List;
import java.util.Objects;

/**
 * Voice-Lab-only adapter that replays the existing pipeline's observable transcript callbacks
 * through Speech Core v2 without changing the production editor path.
 */
public final class SpeechCoreShadowEvaluator {
    private static final long SEGMENT_ID = 1L;

    private final SpeechSessionToken token;
    private final SpeechCoreCoordinator coordinator;
    private long nextProviderSequence = 1L;
    private int acceptedRevisions;
    private int ignoredCallbacks;
    private int earlierTextRevisions;
    private boolean provisionalPunctuationObserved;
    private boolean terminal;
    private String previousText = "";
    private String detail = "shadow replay ready";

    public SpeechCoreShadowEvaluator(long attemptId, ProcessingLocation location) {
        if (attemptId <= 0L) throw new IllegalArgumentException("attemptId must be positive");
        Objects.requireNonNull(location, "location");
        token = new SpeechSessionToken(new SessionId("voice-lab-shadow-" + attemptId), attemptId);
        EngineCapabilities capabilities = location == ProcessingLocation.ON_DEVICE
                ? EngineCapabilities.of(
                        EngineCapability.LIVE_REVISIONS,
                        EngineCapability.ON_DEVICE)
                : EngineCapabilities.of(EngineCapability.LIVE_REVISIONS);
        EngineDescriptor observedCallbacks = new EngineDescriptor(
                "v1-callback-shadow",
                "Existing-pipeline callback shadow",
                "speech-core-v2",
                location,
                capabilities);
        coordinator = new SpeechCoreCoordinator(
                token,
                observedCallbacks,
                null,
                new RuntimeStrategyDecision(
                        RuntimeStrategy.STREAMING_ONLY,
                        0,
                        0,
                        0L,
                        List.of("Voice Lab shadow replay; no editor writes")),
                PersonalizationSnapshot.empty(),
                SegmentTransformPolicy.DEFAULT);
        coordinator.prepare(token);
        coordinator.ready(token);
        coordinator.openSegment(token, SEGMENT_ID, SegmentJoin.NONE);
    }

    public synchronized SpeechCoreShadowSnapshot accept(TranscriptUpdate update) {
        if (terminal || update == null || update.text().isBlank()) {
            ignoredCallbacks++;
            return snapshot();
        }
        String text = update.text();
        long sequence = Math.max(nextProviderSequence, update.sequence());
        nextProviderSequence = sequence + 1L;
        CoordinatorUpdate accepted = coordinator.liveRevision(new StreamingRevisionInput(
                token,
                SEGMENT_ID,
                sequence,
                text,
                List.of(),
                update.finalResult()));
        if (accepted.disposition() == CoordinatorDisposition.APPLIED) {
            acceptedRevisions++;
            if (!previousText.isEmpty()
                    && !text.equals(previousText)
                    && !text.startsWith(previousText)) {
                earlierTextRevisions++;
            }
            if (!update.finalResult() && containsSentencePunctuation(text)) {
                provisionalPunctuationObserved = true;
            }
            previousText = text;
        } else {
            ignoredCallbacks++;
        }
        detail = accepted.detail();
        return snapshot();
    }

    public synchronized SpeechCoreShadowSnapshot complete(String authoritativeRawText) {
        if (terminal) return snapshot();
        String raw = authoritativeRawText == null ? "" : authoritativeRawText.trim();
        if (!raw.isEmpty() && !raw.equals(previousText)) {
            accept(TranscriptUpdate.finalText(
                    nextProviderSequence++, raw, TranscriptUpdate.Source.OPENAI_COMPATIBLE_BATCH));
        }
        if (!coordinator.draft().renderedText().isBlank()) {
            CoordinatorUpdate boundary = coordinator.hardBoundary(token, SEGMENT_ID);
            detail = boundary.detail();
        }
        if (coordinator.draft().captureState() == CaptureState.LISTENING) {
            coordinator.stopRequested(token);
        }
        coordinator.captureEnded(token, TerminalReason.USER_FINISH);
        terminal = true;
        return snapshot();
    }

    public synchronized SpeechCoreShadowSnapshot fail() {
        if (terminal) return snapshot();
        if (!coordinator.draft().renderedText().isBlank()) {
            CoordinatorUpdate boundary = coordinator.hardBoundary(token, SEGMENT_ID);
            detail = boundary.detail();
        }
        coordinator.captureFailed(token, TerminalReason.ENGINE_FAILURE);
        terminal = true;
        return snapshot();
    }

    public synchronized SpeechCoreShadowSnapshot snapshot() {
        return new SpeechCoreShadowSnapshot(
                coordinator.draft().renderedText(),
                coordinator.draft().captureState(),
                coordinator.draft().segments().size(),
                acceptedRevisions,
                ignoredCallbacks,
                earlierTextRevisions,
                provisionalPunctuationObserved,
                terminal,
                detail);
    }

    private static boolean containsSentencePunctuation(String text) {
        return text.codePoints().anyMatch(codePoint -> codePoint == '.'
                || codePoint == ','
                || codePoint == '?'
                || codePoint == '!'
                || codePoint == 0x3002
                || codePoint == 0xFF0C
                || codePoint == 0xFF1F
                || codePoint == 0xFF01);
    }
}
