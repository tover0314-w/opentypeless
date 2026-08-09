package com.opentypeless.android.personalization;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.data.CorrectionRule;
import com.opentypeless.android.data.PersonalTerm;
import com.opentypeless.android.data.PersonalizationSnapshot;

import org.junit.Test;

import java.util.List;

public final class PersonalizedTextProcessorTest {
    @Test
    public void replacementsAreComputedFromOriginalTextWithoutCascading() {
        ProcessingResult result = PersonalizedTextProcessor.apply(
                "a b",
                snapshot(
                        List.of(),
                        List.of(correction(1, "a", "b"), correction(2, "b", "c"))));

        assertEquals("b c", result.text());
        assertEquals(List.of(1L, 2L), result.matchedCorrectionIds());
    }

    @Test
    public void overlappingRulesUseLeftmostLongestOriginalSpan() {
        ProcessingResult result = PersonalizedTextProcessor.apply(
                "new york city",
                snapshot(
                        List.of(),
                        List.of(
                                correction(1, "new", "old"),
                                correction(2, "new york", "NY"),
                                correction(3, "york city", "YC"))));

        assertEquals("NY city", result.text());
        assertEquals(List.of(2L), result.matchedCorrectionIds());
    }

    @Test
    public void nfkcMatchingPreservesAllUnmatchedOriginalText() {
        PersonalTerm term = term(7, "OpenTypeless", "open typeless");

        ProcessingResult result = PersonalizedTextProcessor.apply(
                " \nＯＰＥＮ　ＴＹＰＥＬＥＳＳ\t！ ",
                snapshot(List.of(term), List.of()));

        assertEquals(" \nOpenTypeless\t！ ", result.text());
        assertEquals(List.of(7L), result.matchedTermIds());
    }

    @Test
    public void nfkcMatchingMapsCombiningSequenceBackToOriginalSpan() {
        PersonalTerm term = term(8, "Café", "café");

        ProcessingResult result = PersonalizedTextProcessor.apply(
                "Cafe\u0301 noir",
                snapshot(List.of(term), List.of()));

        assertEquals("Café noir", result.text());
        assertEquals(List.of(8L), result.matchedTermIds());
    }

    @Test
    public void nfkcExpansionDoesNotReplaceAnAmbiguousPartialOriginalGlyph() {
        ProcessingResult result = PersonalizedTextProcessor.apply(
                "½ cup",
                snapshot(List.of(), List.of(correction(12, "1", "one"))));

        assertEquals("½ cup", result.text());
        assertTrue(result.matchedCorrectionIds().isEmpty());
    }

    @Test
    public void latinAliasesRespectUnicodeWordBoundaries() {
        PersonalTerm term = term(9, "Cat", "cat");

        ProcessingResult result = PersonalizedTextProcessor.apply(
                "cat concatenate cat_2",
                snapshot(List.of(term), List.of()));

        assertEquals("Cat concatenate cat_2", result.text());
        assertEquals(List.of(9L), result.matchedTermIds());
    }

    @Test
    public void confirmedCorrectionWinsOverAliasAtSameSpan() {
        PersonalTerm term = term(10, "Token", "token");
        CorrectionRule correction = correction(11, "token", "Corrected");

        ProcessingResult result = PersonalizedTextProcessor.apply(
                "token",
                snapshot(List.of(term), List.of(correction)));

        assertEquals("Corrected", result.text());
        assertTrue(result.matchedTermIds().isEmpty());
        assertEquals(List.of(11L), result.matchedCorrectionIds());
    }

    @Test
    public void noMatchReturnsTextByteForByteIncludingOuterWhitespace() {
        String input = "\n  untouched Ｆｕｌｌｗｉｄｔｈ  \t";

        ProcessingResult result = PersonalizedTextProcessor.apply(
                input,
                snapshot(List.of(), List.of(correction(1, "missing", "value"))));

        assertEquals(input, result.text());
    }

    @Test
    public void rejectsProviderTextBeyondTheCommitSafetyLimit() {
        assertThrows(IllegalArgumentException.class, () -> PersonalizedTextProcessor.apply(
                "字".repeat(20_001),
                PersonalizationSnapshot.empty()));
    }

    @Test
    public void rejectsPathologicalPersonalizationExpansion() {
        assertThrows(IllegalArgumentException.class, () -> PersonalizedTextProcessor.apply(
                "错".repeat(1_000),
                snapshot(
                        List.of(),
                        List.of(correction(1, "错", "正".repeat(100))))));
    }

    @Test
    public void legacyTermRowsCannotCreateUnboundedAliasWork() {
        StringBuilder aliases = new StringBuilder();
        for (int index = 0; index < 100; index++) {
            if (index > 0) aliases.append(',');
            aliases.append("spoken").append(index);
        }
        PersonalTerm term = term(20, "Canonical", aliases.toString());

        ProcessingResult result = PersonalizedTextProcessor.apply(
                "spoken0 spoken15 spoken16 spoken99",
                snapshot(List.of(term), List.of()));

        assertEquals("Canonical Canonical spoken16 spoken99", result.text());
        assertEquals(16, term.aliasList().size());
    }

    private static PersonalTerm term(long id, String canonical, String aliases) {
        return new PersonalTerm(id, canonical, "", aliases, "", 0, true);
    }

    private static CorrectionRule correction(long id, String pattern, String replacement) {
        return new CorrectionRule(id, pattern, replacement, "", 0, true);
    }

    private static PersonalizationSnapshot snapshot(
            List<PersonalTerm> terms, List<CorrectionRule> corrections) {
        return new PersonalizationSnapshot(terms, corrections);
    }
}
