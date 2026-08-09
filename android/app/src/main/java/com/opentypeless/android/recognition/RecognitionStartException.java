package com.opentypeless.android.recognition;

public final class RecognitionStartException extends Exception {
    private final RecognitionFailure failure;

    public RecognitionStartException(RecognitionFailure failure) {
        super(failure == null ? "Unable to start speech recognition" : failure.message());
        this.failure = failure == null
                ? RecognitionErrors.fromPipelineMessage(getMessage())
                : failure;
    }

    public RecognitionFailure failure() {
        return failure;
    }
}
