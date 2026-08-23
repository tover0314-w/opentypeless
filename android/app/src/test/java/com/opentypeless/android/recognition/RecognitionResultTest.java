package com.opentypeless.android.recognition;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public final class RecognitionResultTest {
    @Test
    public void normalizesAlternativesAndConfidenceScores() {
        RecognitionResult result = new RecognitionResult(
                List.of(" first ", "", "first", "second"),
                new float[]{0.9f, 2f});

        assertEquals(List.of("first", "second"), result.alternatives());
        assertArrayEquals(new float[]{0.9f, -1f}, result.confidenceScores(), 0f);
        assertEquals("first", result.bestText());
        assertFalse(result.isEmpty());
    }

    @Test
    public void confidenceArrayIsDefensivelyCopiedAndResultsCanBeLimited() {
        RecognitionResult result = new RecognitionResult(
                List.of("one", "two", "three"),
                new float[]{0.8f, 0.5f, 0.2f});

        float[] exposed = result.confidenceScores();
        exposed[0] = 0f;

        assertArrayEquals(new float[]{0.8f, 0.5f, 0.2f}, result.confidenceScores(), 0f);
        assertEquals(List.of("one", "two"), result.limitedTo(2).alternatives());
        assertTrue(RecognitionResult.single("  ").isEmpty());
    }
}
