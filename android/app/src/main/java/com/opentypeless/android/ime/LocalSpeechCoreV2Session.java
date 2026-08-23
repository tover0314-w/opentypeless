package com.opentypeless.android.ime;

import android.content.Context;

import com.opentypeless.android.audio.AudioCapture;
import com.opentypeless.android.audio.StreamingAudioResult;
import com.opentypeless.android.audio.WavEncoder;
import com.opentypeless.android.context.InputPolicy;
import com.opentypeless.android.data.PersonalizationSnapshot;
import com.opentypeless.android.offline.LocalOfflineRecognitionClient;
import com.opentypeless.android.offline.LocalOfflineRecognizer;
import com.opentypeless.android.offline.LocalPunctuationRecognitionClient;
import com.opentypeless.android.offline.LocalPunctuationRecognizer;
import com.opentypeless.android.offline.LocalRealtimeRecognitionClient;
import com.opentypeless.android.offline.OfflineModelSpec;
import com.opentypeless.android.offline.OfflineStreamingModelSpec;
import com.opentypeless.android.offline.OfflineStreamingRecognizer;
import com.opentypeless.android.speech.audio.BoundarySignal;
import com.opentypeless.android.speech.audio.ContinuousSegmentAssembler;
import com.opentypeless.android.speech.audio.EndpointPolicy;
import com.opentypeless.android.speech.audio.Pcm16Chunk;
import com.opentypeless.android.speech.audio.SegmentAudio;
import com.opentypeless.android.speech.audio.SegmentAudioUpdate;
import com.opentypeless.android.speech.audio.StreamingFrameVad;
import com.opentypeless.android.speech.core.CaptureState;
import com.opentypeless.android.speech.core.SegmentJoin;
import com.opentypeless.android.speech.core.SessionId;
import com.opentypeless.android.speech.core.TerminalReason;
import com.opentypeless.android.speech.core.VoiceDraft;
import com.opentypeless.android.speech.core.VoiceSegment;
import com.opentypeless.android.speech.delivery.ProjectionDocument;
import com.opentypeless.android.speech.delivery.ProjectionMode;
import com.opentypeless.android.speech.delivery.VoiceDraftProjectionPlanner;
import com.opentypeless.android.speech.engine.EngineCapabilities;
import com.opentypeless.android.speech.engine.EngineCapability;
import com.opentypeless.android.speech.engine.EngineDescriptor;
import com.opentypeless.android.speech.engine.ProcessingLocation;
import com.opentypeless.android.speech.journal.JournalSessionMetadata;
import com.opentypeless.android.speech.journal.JournalToken;
import com.opentypeless.android.speech.journal.SpeechCoreJournalWriter;
import com.opentypeless.android.speech.journal.VoiceDraftJournal;
import com.opentypeless.android.speech.runtime.AndroidRuntimeResources;
import com.opentypeless.android.speech.runtime.CoordinatorDisposition;
import com.opentypeless.android.speech.runtime.CoordinatorUpdate;
import com.opentypeless.android.speech.runtime.LocalRuntimeSelector;
import com.opentypeless.android.speech.runtime.QualityJobToken;
import com.opentypeless.android.speech.runtime.RuntimeCapabilities;
import com.opentypeless.android.speech.runtime.RuntimePolicy;
import com.opentypeless.android.speech.runtime.RuntimeStrategy;
import com.opentypeless.android.speech.runtime.RuntimeStrategyDecision;
import com.opentypeless.android.speech.runtime.SegmentJoinPolicy;
import com.opentypeless.android.speech.runtime.SpeechCoreCoordinator;
import com.opentypeless.android.speech.runtime.SpeechSessionToken;
import com.opentypeless.android.speech.runtime.StreamingHypothesisSlicer;
import com.opentypeless.android.speech.runtime.StreamingRevisionInput;
import com.opentypeless.android.speech.transform.SegmentTransformPolicy;
import com.opentypeless.android.settings.ProcessingMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Android adapter that makes Speech Core v2 the real local-offline recognition path.
 *
 * <p>Audio capture is continuous. Streaming revisions, pause punctuation, segment quality work,
 * encrypted recovery, and the editor projection document all share one session generation. The
 * adapter never mutates an {@code InputConnection}; the IME remains the sole delivery authority.
 */
