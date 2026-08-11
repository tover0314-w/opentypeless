package com.opentypeless.android.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class VoiceLabScorerTest {
    @Test
    public void scoresChineseByCharactersAndIgnoresPunctuation() {
        VoiceLabScorer.Score exact = VoiceLabScorer.score("没问题", "没问题。");
        VoiceLabScorer.Score wrong = VoiceLabScorer.score("没问题", "没文题");

        assertEquals("CER", exact.metric());
        assertTrue(exact.exact());
        assertEquals(1, wrong.errors());
        assertEquals(3, wrong.referenceUnits());
        assertEquals(1.0d / 3.0d, wrong.errorRate(), 0.000_001d);
    }

    @Test
    public void scoresEnglishByWordsWithoutCaseOrPunctuationPenalty() {
        VoiceLabScorer.Score exact = VoiceLabScorer.score("Sounds good", "sounds good!");
        VoiceLabScorer.Score missing = VoiceLabScorer.score("No problem", "problem");

        assertEquals("WER", exact.metric());
        assertTrue(exact.exact());
        assertFalse(missing.exact());
        assertEquals(1, missing.errors());
        assertEquals(2, missing.referenceUnits());
    }

    @Test
    public void emptyHypothesisCountsEveryReferenceUnitAsAnError() {
        VoiceLabScorer.Score score = VoiceLabScorer.score("No problem", "");

        assertEquals(2, score.errors());
        assertEquals(1.0d, score.errorRate(), 0.0d);
    }
}
