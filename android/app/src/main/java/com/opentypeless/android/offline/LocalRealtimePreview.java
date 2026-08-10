package com.opentypeless.android.offline;

import com.opentypeless.android.audio.AudioRecorder;
import com.opentypeless.android.audio.WavEncoder;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Produces replaceable prefix transcripts while the batch SenseVoice model is recording.
 *
 * <p>SenseVoice is not a native streaming model, so this class re-decodes the bounded utterance
 * prefix. Updates are coalesced on one worker, the prefix is capped at 30 seconds, and failures are
 * deliberately non-terminal: the complete recording still receives the authoritative final pass.
 */
public final class LocalRealtimePreview implements AutoCloseable {
    interface Decoder {
        String decode(byte[] wav);
    }

    public interface Listener {
        void onPartial(String text);
    }

    static final int INITIAL_PCM_BYTES = AudioRecorder.SAMPLE_RATE * 2 * 3 / 4;
    static final int STEP_PCM_BYTES = AudioRecorder.SAMPLE_RATE * 2 * 3 / 4;
    static final int MAX_PCM_BYTES = AudioRecorder.SAMPLE_RATE * 2 * 30;

    private final Decoder decoder;
    private final Listener listener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final byte[] pcm = new byte[MAX_PCM_BYTES];
    private int size;
    private int lastScheduledSize;
    private boolean decoding;
    private boolean closed;

    public LocalRealtimePreview(
            LocalOfflineRecognizer.Session session,
            Listener listener) {
        this(session::transcribeWithPunctuation, listener);
    }

    LocalRealtimePreview(Decoder decoder, Listener listener) {
        if (decoder == null) throw new IllegalArgumentException("Decoder is required");
        if (listener == null) throw new IllegalArgumentException("Listener is required");
        this.decoder = decoder;
        this.listener = listener;
    }

    /** Copies a PCM16 chunk and schedules at most one prefix decode at a time. */
    public synchronized void accept(byte[] data, int length) {
        if (closed || data == null || length <= 0 || size >= pcm.length) return;
        int safeLength = Math.min(length, data.length) & ~1;
        int accepted = Math.min(safeLength, pcm.length - size) & ~1;
        if (accepted <= 0) return;
        System.arraycopy(data, 0, pcm, size, accepted);
        size += accepted;
        scheduleIfReady();
    }

    private synchronized void scheduleIfReady() {
        if (closed || decoding || size < INITIAL_PCM_BYTES
                || size - lastScheduledSize < STEP_PCM_BYTES) {
            return;
        }
        byte[] snapshot = Arrays.copyOf(pcm, size);
        lastScheduledSize = size;
        decoding = true;
        executor.execute(() -> decode(snapshot));
    }

    private void decode(byte[] snapshot) {
        try {
            String text = decoder.decode(WavEncoder.pcm16Mono(
                    snapshot,
                    AudioRecorder.SAMPLE_RATE));
            if (text != null && !text.isBlank()) listener.onPartial(text.trim());
        } catch (RuntimeException ignored) {
            // Prefixes can be too short or contain only noise. Final recognition remains decisive.
        } finally {
            synchronized (this) {
                decoding = false;
                scheduleIfReady();
            }
        }
    }

    /** Stops new previews and waits for the current decode before the authoritative final pass. */
    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                executor.awaitTermination(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException error) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