final class LocalSpeechCoreV2Session implements AutoCloseable {
    interface Observer {
        boolean isCurrent();
        void onReadyForSpeech();
        void onBeginningOfSpeech();
        void onDocument(ProjectionDocument document, String renderedText, boolean terminalPreview);
        void onStatus(String message);
        void onJournalCreated(JournalToken token);
    }

    record Result(
            String rawText,
            String renderedText,
            long durationMs,
            boolean reachedLimit,
            boolean autoStopped,
            RuntimeStrategyDecision runtimeStrategy,
            int segmentCount,
            String recoveryId) {}

    private record SegmentPayload(byte[] wav, SegmentAudio journalAudio) {
        SegmentPayload {
            Objects.requireNonNull(wav, "wav");
            Objects.requireNonNull(journalAudio, "journalAudio");
        }

        void zeroize() {
            Arrays.fill(wav, (byte) 0);
            journalAudio.zeroize();
        }
    }

    private static final long JOURNAL_FLUSH_TIMEOUT_MS = 2_000L;

    private final Object lock = new Object();
    private final Context context;
    private final DictationRequest request;
    private final long generation;
    private final AudioCapture audioCapture;
    private final LocalRealtimeRecognitionClient streamingClient;
    private final LocalOfflineRecognitionClient qualityClient;
    private final LocalPunctuationRecognitionClient punctuationClient;
    private final ExecutorService qualityExecutor;
    private final ExecutorService punctuationExecutor;
    private final Observer observer;
    private final ContinuousSegmentAssembler assembler =
            new ContinuousSegmentAssembler(AudioCapture.SAMPLE_RATE, EndpointPolicy.DEFAULT);
    private final StreamingFrameVad frameVad = new StreamingFrameVad(AudioCapture.SAMPLE_RATE);
    private final StreamingHypothesisSlicer slicer = new StreamingHypothesisSlicer();
    private final SpeechSessionToken token;
    private final SpeechCoreCoordinator coordinator;
    private final RuntimeStrategyDecision runtimeStrategy;
    private final boolean punctuationExecutionAllowed;
    private final ProjectionMode projectionMode;
    private final Map<Long, String> rawBySegment = new LinkedHashMap<>();
    private final Map<Long, SegmentPayload> payloadBySegment = new HashMap<>();
    private final Map<QualityJobToken, Future<?>> qualityFutures = new LinkedHashMap<>();
    private final List<QualityJobToken> deferredQuality = new ArrayList<>();
    private final List<Future<?>> punctuationFutures = new ArrayList<>();
    private final Set<QualityJobToken> terminalQuality = new java.util.HashSet<>();
    private final JournalQueue journal;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean explicitlyDiscarded = new AtomicBoolean();
    private volatile LocalRealtimeRecognitionClient.Session streamingSession;
    private long sampleCursor;
    private long activeSegmentId;
    private long providerSequence;
    private boolean beginningDelivered;
    private boolean captureFinished;
    private boolean qualityExecutionAllowed;
    private boolean closed;

