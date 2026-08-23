package com.opentypeless.android.offline;

import android.app.Service;
import android.content.ComponentCallbacks2;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Isolated, warm streaming-ASR worker.
 *
 * <p>The online Paraformer weights never share an address space with SenseVoice. A bound client
 * keeps this process warm across voice turns; only its light per-turn stream is released. Android
 * memory pressure and service teardown remain deterministic model-release boundaries.
 */
public final class LocalStreamingRecognitionService extends Service {
    private static final long NO_SESSION = -1L;
    private static final long CANCELLING = -2L;
    private static final int MAX_PCM_BYTES = 18_000_000;

    private final AtomicLong activeSession = new AtomicLong(NO_SESSION);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ILocalOfflineRecognitionService.Stub binder =
            new ILocalOfflineRecognitionService.Stub() {
                @Override
                public void prewarmRealtime() {
                    enforceSameUid();
                    if (activeSession.get() != NO_SESSION) return;
                    OfflineStreamingRecognizer.prewarm(LocalStreamingRecognitionService.this);
                }

                @Override
                public void releaseRealtimeModel() {
                    enforceSameUid();
                    if (activeSession.get() == NO_SESSION) {
                        OfflineStreamingRecognizer.releaseShared();
                    }
                }

                @Override
                public void startRealtime(
                        long sessionId,
                        ParcelFileDescriptor pcm16,
                        ILocalRealtimeRecognitionCallback callback) {
                    enforceSameUid();
                    if (sessionId <= 0L || pcm16 == null || callback == null) {
                        closeQuietly(pcm16);
                        throw new IllegalArgumentException("Invalid live preview request");
                    }
                    if (!activeSession.compareAndSet(NO_SESSION, sessionId)) {
                        closeQuietly(pcm16);
                        throw new IllegalStateException("Streaming recognizer is busy");
                    }
                    try {
                        worker.execute(() -> runRealtime(sessionId, pcm16, callback));
                    } catch (RuntimeException error) {
                        activeSession.compareAndSet(sessionId, NO_SESSION);
                        closeQuietly(pcm16);
                        throw error;
                    }
                }

                @Override
                public Bundle transcribe(
                        long sessionId,
                        ParcelFileDescriptor wav,
                        String language,
                        boolean useInverseTextNormalization) {
                    enforceSameUid();
                    closeQuietly(wav);
                    throw new UnsupportedOperationException(
                            "Quality recognition belongs to the isolated quality service");
                }

                @Override
                public void cancel(long sessionId) {
                    enforceSameUid();
                    if (sessionId > 0L) activeSession.compareAndSet(sessionId, CANCELLING);
                    // Closing the client's pipe unblocks the reader. Unlike SenseVoice, online
                    // decoding is cooperative, so the warm process does not need to be killed.
                }

                @Override
                public int servicePid() {
                    enforceSameUid();
                    return Process.myPid();
                }
            };

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
                && activeSession.get() == NO_SESSION) {
            OfflineStreamingRecognizer.releaseShared();
        }
    }

    @Override
    public void onDestroy() {
        worker.shutdownNow();
        OfflineStreamingRecognizer.releaseShared();
        super.onDestroy();
    }

    private void runRealtime(
            long sessionId,
            ParcelFileDescriptor pcm16,
            ILocalRealtimeRecognitionCallback callback) {
        String finalText = "";
        Exception failure = null;
        boolean deliverTerminal;
        try (FileInputStream input = new FileInputStream(pcm16.getFileDescriptor());
             OfflineStreamingRecognizer.Session session =
                     OfflineStreamingRecognizer.openSession(this)) {
            requireActive(sessionId);
            callback.onReady(sessionId);
            byte[] buffer = new byte[16_000 * 2 * 40 / 1_000];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                requireActive(sessionId);
                if (read == 0) continue;
                if (total > MAX_PCM_BYTES - read) {
                    throw new IllegalStateException("Live preview audio exceeded the limit");
                }
                total += read;
                String partial = session.acceptPcm16(buffer, read);
                if (!partial.isEmpty()) callback.onPartial(sessionId, partial);
            }
            requireActive(sessionId);
            finalText = session.finish();
        } catch (Exception error) {
            failure = error;
        } finally {
            closeQuietly(pcm16);
            deliverTerminal = activeSession.compareAndSet(sessionId, NO_SESSION);
            if (!deliverTerminal) activeSession.compareAndSet(CANCELLING, NO_SESSION);
        }
        if (!deliverTerminal) return;
        try {
            if (failure == null) callback.onFinal(sessionId, finalText);
            else callback.onError(sessionId, safeMessage(failure));
        } catch (android.os.RemoteException ignored) {
            // The IME may already have detached during cancellation.
        }
    }

    private void enforceSameUid() {
        if (Binder.getCallingUid() != Process.myUid()) {
            throw new SecurityException("Offline recognition is private to OpenTypeless");
        }
    }

    private void requireActive(long sessionId) {
        if (activeSession.get() != sessionId) {
            throw new IllegalStateException("Streaming recognition was cancelled");
        }
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? "Live preview recognition failed"
                : message;
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor == null) return;
        try {
            descriptor.close();
        } catch (IOException ignored) {
            // Pipe peers and cancellation can race to close the descriptor.
        }
    }
}
