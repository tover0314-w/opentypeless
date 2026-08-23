package com.opentypeless.android.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Deterministic CER/WER scorer for the authored Voice Lab prompts. */
public final class VoiceLabScorer {
    public record Score(String metric, int errors, int referenceUnits, double errorRate) {
        public boolean exact() {
            return errors == 0;
        }
    }

    private VoiceLabScorer() {}

    public static Score score(String reference, String hypothesis) {
        if (containsHan(reference)) {
            List<String> expected = characterUnits(reference);
            List<String> actual = characterUnits(hypothesis);
            int errors = distance(expected, actual);
            return new Score("CER", errors, expected.size(), rate(errors, expected.size()));
        }
        List<String> expected = wordUnits(reference);
        List<String> actual = wordUnits(hypothesis);
        int errors = distance(expected, actual);
        return new Score("WER", errors, expected.size(), rate(errors, expected.size()));
    }

    private static boolean containsHan(String value) {
        if (value == null) return false;
        return value.codePoints().anyMatch(codePoint ->
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private static List<String> characterUnits(String value) {
        List<String> units = new ArrayList<>();
        if (value == null) return units;
        value.codePoints()
                .filter(Character::isLetterOrDigit)
                .map(Character::toLowerCase)
                .forEach(codePoint -> units.add(new String(Character.toChars(codePoint))));
        return units;
    }

    private static List<String> wordUnits(String value) {
        String normalized = value == null
                ? ""
                : value.toLowerCase(Locale.ROOT)
                        .replaceAll("[^\\p{L}\\p{N}]+", " ")
                        .trim();
        if (normalized.isEmpty()) return List.of();
        return List.of(normalized.split("\\s+"));
    }

    private static int distance(List<String> expected, List<String> actual) {
        int[] previous = new int[actual.size() + 1];
        int[] current = new int[actual.size() + 1];
        for (int index = 0; index <= actual.size(); index++) previous[index] = index;
        for (int left = 1; left <= expected.size(); left++) {
            current[0] = left;
            for (int right = 1; right <= actual.size(); right++) {
                int substitution = previous[right - 1]
                        + (expected.get(left - 1).equals(actual.get(right - 1)) ? 0 : 1);
                current[right] = Math.min(
                        Math.min(previous[right] + 1, current[right - 1] + 1),
                        substitution);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[actual.size()];
    }

    private static double rate(int errors, int referenceUnits) {
        if (referenceUnits == 0) return errors == 0 ? 0.0d : 1.0d;
        return (double) errors / referenceUnits;
    }
}