    LocalSpeechCoreV2Session(
            Context context,
            DictationRequest request,
            long generation,
            AudioCapture audioCapture,
            LocalRealtimeRecognitionClient streamingClient,
            LocalOfflineRecognitionClient qualityClient,
            LocalPunctuationRecognitionClient punctuationClient,
            ExecutorService qualityExecutor,
            ExecutorService punctuationExecutor,
            VoiceDraftJournal journal,
            Observer observer) {
        this.context = context.getApplicationContext();
        this.request = Objects.requireNonNull(request, "request");
        if (generation <= 0L) throw new IllegalArgumentException("generation must be positive");
        this.generation = generation;
        this.audioCapture = Objects.requireNonNull(audioCapture, "audioCapture");
        this.streamingClient = Objects.requireNonNull(streamingClient, "streamingClient");
        this.qualityClient = Objects.requireNonNull(qualityClient, "qualityClient");
        this.punctuationClient = Objects.requireNonNull(
                punctuationClient, "punctuationClient");
        this.qualityExecutor = Objects.requireNonNull(qualityExecutor, "qualityExecutor");
        this.punctuationExecutor = Objects.requireNonNull(
                punctuationExecutor, "punctuationExecutor");
        this.observer = Objects.requireNonNull(observer, "observer");
        projectionMode = chooseProjectionMode(request);

        SessionId sessionId = SessionId.of("local-v2-" + generation + "-"
                + java.util.UUID.randomUUID().toString().replace("-", ""));
        token = new SpeechSessionToken(sessionId, generation);
        boolean streamingInstalled = OfflineStreamingRecognizer.isInstalled(this.context);
        boolean qualityInstalled = LocalOfflineRecognizer.isInstalled(this.context);
        boolean punctuationInstalled = LocalPunctuationRecognizer.isInstalled(this.context);
        com.opentypeless.android.speech.runtime.RuntimeResources runtimeResources =
                AndroidRuntimeResources.snapshot(this.context, punctuationInstalled);
        runtimeStrategy = LocalRuntimeSelector.select(
                new RuntimeCapabilities(streamingInstalled, qualityInstalled, true),
                runtimeResources,
                RuntimePolicy.DEFAULT);
        punctuationExecutionAllowed = punctuationInstalled
                && !runtimeResources.lowMemorySignal()
                && runtimeResources.availableMemoryMiB()
                        >= RuntimePolicy.DEFAULT.minimumSequentialAvailableMiB()
                && (runtimeResources.thermalLevel()
                                == com.opentypeless.android.speech.runtime.ThermalLevel.UNKNOWN
                        || !runtimeResources.thermalLevel().atLeast(
                                RuntimePolicy.DEFAULT.disableQualityAtThermal()));
        EngineDescriptor streaming = new EngineDescriptor(
                OfflineStreamingModelSpec.REALTIME.id(),
                OfflineStreamingModelSpec.REALTIME.displayName(),
                OfflineStreamingModelSpec.REALTIME.revision(),
                ProcessingLocation.ON_DEVICE,
                EngineCapabilities.of(
                        EngineCapability.LIVE_REVISIONS,
                        EngineCapability.ON_DEVICE));
        EngineDescriptor quality = qualityInstalled
                ? new EngineDescriptor(
                        OfflineModelSpec.QUALITY.id(),
                        OfflineModelSpec.QUALITY.displayName(),
                        OfflineModelSpec.QUALITY.revision(),
                        ProcessingLocation.ON_DEVICE,
                        punctuationExecutionAllowed
                                ? EngineCapabilities.of(
                                        EngineCapability.SEGMENT_FINALS,
                                        EngineCapability.AUTOMATIC_PUNCTUATION,
                                        EngineCapability.ON_DEVICE)
                                : EngineCapabilities.of(
                                        EngineCapability.SEGMENT_FINALS,
                                        EngineCapability.ON_DEVICE))
                : null;
        PersonalizationSnapshot personalization = request.settings().personalizationEnabled()
                ? request.personalization()
                : PersonalizationSnapshot.empty();
        coordinator = new SpeechCoreCoordinator(
                token,
                streaming,
                quality,
                runtimeStrategy,
                personalization,
                punctuationPolicy(request));
        qualityExecutionAllowed = runtimeStrategy.strategy()
                == RuntimeStrategy.CONCURRENT_TWO_PASS;
        this.journal = new JournalQueue(
                Objects.requireNonNull(journal, "journal"),
                new JournalSessionMetadata(
                        sessionId,
                        generation,
                        System.currentTimeMillis(),
                        streaming.engineId(),
                        streaming.modelRevision(),
                        request.settings().language(),
                        AudioCapture.SAMPLE_RATE));
    }

