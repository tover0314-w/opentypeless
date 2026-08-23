package com.opentypeless.android.offline;

import android.app.Service;
import android.content.ComponentCallbacks2;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;

import java.util.concurrent.atomic.AtomicLong;

/** Keeps the optional 72 MiB punctuation model outside both the IME and ASR worker processes. */
public final class LocalPunctuationRecognitionService extends Service {
    private static final long NO_SESSION = -1L;
    private static final long CANCELLING = -2L;

    private final AtomicLong activeSession = new AtomicLong(NO_SESSION);
    private final ILocalPunctuationService.Stub binder =
            new ILocalPunctuationService.Stub() {
                @Override
                public void prewarm() {
                    enforceSameUid();
                    if (activeSession.get() == NO_SESSION) {
                        LocalPunctuationRecognizer.prewarm(
                                LocalPunctuationRecognitionService.this);
                    }
                }

                @Override
                public String punctuate(long sessionId, String text) {
                    enforceSameUid();
                    if (sessionId <= 0L || text == null) {
                        throw new IllegalArgumentException("Invalid punctuation request");
                    }
                    if (!activeSession.compareAndSet(NO_SESSION, sessionId)) {
                        throw new IllegalStateException("Punctuation model is busy");
                    }
                    try {
                        String result = LocalPunctuationRecognizer.addPunctuation(
                                LocalPunctuationRecognitionService.this, text);
                        requireActive(sessionId);
                        return result;
                    } finally {
                        activeSession.compareAndSet(sessionId, NO_SESSION);
                    }
                }

                @Override
                public void cancel(long sessionId) {
                    enforceSameUid();
                    if (sessionId > 0L && activeSession.compareAndSet(sessionId, CANCELLING)) {
                        // Native punctuation has no cooperative cancellation hook. Killing this
                        // text-only private process cannot affect capture, ASR, or the editor draft.
                        Process.killProcess(Process.myPid());
                    }
                }

                @Override
                public void releaseModel() {
                    enforceSameUid();
                    if (activeSession.get() == NO_SESSION) {
                        LocalPunctuationRecognizer.releaseShared();
                        // Native allocators may retain large arenas after release. This process
                        // owns no editor or audio state, so exiting is the deterministic memory
                        // reclamation boundary after each dictation lease.
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
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
                && activeSession.get() == NO_SESSION) {
            LocalPunctuationRecognizer.releaseShared();
        }
    }

    @Override
    public void onDestroy() {
        LocalPunctuationRecognizer.releaseShared();
        super.onDestroy();
    }

    private void enforceSameUid() {
        if (Binder.getCallingUid() != Process.myUid()) {
            throw new SecurityException("Punctuation service is private to OpenTypeless");
        }
    }

    private void requireActive(long sessionId) {
        if (activeSession.get() != sessionId) {
            throw new IllegalStateException("Punctuation request was cancelled");
        }
    }
}
