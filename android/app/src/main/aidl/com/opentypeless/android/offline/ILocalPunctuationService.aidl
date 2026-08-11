package com.opentypeless.android.offline;

/** Internal same-UID text-only contract for the isolated punctuation model. */
interface ILocalPunctuationService {
    void prewarm();
    String punctuate(long sessionId, String text);
    oneway void cancel(long sessionId);
    void releaseModel();
    int servicePid();
}