    Result execute() {
        CoordinatorUpdate prepared = coordinator.prepare(token);
        acceptCoordinatorUpdate(prepared, false);
        schedulePunctuationPrewarm();
        try {
            requireCurrent();
            streamingSession = streamingClient.start(this::onStreamingHypothesis);
            StreamingAudioResult audio = audioCapture.stream(
                    requireCaptureSession(),
                    request.settings().boundedMaxRecordingSeconds(),
                    new AudioCapture.CaptureListener() {
                        @Override
                        public void onReady() {
                            CoordinatorUpdate ready = coordinator.ready(token);
                            acceptCoordinatorUpdate(ready, false);
                            if (observer.isCurrent()) observer.onReadyForSpeech();
                        }

                        @Override
                        public void onBeginningOfSpeech() {
                            if (observer.isCurrent()) observer.onBeginningOfSpeech();
                        }
                    },
                    this::onPcmFrame);
            requireCurrent();

            LocalRealtimeRecognitionClient.Session stream = streamingSession;
            String streamingFinal = stream == null ? "" : stream.finish();
            if (!streamingFinal.isBlank()) onStreamingHypothesis(streamingFinal);

            SegmentAudioUpdate finished;
            synchronized (lock) {
                finished = assembler.finish();
                captureFinished = true;
            }
            acceptAudioUpdate(finished);
            ensureFallbackSegment(streamingFinal);

            if (runtimeStrategy.strategy() == RuntimeStrategy.SEQUENTIAL_TWO_PASS) {
                // The low-memory route explicitly unloads the online weights before starting the
                // quality process. It remains v2, but does not make both model heaps resident.
                streamingClient.releaseWarmModel();
                synchronized (lock) {
                    qualityExecutionAllowed = true;
                }
                startDeferredQuality();
            }
            awaitQualityDeadline();

            TerminalReason terminalReason = audio.reachedLimit()
                    ? TerminalReason.DURATION_LIMIT
                    : TerminalReason.USER_FINISH;
            CoordinatorUpdate ended = coordinator.captureEnded(token, terminalReason);
            acceptCoordinatorUpdate(ended, true);
            journal.flush(JOURNAL_FLUSH_TIMEOUT_MS);

            VoiceDraft draft = coordinator.draft();
            String rendered = draft.renderedText().trim();
            if (rendered.isBlank()) {
                throw new IllegalStateException("Offline recognition returned no text");
            }
            String raw = renderRaw(draft).trim();
            if (raw.isBlank()) raw = rendered;
            String recoveryId = journal.durable()
                    ? recoveryId(journal.token())
                    : "";
            return new Result(
                    raw,
                    rendered,
                    audio.durationMs(),
                    audio.reachedLimit(),
                    audio.autoStopped(),
                    runtimeStrategy,
                    draft.segments().size(),
                    recoveryId);
        } catch (CancellationException error) {
            if (explicitlyDiscarded.get()) discardCoordinatorAndJournal();
            throw error;
        } catch (RuntimeException error) {
            CoordinatorUpdate failed = coordinator.captureFailed(
                    token, TerminalReason.ENGINE_FAILURE);
            acceptCoordinatorUpdate(failed, true);
            journal.flush(JOURNAL_FLUSH_TIMEOUT_MS);
            throw error;
        } finally {
            LocalRealtimeRecognitionClient.Session stream = streamingSession;
            streamingSession = null;
            if (stream != null) stream.close();
        }
    }

    void cancel(boolean explicitDiscard) {
        if (!cancelled.compareAndSet(false, true)) return;
        explicitlyDiscarded.set(explicitDiscard);
        LocalRealtimeRecognitionClient.Session stream = streamingSession;
        if (stream != null) stream.cancel();
        qualityClient.cancelActive();
        punctuationClient.cancelActive();
        synchronized (lock) {
            for (Future<?> future : qualityFutures.values()) future.cancel(true);
            for (Future<?> future : punctuationFutures) future.cancel(true);
            punctuationFutures.clear();
            for (SegmentPayload payload : payloadBySegment.values()) payload.zeroize();
            payloadBySegment.clear();
            if (!assembler.terminal()) {
                if (explicitDiscard) assembler.discard();
                else assembler.close();
            }
        }
        if (explicitDiscard) {
            // Queue the authenticated tombstone without blocking the IME thread on Keystore/disk.
            // The capture worker flushes the same idempotent discard before it exits.
            try {
                journal.sync(coordinator.explicitDiscard(token));
            } catch (RuntimeException ignored) {
                // The existing journal remains recoverable rather than being cleared unsafely.
            }
        }
    }

    RuntimeStrategyDecision runtimeStrategy() {
        return runtimeStrategy;
    }

    /**
     * Sealed-prefix delivery is used only when the terminal pipeline cannot globally rewrite it.
     * Smart/translate with a configured LLM keeps the complete document composing so the final
     * integrity-checked candidate can replace it atomically.
     */
    static ProjectionMode chooseProjectionMode(DictationRequest request) {
        if (request.captureMode() != DictationRequest.CaptureMode.CONTINUOUS) {
            return ProjectionMode.SHORT_DICTATION;
        }
        ProcessingMode resolved = InputPolicy.resolve(
                request.requestedMode(), request.inputContext());
        boolean globalRewrite = (resolved == ProcessingMode.SMART
                || resolved == ProcessingMode.TRANSLATE)
                && request.settings().polishEnabled()
                && !request.settings().llmBaseUrl().trim().isEmpty()
                && !request.settings().llmModel().trim().isEmpty();
        return globalRewrite
                ? ProjectionMode.SHORT_DICTATION
                : ProjectionMode.LONG_DICTATION;
    }

