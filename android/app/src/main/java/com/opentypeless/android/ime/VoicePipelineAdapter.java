package com.opentypeless.android.ime;

import java.util.Objects;

/** Adapts the compatibility {@link VoicePipeline} facade to the stable controller boundary. */
public final class VoicePipelineAdapter implements VoiceController {
    private final VoicePipeline pipeline;

    public VoicePipelineAdapter(VoicePipeline pipeline) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
    }

    @Override
    public boolean start(DictationRequest request, Events events) {
        Objects.requireNonNull(request, "request");
        return pipeline.start(request, listenerFor(events));
    }

    @Override
    public void stop() {
        pipeline.stopRecording();
    }

    @Override
    public void cancel() {
        pipeline.cancel();
    }

    @Override
    public State state() {
        return controllerState(pipeline.state());
    }

    static VoicePipeline.Listener listenerFor(Events events) {
        Events sink = Objects.requireNonNull(events, "events");
        return new VoicePipeline.Listener() {
            @Override
            public void onState(VoicePipeline.State state, String message) {
                sink.onState(controllerState(state), message);
            }

            @Override
            public void onRoute(com.opentypeless.android.diagnostics.RecognitionRoute route) {
                sink.onRoute(route);
            }

            @Override
            public void onReadyForSpeech() {
                sink.onReadyForSpeech();
            }

            @Override
            public void onBeginningOfSpeech() {
                sink.onBeginningOfSpeech();
            }

            @Override
            public void onTranscript(TranscriptUpdate update) {
                sink.onTranscript(update);
            }

            @Override
            public void onResult(DictationResult result) {
                sink.onResult(result);
            }

            @Override
            public void onError(String message) {
                sink.onError(message);
            }
        };
    }

    static State controllerState(VoicePipeline.State state) {
        Objects.requireNonNull(state, "state");
        return switch (state) {
            case IDLE -> State.IDLE;
            case RECORDING -> State.RECORDING;
            case TRANSCRIBING -> State.TRANSCRIBING;
            case POLISHING -> State.POLISHING;
        };
    }
}
