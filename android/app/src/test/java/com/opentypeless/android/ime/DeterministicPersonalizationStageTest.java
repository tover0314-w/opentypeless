package com.opentypeless.android.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.data.CorrectionRule;
import com.opentypeless.android.data.PersonalTerm;
import com.opentypeless.android.data.PersonalizationSnapshot;
import com.opentypeless.android.personalization.PersonalizedTextProcessor;
import com.opentypeless.android.personalization.ProcessingResult;

import org.junit.Test;

import java.io.Serializable;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

public final class DeterministicPersonalizationStageTest {
    private final DeterministicPersonalizationStage stage =
            new DeterministicPersonalizationStage();

    @Test
    public void stageSurfaceIsPackageConfinedFinalAndCapabilityFree() {
        Class<?> type = DeterministicPersonalizationStage.class;

        assertTrue(Modifier.isFinal(type.getModifiers()));
        assertFalse(Modifier.isPublic(type.getModifiers()));
        assertEquals(
                List.of(TextProcessingPipeline.DeterministicStage.class),
                Arrays.asList(type.getInterfaces()));
        assertEquals(
                List.of("MAX_TRANSCRIPT_CODE_POINTS:int"),
                Arrays.stream(type.getDeclaredFields())
                        .filter(field -> !field.isSynthetic())
                        .map(field -> field.getName() + ":" + field.getType().getName())
                        .toList());
        assertEquals(
                List.of("apply"),
                Arrays.stream(type.getDeclaredMethods())
                        .filter(method -> !method.isSynthetic())
                        .map(method -> method.getName())
                        .toList());
        assertFalse(Serializable.class.isAssignableFrom(type));
        assertFalse(Arrays.stream(type.getDeclaredFields())
                .map(field -> field.getType().getName())
                .anyMatch(name -> name.startsWith("android.")
                        || name.contains("InputConnection")
                        || name.contains("EditorOperation")));
    }

    @Test
    public void stageDelegatesExactDeterministicTextAndMatchedIds() {
        PersonalizationSnapshot snapshot = new PersonalizationSnapshot(
                List.of(new PersonalTerm(
                        7L, "OpenTypeless", "", "open typeless", "", 0, true)),
                List.of(new CorrectionRule(
                        11L, "ten thirty", "10:30", "", 0, true)));
        String input = "Use open typeless at ten thirty";

        ProcessingResult expected = PersonalizedTextProcessor.apply(input, snapshot);
        ProcessingResult actual = stage.apply(
                input,
                snapshot,
                TextProcessingPipeline.DeterministicFailurePolicy.PROPAGATE);

        assertEquals(expected, actual);
        assertEquals("Use OpenTypeless at 10:30", actual.text());
        assertEquals(List.of(7L), actual.matchedTermIds());
        assertEquals(List.of(11L), actual.matchedCorrectionIds());
    }

    @Test
    public void preserveInputPolicyContainsCorruptRuleWithoutInventingMatches() {
        String input = "a ".repeat(100);
        PersonalizationSnapshot explosive = new PersonalizationSnapshot(
                List.of(),
                List.of(new CorrectionRule(
                        1L, "a", "x".repeat(1_000), "", 0, true)));

        ProcessingResult result = stage.apply(
                input,
                explosive,
                TextProcessingPipeline.DeterministicFailurePolicy.PRESERVE_INPUT);

        assertEquals(input, result.text());
        assertTrue(result.matchedTermIds().isEmpty());
        assertTrue(result.matchedCorrectionIds().isEmpty());
    }

    @Test
    public void preserveInputPolicyBoundsOversizedNonBmpTranscriptByCodePoint() {
        String input = "😀".repeat(20_001);

        ProcessingResult result = stage.apply(
                input,
                PersonalizationSnapshot.empty(),
                TextProcessingPipeline.DeterministicFailurePolicy.PRESERVE_INPUT);

        assertEquals(20_000, result.text().codePointCount(0, result.text().length()));
        assertEquals("😀".repeat(20_000), result.text());
        assertTrue(result.matchedTermIds().isEmpty());
        assertTrue(result.matchedCorrectionIds().isEmpty());
    }

    @Test
    public void propagatePolicyAndNullBoundariesFailClosed() {
        String input = "a ".repeat(100);
        PersonalizationSnapshot explosive = new PersonalizationSnapshot(
                List.of(),
                List.of(new CorrectionRule(
                        1L, "a", "x".repeat(1_000), "", 0, true)));

        assertThrows(
                IllegalArgumentException.class,
                () -> stage.apply(
                        input,
                        explosive,
                        TextProcessingPipeline.DeterministicFailurePolicy.PROPAGATE));
        assertThrows(
                NullPointerException.class,
                () -> stage.apply(
                        null,
                        PersonalizationSnapshot.empty(),
                        TextProcessingPipeline.DeterministicFailurePolicy.PRESERVE_INPUT));
        assertThrows(
                NullPointerException.class,
                () -> stage.apply(
                        "text",
                        null,
                        TextProcessingPipeline.DeterministicFailurePolicy.PRESERVE_INPUT));
        assertThrows(
                NullPointerException.class,
                () -> stage.apply("text", PersonalizationSnapshot.empty(), null));
    }
}