    private AudioCapture.Session requireCaptureSession() {
        // Local v2 is entered only from VoicePipelineRuntime's captured-audio route.
        // The session is supplied through the request-scoped holder immediately before execute.
        AudioCapture.Session session = captureSession;
        if (session == null) throw new IllegalStateException("Capture session is unavailable");
        return session;
    }

    private volatile AudioCapture.Session captureSession;

    void setCaptureSession(AudioCapture.Session session) {
        if (captureSession != null) throw new IllegalStateException("Capture session already set");
        captureSession = Objects.requireNonNull(session, "session");
    }

    private void onPcmFrame(byte[] bytes, int offset, int length) {
        requireCurrent();
        int safeLength = Math.min(length, bytes.length - offset) & ~1;
        if (safeLength <= 0) return;
        boolean speech = frameVad.classify(bytes, offset, safeLength);
        if (speech && !beginningDelivered) {
            beginningDelivered = true;
            if (observer.isCurrent()) observer.onBeginningOfSpeech();
        }
        short[] samples = pcm16(bytes, offset, safeLength);
        SegmentAudioUpdate update;
        synchronized (lock) {
            update = assembler.accept(new Pcm16Chunk(sampleCursor, samples), speech);
            sampleCursor += samples.length;
        }
        Arrays.fill(samples, (short) 0);
        acceptAudioUpdate(update);
        LocalRealtimeRecognitionClient.Session stream = streamingSession;
        if (stream != null) stream.accept(bytes, safeLength);
    }

    private void acceptAudioUpdate(SegmentAudioUpdate update) {
        if (update == null) return;
        synchronized (lock) {
            for (SegmentAudio audio : update.closedSegments()) {
                short[] samples = audio.samples();
                byte[] wav = WavEncoder.pcm16Mono(samples, audio.sampleRate());
                Arrays.fill(samples, (short) 0);
                SegmentAudio journalCopy = new SegmentAudio(
                        audio.segmentId(),
                        audio.audioStartSample(),
                        audio.audioEndSample(),
                        audio.boundarySample(),
                        audio.sampleRate(),
                        audio.reason(),
                        audio.samples());
                SegmentPayload previous = payloadBySegment.put(
                        audio.segmentId(), new SegmentPayload(wav, journalCopy));
                if (previous != null) previous.zeroize();
                audio.zeroize();
                journal.syncAudio(journalCopy);
            }
        }
        for (BoundarySignal signal : update.boundarySignals()) acceptBoundary(signal);
    }

    private void acceptBoundary(BoundarySignal signal) {
        if (signal instanceof BoundarySignal.SegmentOpened opened) {
            synchronized (lock) {
                activeSegmentId = opened.segmentId();
                ensureOpenSegment(opened.segmentId(), slicer.activeSegmentText());
            }
            return;
        }
        if (signal instanceof BoundarySignal.SoftBoundary soft) {
            CoordinatorUpdate update;
            String source;
            synchronized (lock) {
                source = slicer.activeSegmentText().trim();
                ensureOpenSegment(soft.segmentId(), source);
                update = coordinator.softBoundary(token, soft.segmentId(), null);
            }
            acceptCoordinatorUpdate(update, false);
            scheduleProvisionalPunctuation(soft.segmentId(), source);
            return;
        }
        if (signal instanceof BoundarySignal.SegmentReopened reopened) {
            CoordinatorUpdate update;
            String current;
            synchronized (lock) {
                update = coordinator.reopenSegment(token, reopened.segmentId());
                current = slicer.activeSegmentText();
            }
            acceptCoordinatorUpdate(update, false);
            if (!current.isBlank()) publishLiveRevision(reopened.segmentId(), current);
            return;
        }
        if (signal instanceof BoundarySignal.HardBoundary hard) {
            CoordinatorUpdate update;
            synchronized (lock) {
                ensureOpenSegment(hard.segmentId(), slicer.activeSegmentText());
                update = coordinator.hardBoundary(token, hard.segmentId());
                activeSegmentId = 0L;
                slicer.sealAtCurrentHypothesis();
            }
            acceptCoordinatorUpdate(update, false);
        }
    }

