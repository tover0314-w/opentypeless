package com.opentypeless.android.speech.audio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Couples a continuous bounded PCM ring to the pure endpoint detector.
 *
 * <p>The assembler never owns or stops {@code AudioRecord}. A hard boundary returns a copy of only
 * the closed segment while the ring remains alive for overlap/pre-roll into the following segment.
 * All calls are serialized by the capture worker.
 */
public final class ContinuousSegmentAssembler implements AutoCloseable {
    private static final int CAPACITY_MARGIN_MS = 1_000;

    private final int sampleRate;
    private final SegmentBoundaryDetector detector;
    private final BoundedPcmRingBuffer ring;
    private boolean terminal;
    private boolean discarded;

    public ContinuousSegmentAssembler(int sampleRate, EndpointPolicy policy) {
        if (sampleRate <= 0) throw new IllegalArgumentException("sample rate must be positive");
        EndpointPolicy safePolicy = Objects.requireNonNull(policy, "policy");
        this.sampleRate = sampleRate;
        detector = new SegmentBoundaryDetector(sampleRate, safePolicy);
        long capacityMs = Math.addExact(
                Math.addExact(
                        (long) safePolicy.maximumSegmentMs(),
                        safePolicy.hardSilenceMs()),
                Math.addExact(
                        (long) safePolicy.preRollMs() + safePolicy.overlapMs(),
                        CAPACITY_MARGIN_MS));
        if (capacityMs > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("endpoint policy requires an unsupported PCM ring");
        }
        ring = new BoundedPcmRingBuffer(sampleRate, (int) capacityMs);
    }

    public synchronized SegmentAudioUpdate accept(Pcm16Chunk chunk, boolean speech) {
        requireActive();
        Objects.requireNonNull(chunk, "chunk");
        ring.append(chunk);
        List<BoundarySignal> signals = detector.accept(
                new VadFrame(chunk.startSample(), chunk.rawSamples().length, speech));
        return materialize(signals);
    }

    /** Flushes a final open segment. It is a normal, recoverable finish and never discard. */
    public synchronized SegmentAudioUpdate finish() {
        requireActive();
        terminal = true;
        return materialize(detector.finish(ring.endSample()));
    }

    /** Explicit user discard. No segment audio is returned and retained PCM is immediately wiped. */
    public synchronized void discard() {
        if (terminal) return;
        detector.discard();
        discarded = true;
        terminal = true;
        ring.close();
    }

    public synchronized long capturedEndSample() {
        return ring.endSample();
    }

    public synchronized boolean terminal() {
        return terminal;
    }

    public synchronized boolean discarded() {
        return discarded;
    }

    @Override
    public synchronized void close() {
        if (!terminal) {
            detector.discard();
            terminal = true;
            discarded = true;
        }
        ring.close();
    }

    private SegmentAudioUpdate materialize(List<BoundarySignal> signals) {
        if (signals.isEmpty()) return SegmentAudioUpdate.EMPTY;
        ArrayList<SegmentAudio> closed = new ArrayList<>();
        try {
            for (BoundarySignal signal : signals) {
                if (!(signal instanceof BoundarySignal.HardBoundary boundary)) continue;
                short[] samples = ring.slice(boundary.audioStartSample(), boundary.audioEndSample());
                closed.add(new SegmentAudio(
                        boundary.segmentId(),
                        boundary.audioStartSample(),
                        boundary.audioEndSample(),
                        boundary.boundarySample(),
                        sampleRate,
                        boundary.reason(),
                        samples));
                Arrays.fill(samples, (short) 0);
            }
            return new SegmentAudioUpdate(signals, closed);
        } catch (RuntimeException error) {
            for (SegmentAudio audio : closed) audio.zeroize();
            throw error;
        }
    }

    private void requireActive() {
        if (terminal) throw new IllegalStateException("segment assembler is terminal");
    }
}
