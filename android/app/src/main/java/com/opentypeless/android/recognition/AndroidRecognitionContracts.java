package com.opentypeless.android.recognition;

import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;

public final class AndroidRecognitionContracts {
    public static final String EXTRA_ERROR_CODE =
            "com.opentypeless.android.extra.RECOGNITION_ERROR_CODE";
    public static final String EXTRA_ERROR_MESSAGE =
            "com.opentypeless.android.extra.RECOGNITION_ERROR_MESSAGE";

    private AndroidRecognitionContracts() {}

    public static RecognitionRequest request(Intent intent, String callingPackage) {
        if (intent == null) {
            return new RecognitionRequest("", callingPackage, "", 1, false);
        }
        return new RecognitionRequest(
                intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE),
                callingPackage,
                intent.getStringExtra(RecognizerIntent.EXTRA_PROMPT),
                intent.getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1),
                intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false));
    }

    public static Bundle results(RecognitionResult result) {
        RecognitionResult safe = result == null
                ? new RecognitionResult(null, null)
                : result;
        Bundle bundle = new Bundle();
        bundle.putStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION,
                new ArrayList<>(safe.alternatives()));
        bundle.putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, safe.confidenceScores());
        return bundle;
    }

    public static Intent resultIntent(RecognitionResult result) {
        RecognitionResult safe = result == null
                ? new RecognitionResult(null, null)
                : result;
        return new Intent()
                .putStringArrayListExtra(
                        RecognizerIntent.EXTRA_RESULTS,
                        new ArrayList<>(safe.alternatives()))
                .putExtra(
                        RecognizerIntent.EXTRA_CONFIDENCE_SCORES,
                        safe.confidenceScores());
    }

    public static Intent errorIntent(RecognitionFailure failure) {
        RecognitionFailure safe = failure == null
                ? RecognitionErrors.fromPipelineMessage(null)
                : failure;
        return new Intent()
                .putExtra(EXTRA_ERROR_CODE, safe.errorCode())
                .putExtra(EXTRA_ERROR_MESSAGE, safe.message());
    }
}