    private void onStreamingHypothesis(String fullText) {
        if (!observer.isCurrent() || cancelled.get()) return;
        StreamingHypothesisSlicer.Slice slice;
        long segmentId;
        synchronized (lock) {
            slice = slicer.accept(fullText);
            segmentId = activeSegmentId;
            if (segmentId == 0L || slice.segmentText().isBlank()) return;
            ensureOpenSegment(segmentId, slice.segmentText());
        }
        publishLiveRevision(segmentId, slice.segmentText());
    }

    private void publishLiveRevision(long segmentId, String text) {
        if (text == null || text.isBlank() || cancelled.get()) return;
        CoordinatorUpdate update;
        synchronized (lock) {
            rawBySegment.put(segmentId, text.trim());
            update = coordinator.liveRevision(StreamingRevisionInput.text(
                    token, segmentId, ++providerSequence, text.trim()));
        }
        acceptCoordinatorUpdate(update, false);
    }

    private void ensureOpenSegment(long segmentId, String firstText) {
        if (coordinator.draft().segment(segmentId).isPresent()) return;
        SegmentJoin join = SegmentJoinPolicy.choose(
                coordinator.draft().renderedText(),
                firstText,
                request.settings().language());
        CoordinatorUpdate opened = coordinator.openSegment(token, segmentId, join);
        acceptCoordinatorUpdate(opened, false);
    }

    private void ensureFallbackSegment(String streamingFinal) {
        synchronized (lock) {
            if (!coordinator.draft().segments().isEmpty()) return;
            String text = streamingFinal == null || streamingFinal.isBlank()
                    ? slicer.latestProviderFull()
                    : streamingFinal.trim();
            if (text.isBlank()) return;
            long segmentId = 1L;
            ensureOpenSegment(segmentId, text);
            publishLiveRevision(segmentId, text);
            CoordinatorUpdate hard = coordinator.hardBoundary(token, segmentId);
            acceptCoordinatorUpdate(hard, false);
        }
    }

    private void acceptCoordinatorUpdate(CoordinatorUpdate update, boolean terminalPreview) {
        if (update == null) return;
        journal.sync(update);
        if (update.disposition() == CoordinatorDisposition.REJECTED_SESSION
                || update.disposition() == CoordinatorDisposition.REJECTED_BOUNDS) {
            return;
        }
        if (!update.qualityJobsToStart().isEmpty()) {
            for (QualityJobToken job : update.qualityJobsToStart()) scheduleQuality(job);
        }
        if (!update.projectionChanged() && !terminalPreview) return;
        VoiceDraft draft = update.draft();
        String text = draft.renderedText();
        if (text.isBlank()) return;
        ProjectionDocument document = VoiceDraftProjectionPlanner.plan(draft, projectionMode);
        if (observer.isCurrent()) observer.onDocument(document, text, terminalPreview);
    }

    private void scheduleQuality(QualityJobToken job) {
        synchronized (lock) {
            if (terminalQuality.contains(job) || qualityFutures.containsKey(job)) return;
            if (runtimeStrategy.strategy() == RuntimeStrategy.SEQUENTIAL_TWO_PASS
                    && !qualityExecutionAllowed) {
                deferredQuality.add(job);
                return;
            }
            SegmentPayload payload = payloadBySegment.get(job.segmentId());
            if (payload == null) {
                terminalQuality.add(job);
                CoordinatorUpdate fallback = coordinator.qualityFailed(job);
                acceptCoordinatorUpdate(fallback, false);
                return;
            }
            try {
                Future<?> future = qualityExecutor.submit(() -> runQuality(job));
                qualityFutures.put(job, future);
            } catch (RejectedExecutionException error) {
                terminalQuality.add(job);
                CoordinatorUpdate fallback = coordinator.qualityFailed(job);
                acceptCoordinatorUpdate(fallback, false);
            }
        }
    }

    private void startDeferredQuality() {
        List<QualityJobToken> queued;
        synchronized (lock) {
            queued = List.copyOf(deferredQuality);
            deferredQuality.clear();
        }
        for (QualityJobToken job : queued) scheduleQuality(job);
    }

