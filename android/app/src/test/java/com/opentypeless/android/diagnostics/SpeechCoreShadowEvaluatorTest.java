package com.opentypeless.android.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.ime.TranscriptUpdate;
import com.opentypeless.android.speech.core.CaptureState;
import com.opentypeless.android.speech.engine.ProcessingLocation;
import org.junit.Test;

public final class SpeechCoreShadowEvaluatorTest {
    @Test
    public void revisionsAndProvisionalPunctuationRemainObservable() {
        SpeechCoreShadowEvaluator evaluator =
                new SpeechCoreShadowEvaluator(1L, ProcessingLocation.ON_DEVICE);

        evaluator.accept(TranscriptUpdate.unstable(
                1L, "我觉得这个", TranscriptUpdate.Source.LOCAL_OFFLINE));
        evaluator.accept(TranscriptUpdate.unstable(
                2L, "我觉得，这个方案", TranscriptUpdate.Source.LOCAL_OFFLINE));
        SpeechCoreShadowSnapshot finalSnapshot = evaluator.complete("我觉得这个方案");

        assertEquals(CaptureState.ENDED, finalSnapshot.captureState());
        assertEquals("我觉得这个方案。", finalSnapshot.renderedText());
        assertEquals(3, finalSnapshot.acceptedRevisions());
        assertEquals(2, finalSnapshot.earlierTextRevisions());
        assertTrue(finalSnapshot.provisionalPunctuationObserved());
        assertTrue(finalSnapshot.terminal());
    }

    @Test
    public void blankAndLateCallbacksCannotEraseTheAcceptedDraft() {
        SpeechCoreShadowEvaluator evaluator =
                new SpeechCoreShadowEvaluator(2L, ProcessingLocation.NETWORK);
        evaluator.accept(TranscriptUpdate.unstable(
                1L, "safe words", TranscriptUpdate.Source.DASHSCOPE_PARAFORMER));
        evaluator.accept(TranscriptUpdate.unstable(
                2L, "   ", TranscriptUpdate.Source.DASHSCOPE_PARAFORMER));
        SpeechCoreShadowSnapshot failed = evaluator.fail();
        SpeechCoreShadowSnapshot late = evaluator.accept(TranscriptUpdate.finalText(
                3L, "must not return", TranscriptUpdate.Source.DASHSCOPE_PARAFORMER));

        assertEquals("safe words.", failed.renderedText());
        assertEquals(failed.renderedText(), late.renderedText());
        assertTrue(late.ignoredCallbacks() >= 2);
        assertFalse(late.renderedText().contains("must not return"));
    }
}
