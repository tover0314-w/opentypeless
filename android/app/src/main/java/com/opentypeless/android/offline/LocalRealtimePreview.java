package com.opentypeless.android.offline;

import android.content.Context;

import com.opentypeless.android.audio.AudioCapture;
import com.opentypeless.android.audio.WavEncoder;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
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
    interface Decoder extends AutoCloseable {
        String decode(byte[] wav);

        @Override
        default void close() {}
    }

    public interface Listener {
        void onPartial(String text);
    }

    public static final int INITIAL_PCM_BYTES = AudioCapture.SAMPLE_RATE * 2 * 3 / 4;
    public static final int STEP_PCM_BYTES = AudioCapture.SAMPLE_RATE * 2 * 3 / 4;
    public static final int MAX_PCM_BYTES = AudioCapture.SAMPLE_RATE * 2 * 30;

    private Decoder decoder;
    private Listener listener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final byte[] pcm = new byte[MAX_PCM_BYTES];
    private int size;
    private int lastScheduledSize;
    private boolean decoding;
    private boolean closed;

    public LocalRealtimePreview(
            LocalOfflineRecognizer.Session session,
            Listener listener) {
        this(Objects.requireNonNull(session, "session")::transcribeWithPunctuation, listener);
    }

    /** Creates a lazy, worker-owned SenseVoice session without model work on the caller thread. */
    public LocalRealtimePreview(
            Context context,
            String configuredLanguage,
            Listener listener) {
        this(new LazySessionDecoder(context, configuredLanguage), listener);
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
            Decoder currentDecoder;
            synchronized (this) {
                if (closed || decoder == null) return;
                currentDecoder = decoder;
            }
            byte[] wav = WavEncoder.pcm16Mono(snapshot, AudioCapture.SAMPLE_RATE);
            String text;
            try {
                text = currentDecoder.decode(wav);
            } finally {
                Arrays.fill(wav, (byte) 0);
            }
            Listener currentListener;
            synchronized (this) {
                currentListener = closed ? null : listener;
            }
            if (currentListener != null && text != null && !text.isBlank()) {
                currentListener.onPartial(text.trim());
            }
        } catch (RuntimeException ignored) {
            // Prefixes can be too short or contain only noise. Final recognition remains decisive.
        } finally {
            Arrays.fill(snapshot, (byte) 0);
            boolean release;
            synchronized (this) {
                decoding = false;
                if (!closed) scheduleIfReady();
                release = closed;
            }
            if (release) releaseReferences();
        }
    }

    /** Revokes preview work without waiting for a running decode on the caller thread. */
    public void cancel() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            clearPcmLocked();
        }
        List<Runnable> queued = executor.shutdownNow();
        boolean release;
        synchronized (this) {
            if (!queued.isEmpty()) decoding = false;
            release = !decoding;
        }
        if (release) releaseReferences();
    }

    /** Stops new previews and waits for the current decode before the authoritative final pass. */
    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            clearPcmLocked();
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
        } finally {
            releaseReferences();
        }
    }

    @Override
    public synchronized String toString() {
        return "LocalRealtimePreview{bufferedBytes=" + size
                + ", decoding=" + decoding
                + ", closed=" + closed
                + ", content=<redacted>}";
    }

    private void clearPcmLocked() {
        Arrays.fill(pcm, (byte) 0);
        size = 0;
        lastScheduledSize = 0;
    }

    private void releaseReferences() {
        Decoder release;
        synchronized (this) {
            release = decoder;
            decoder = null;
            listener = null;
        }
        if (release != null) {
            try {
                release.close();
            } catch (RuntimeException ignored) {
                // Preview authority is already revoked; native details remain private.
            }
        }
    }

    private static final class LazySessionDecoder implements Decoder {
        private final Context context;
        private final String configuredLanguage;
        private LocalOfflineRecognizer.Session session;

        private LazySessionDecoder(Context context, String configuredLanguage) {
            Context safe = Objects.requireNonNull(context, "context");
            Context application = safe.getApplicationContext();
            this.context = application == null ? safe : application;
            this.configuredLanguage = configuredLanguage == null ? "" : configuredLanguage;
        }

        @Override
        public String decode(byte[] wav) {
            if (session == null) {
                session = LocalOfflineRecognizer.openSession(context, configuredLanguage);
            }
            return session.transcribeWithPunctuation(wav);
        }

        @Override
        public void close() {
            LocalOfflineRecognizer.Session current = session;
            session = null;
            if (current != null) current.close();
        }
    }
}