    private void runQuality(QualityJobToken job) {
        SegmentPayload payload;
        synchronized (lock) {
            payload = payloadBySegment.get(job.segmentId());
        }
        if (payload == null || cancelled.get()) return;
        try {
            boolean formatted = true;
            LocalOfflineRecognitionClient.Result result = qualityClient.recognize(
                    payload.wav(), request.settings().language(), formatted);
            if (cancelled.get()) return;
            String qualityText = result.exactText();
            String punctuationCandidate = punctuationCandidate(qualityText);
            synchronized (lock) {
                rawBySegment.put(job.segmentId(), qualityText);
                terminalQuality.add(job);
            }
            CoordinatorUpdate accepted = coordinator.qualitySucceeded(
                    job, qualityText, punctuationCandidate, null);
            acceptCoordinatorUpdate(accepted, false);
        } catch (CancellationException error) {
            if (!cancelled.get()) failQuality(job, true);
        } catch (RuntimeException error) {
            failQuality(job, false);
        } finally {
            synchronized (lock) {
                qualityFutures.remove(job);
                SegmentPayload removed = payloadBySegment.remove(job.segmentId());
                if (removed != null) removed.zeroize();
            }
        }
    }

    private void failQuality(QualityJobToken job, boolean timedOut) {
        synchronized (lock) {
            if (!terminalQuality.add(job)) return;
        }
        CoordinatorUpdate fallback = timedOut
                ? coordinator.qualityTimedOut(job)
                : coordinator.qualityFailed(job);
        acceptCoordinatorUpdate(fallback, false);
    }

    private void scheduleProvisionalPunctuation(long segmentId, String sourceText) {
        if (sourceText == null
                || sourceText.isBlank()
                || !punctuationEnabled()
                || cancelled.get()) {
            return;
        }
        try {
            Future<?> future = punctuationExecutor.submit(() -> {
                try {
                    String candidate = punctuationClient.punctuate(sourceText);
                    if (cancelled.get() || !observer.isCurrent()) return;
                    CoordinatorUpdate punctuation = coordinator.provisionalPunctuation(
                            token, segmentId, sourceText, candidate);
                    acceptCoordinatorUpdate(punctuation, false);
                } catch (CancellationException ignored) {
                    // Cancellation never removes the last accepted transcript revision.
                } catch (RuntimeException ignored) {
                    // The immediate terminal punctuation revision remains the safe fallback.
                }
            });
            synchronized (lock) {
                punctuationFutures.add(future);
            }
        } catch (RejectedExecutionException ignored) {
            // The immediate terminal punctuation revision remains the safe fallback.
        }
    }

    private void schedulePunctuationPrewarm() {
        if (!punctuationEnabled() || cancelled.get()) return;
        try {
            Future<?> future = punctuationExecutor.submit(() -> {
                try {
                    punctuationClient.prewarm();
                } catch (RuntimeException ignored) {
                    // The first real punctuation request retries loading; prewarm is not a gate.
                }
            });
            synchronized (lock) {
                punctuationFutures.add(future);
            }
        } catch (RejectedExecutionException ignored) {
            // The first real punctuation request can still load synchronously.
        }
    }

