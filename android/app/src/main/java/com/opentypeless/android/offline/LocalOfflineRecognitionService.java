package com.opentypeless.android.offline;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/** Loads SenseVoice only inside the private {@code :local_quality} process. */
public final class LocalOfflineRecognitionService extends Service {
    public static final String RESULT_EXACT = "exact";
    public static final String RESULT_PUNCTUATED = "punctuated";
    public static final int MAX_WAV_BYTES = 18_000_000;
    private static final long NO_SESSION = -1L;
    private static final long CANCELLING = -2L;

    private final AtomicLong activeSession = new AtomicLong(NO_SESSION);
    private final ILocalOfflineRecognitionService.Stub binder =
            new ILocalOfflineRecognitionService.Stub() {
                @Override
                public void prewarmRealtime() {
                    enforceSameUid();
                    throw new UnsupportedOperationException(
                            "Realtime recognition belongs to the isolated streaming service");
                }

                @Override
                public void releaseRealtimeModel() {
                    enforceSameUid();
                    // This process deliberately never owns the realtime model.
                }

                @Override
                public void startRealtime(
                        long sessionId,
                        ParcelFileDescriptor pcm16,
                        ILocalRealtimeRecognitionCallback callback) {
                    enforceSameUid();
                    closeQuietly(pcm16);
                    throw new UnsupportedOperationException(
                            "Realtime recognition belongs to the isolated streaming service");
                }

                @Override
                public Bundle transcribe(
                        long sessionId,
                        ParcelFileDescriptor wav,
                        String language,
                        boolean useInverseTextNormalization) {
                    enforceSameUid();
                    if (sessionId <= 0L || wav == null) {
                        throw new IllegalArgumentException("Invalid offline recognition request");
                    }
                    if (!activeSession.compareAndSet(NO_SESSION, sessionId)) {
                        closeQuietly(wav);
                        throw new IllegalStateException("Offline recognizer is busy");
                    }
                    try {
                        byte[] audio = readBounded(wav);
                        requireActive(sessionId);
                        try (LocalOfflineRecognizer.Session session =
                                     LocalOfflineRecognizer.openSession(
                                             LocalOfflineRecognitionService.this,
                                             language)) {
                            // Loading and decoding the 228 MB SenseVoice model twice made the
                            // release-to-text path roughly twice as slow. Pick the field-safe mode
                            // before crossing Binder and run one authoritative decode instead.
                            String text = useInverseTextNormalization
                                    ? session.transcribeWithPunctuation(audio)
                                    : session.transcribe(audio);
                            requireActive(sessionId);
                            Bundle result = new Bundle();
                            result.putString(RESULT_EXACT, text);
                            result.putString(RESULT_PUNCTUATED, text);
                            return result;
                        }
                    } finally {
                        closeQuietly(wav);
                        activeSession.compareAndSet(sessionId, NO_SESSION);
                    }
                }

                @Override
                public void cancel(long sessionId) {
                    enforceSameUid();
                    if (sessionId > 0L && activeSession.compareAndSet(sessionId, CANCELLING)) {
                        // Native SenseVoice decode has no cooperative cancellation primitive.
                        // Killing only this private worker process is the deterministic cancel and
                        // memory-pressure boundary; the IME process and its draft remain alive.
                        Process.killProcess(Process.myPid());
                    }
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
    public void onDestroy() {
        LocalOfflineRecognizer.releaseShared();
        super.onDestroy();
    }

    private void enforceSameUid() {
        if (Binder.getCallingUid() != Process.myUid()) {
            throw new SecurityException("Offline recognition is private to OpenTypeless");
        }
    }

    private void requireActive(long sessionId) {
        if (activeSession.get() != sessionId) {
            throw new IllegalStateException("Offline recognition was cancelled");
        }
    }

    static byte[] readBounded(ParcelFileDescriptor descriptor) {
        try (FileInputStream input = new FileInputStream(descriptor.getFileDescriptor())) {
            return BoundedInputReader.read(input, 44, MAX_WAV_BYTES);
        } catch (IOException error) {
            throw new IllegalStateException("Offline audio pipe failed", error);
        }
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor == null) return;
        try {
            descriptor.close();
        } catch (IOException ignored) {
            // The peer may already have closed the pipe during cancellation.
        }
    }
}
