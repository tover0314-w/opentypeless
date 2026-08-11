package com.opentypeless.android.offline;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

/** Internal same-UID contract. Audio is streamed through a pipe and never placed in Binder data. */
interface ILocalOfflineRecognitionService {
    Bundle transcribe(long sessionId, in ParcelFileDescriptor wav, String language);
    oneway void cancel(long sessionId);
    int servicePid();
}
