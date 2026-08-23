package com.opentypeless.android.offline;

/** Private callback for the optional first-pass streaming model. */
interface ILocalRealtimeRecognitionCallback {
    oneway void onReady(long sessionId);
    oneway void onPartial(long sessionId, String text);
    oneway void onFinal(long sessionId, String text);
    oneway void onError(long sessionId, String message);
}
