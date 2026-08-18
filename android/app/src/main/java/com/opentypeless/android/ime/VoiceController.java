package com.opentypeless.android.ime;

import com.opentypeless.android.diagnostics.RecognitionRoute;

/**
 * Stable session-control boundary for one voice dictation run.
 *
 * <p>The boundary intentionally exposes neither Android UI objects nor persistence services.
 * Recovery storage, model warm-up and process shutdown remain implementation lifecycle concerns,
 * not capabilities of a voice session caller.
 */
public interface VoiceController {
    enum State { IDLE, RECORDING, TRANSCRIBING, POLISHING }

    interface Events {
        void onState(State state, String message);

        default void onRoute(RecognitionRoute route) {}

        default void onReadyForSpeech() {}

        default void onBeginningOfSpeech() {}

        default void onTranscript(TranscriptUpdate update) {}

        void onResult(DictationResult result);

        void onError(String message);
    }

    boolean start(DictationRequest request, Events events);

    void stop();

    void cancel();

    State state();
}