    private String punctuationCandidate(String sourceText) {
        if (!punctuationEnabled()) return null;
        try {
            return punctuationClient.punctuate(sourceText);
        } catch (CancellationException error) {
            throw error;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private boolean punctuationEnabled() {
        return punctuationExecutionAllowed
                && com.opentypeless.android.offline.SafePunctuationRestorer.prefersPunctuation(
                        request.inputContext().fieldKind());
    }

    private static SegmentTransformPolicy punctuationPolicy(DictationRequest request) {
        boolean enabled = com.opentypeless.android.offline.SafePunctuationRestorer.prefersPunctuation(
                request.inputContext().fieldKind());
        return new SegmentTransformPolicy(enabled, enabled, false, true);
    }

    private void awaitQualityDeadline() {
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(runtimeStrategy.qualityDeadlineMs());
        while (runtimeStrategy.qualityDeadlineMs() > 0L) {
            List<Map.Entry<QualityJobToken, Future<?>>> pending;
            synchronized (lock) {
                pending = new ArrayList<>();
                for (Map.Entry<QualityJobToken, Future<?>> entry : qualityFutures.entrySet()) {
                    if (!entry.getValue().isDone()) pending.add(entry);
                }
            }
            if (pending.isEmpty()) return;
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) break;
            Map.Entry<QualityJobToken, Future<?>> first = pending.get(0);
            try {
                first.getValue().get(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new CancellationException("Local quality wait interrupted");
            } catch (ExecutionException ignored) {
                // Worker translates its failure into a streaming fallback.
            } catch (TimeoutException ignored) {
                break;
            }
        }
        List<Map.Entry<QualityJobToken, Future<?>>> expired;
        synchronized (lock) {
            expired = new ArrayList<>(qualityFutures.entrySet());
        }
        if (!expired.isEmpty()) qualityClient.cancelActive();
        for (Map.Entry<QualityJobToken, Future<?>> entry : expired) {
            if (entry.getValue().isDone()) continue;
            entry.getValue().cancel(true);
            failQuality(entry.getKey(), true);
        }
    }

    private String renderRaw(VoiceDraft draft) {
        StringBuilder rendered = new StringBuilder();
        for (VoiceSegment segment : draft.segments()) {
            String raw = rawBySegment.get(segment.segmentId());
            if (raw == null || raw.isBlank()) raw = segment.visibleText();
            if (raw == null || raw.isBlank()) continue;
            if (rendered.length() > 0) rendered.append(segment.joinBefore().delimiter());
            rendered.append(raw);
        }
        return rendered.toString();
    }

    private void discardCoordinatorAndJournal() {
        try {
            CoordinatorUpdate discarded = coordinator.explicitDiscard(token);
            journal.sync(discarded);
            journal.flush(JOURNAL_FLUSH_TIMEOUT_MS);
        } catch (RuntimeException ignored) {
            // The authenticated journal retains its previous safe state if tombstoning fails.
        }
    }

    private void requireCurrent() {
        if (cancelled.get() || !observer.isCurrent() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Speech Core v2 session is no longer current");
        }
    }

    private static short[] pcm16(byte[] bytes, int offset, int length) {
        short[] samples = new short[length / 2];
        for (int index = 0; index < samples.length; index++) {
            int source = offset + index * 2;
            samples[index] = (short) ((bytes[source] & 0xff) | (bytes[source + 1] << 8));
        }
        return samples;
    }

    static String recoveryId(JournalToken token) {
        return "v2:" + token.generation() + ":" + token.sessionId().value();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        LocalRealtimeRecognitionClient.Session stream = streamingSession;
        if (stream != null) stream.close();
        synchronized (lock) {
            for (Future<?> future : punctuationFutures) future.cancel(true);
            punctuationFutures.clear();
            for (SegmentPayload payload : payloadBySegment.values()) payload.zeroize();
            payloadBySegment.clear();
        }
        punctuationClient.releaseSessionWorker();
        assembler.close();
        journal.close();
    }

    /** Serialized, non-UI journal adapter. */
    private final class JournalQueue implements AutoCloseable {
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final Future<SpeechCoreJournalWriter> initialized;
        private volatile SpeechCoreJournalWriter writer;
        private volatile JournalToken token;
        private volatile boolean durable;

        JournalQueue(VoiceDraftJournal journal, JournalSessionMetadata metadata) {
            initialized = executor.submit(() -> {
                SpeechCoreJournalWriter created = new SpeechCoreJournalWriter(journal, metadata);
                writer = created;
                token = created.token();
                durable = true;
                observer.onJournalCreated(token);
                return created;
            });
        }

        void sync(CoordinatorUpdate update) {
            if (update == null) return;
            submit(() -> requireWriter().sync(update));
        }

        void syncAudio(SegmentAudio audio) {
            submit(() -> {
                try {
                    requireWriter().syncAudio(audio);
                } finally {
                    audio.zeroize();
                }
            });
        }

        void flush(long timeoutMs) {
            try {
                Future<?> barrier = executor.submit(() -> {});
                barrier.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException | RejectedExecutionException ignored) {
                durable = false;
            }
        }

        boolean durable() {
            return durable;
        }

        JournalToken token() {
            return token;
        }

        private SpeechCoreJournalWriter requireWriter() {
            try {
                return initialized.get();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new CancellationException("Speech journal initialization interrupted");
            } catch (ExecutionException error) {
                durable = false;
                throw new IllegalStateException("Speech journal is unavailable", error.getCause());
            }
        }

        private void submit(Runnable task) {
            try {
                executor.submit(() -> {
                    try {
                        task.run();
                    } catch (RuntimeException error) {
                        durable = false;
                    }
                });
            } catch (RejectedExecutionException ignored) {
                durable = false;
            }
        }

        @Override
        public void close() {
            submit(() -> {
                SpeechCoreJournalWriter current = writer;
                if (current != null) current.close();
            });
            executor.shutdown();
        }
    }
}
