package com.opentypeless.android.offline;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import com.opentypeless.android.offline.ILocalRealtimeRecognitionCallback;

/** Internal same-UID contract. Audio is streamed through a pipe and never placed in Binder data. */
interface ILocalOfflineRecognitionService {
    void prewarmRealtime();
    void releaseRealtimeModel();
    void startRealtime(
        long sessionId,
        in ParcelFileDescriptor pcm16,
        ILocalRealtimeRecognitionCallback callback);
    Bundle transcribe(
        long sessionId,
        in ParcelFileDescriptor wav,
        String language,
        boolean useInverseTextNormalization);
    oneway void cancel(long sessionId);
    int servicePid();
}
