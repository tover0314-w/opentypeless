package com.opentypeless.android.personalization;

import com.opentypeless.android.data.CorrectionRule;
import com.opentypeless.android.data.PersonalTerm;
import com.opentypeless.android.data.PersonalizationSnapshot;

import java.text.BreakIterator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Applies explicitly confirmed corrections once, without cascading replacements. */
public final class PersonalizedTextProcessor {
    private PersonalizedTextProcessor() {}

    private static final int MAX_INPUT_CODE_POINTS = 20_000;
    private static final int MAX_OUTPUT_CODE_POINTS = 40_000;
    private static final int MAX_CANDIDATE_SPANS = 50_000;
    private static final int MAX_REPLACEMENTS = 320;

    private record Replacement(String source, String target, long id, boolean term) {}
    private record Span(
            int start,
            int end,
            int normalizedLength,
            String target,
            long id,
            boolean term) {}

    private record NormalizedView(String text, int[] originalStarts, int[] originalEnds) {
        boolean coversWholeOriginalUnits(int normalizedStart, int normalizedEnd) {
            boolean startsAtBoundary = normalizedStart == 0
                    || originalStarts[normalizedStart] != originalStarts[normalizedStart - 1];
            boolean endsAtBoundary = normalizedEnd == text.length()
                    || originalEnds[normalizedEnd - 1] != originalEnds[normalizedEnd];
            return startsAtBoundary && endsAtBoundary;
        }

        int originalStart(int normalizedIndex) {
            return originalStarts[normalizedIndex];
        }

        int originalEnd(int normalizedExclusiveEnd) {
            return originalEnds[normalizedExclusiveEnd - 1];
        }
    }

    public static ProcessingResult apply(String input, PersonalizationSnapshot snapshot) {
        String original = input == null ? "" : input;
        if (original.codePointCount(0, original.length()) > MAX_INPUT_CODE_POINTS) {
            throw new IllegalArgumentException("Transcript is too long");
        }
        PersonalizationSnapshot safeSnapshot = snapshot == null
                ? PersonalizationSnapshot.empty()
                : snapshot;
        if (safeSnapshot.terms().isEmpty() && safeSnapshot.corrections().isEmpty()) {
            return new ProcessingResult(original, List.of(), List.of());
        }
        List<Replacement> replacements = new ArrayList<>();
        for (CorrectionRule rule : safeSnapshot.corrections()) {
            if (rule.enabled() && !rule.pattern().isBlank() && !rule.replacement().isBlank()) {
                replacements.add(new Replacement(
                        rule.pattern().trim(), rule.replacement().trim(), rule.id(), false));
                if (replacements.size() == MAX_REPLACEMENTS) break;
            }
        }
        outer:
        for (PersonalTerm term : safeSnapshot.terms()) {
            if (!term.enabled() || term.canonical().isBlank()) continue;
            for (String alias : term.aliasList()) {
                replacements.add(new Replacement(
                        alias.trim(), term.canonical().trim(), term.id(), true));
                if (replacements.size() == MAX_REPLACEMENTS) break outer;
            }
        }
        NormalizedView view = normalizedView(original);
        List<Span> candidates = new ArrayList<>();
        for (Replacement replacement : replacements) {
            if (replacement.source().isEmpty() || replacement.source().equals(replacement.target())) continue;
            String normalizedSource = normalize(replacement.source());
            if (normalizedSource.isEmpty()) continue;
            Matcher matcher = pattern(normalizedSource).matcher(view.text());
            while (matcher.find()) {
                // A compatibility glyph can expand under NFKC (for example 1/2). Never replace
                // only part of that expanded view because it has no exact original-text span.
                if (!view.coversWholeOriginalUnits(matcher.start(), matcher.end())) continue;
                if (candidates.size() >= MAX_CANDIDATE_SPANS) {
                    throw new IllegalArgumentException("Too many personalization matches");
                }
                candidates.add(new Span(
                        view.originalStart(matcher.start()),
                        view.originalEnd(matcher.end()),
                        matcher.end() - matcher.start(),
                        replacement.target(),
                        replacement.id(),
                        replacement.term()));
            }
        }
        candidates.sort((left, right) -> {
            int order = Integer.compare(left.start(), right.start());
            if (order != 0) return order;
            order = Integer.compare(right.end() - right.start(), left.end() - left.start());
            if (order != 0) return order;
            order = Integer.compare(right.normalizedLength(), left.normalizedLength());
            if (order != 0) return order;
            // A user-confirmed correction wins over a term alias at the same span.
            order = Boolean.compare(left.term(), right.term());
            if (order != 0) return order;
            return Long.compare(left.id(), right.id());
        });

        List<Span> accepted = new ArrayList<>();
        int occupiedUntil = 0;
        for (Span candidate : candidates) {
            if (candidate.start() >= occupiedUntil) {
                accepted.add(candidate);
                occupiedUntil = candidate.end();
            }
        }

        long outputCodePoints = original.codePointCount(0, original.length());
        for (Span span : accepted) {
            outputCodePoints -= original.codePointCount(span.start(), span.end());
            outputCodePoints += span.target().codePointCount(0, span.target().length());
            if (outputCodePoints > MAX_OUTPUT_CODE_POINTS) {
                throw new IllegalArgumentException("Personalized transcript is too long");
            }
        }

        StringBuilder output = new StringBuilder(original.length());
        Set<Long> termIds = new LinkedHashSet<>();
        Set<Long> correctionIds = new LinkedHashSet<>();
        int cursor = 0;
        for (Span span : accepted) {
            output.append(original, cursor, span.start()).append(span.target());
            cursor = span.end();
            if (span.term()) termIds.add(span.id());
            else correctionIds.add(span.id());
        }
        output.append(original, cursor, original.length());
        String result = output.toString();
        NormalizedView resultView = normalizedView(result);
        for (PersonalTerm term : safeSnapshot.terms()) {
            if (term.enabled() && matches(resultView, term.canonical().trim())) termIds.add(term.id());
        }
        return new ProcessingResult(result, List.copyOf(termIds), List.copyOf(correctionIds));
    }

    private static Pattern pattern(String source) {
        int flags = Pattern.UNICODE_CASE;
        String expression = Pattern.quote(source);
        if (containsLatinOrDigit(source)) {
            expression = "(?<![\\p{L}\\p{N}_])" + expression + "(?![\\p{L}\\p{N}_])";
            flags |= Pattern.CASE_INSENSITIVE;
        }
        return Pattern.compile(expression, flags);
    }

    private static boolean matches(NormalizedView source, String value) {
        String normalized = normalize(value);
        return !normalized.isEmpty() && pattern(normalized).matcher(source.text()).find();
    }

    private static boolean containsLatinOrDigit(String value) {
        for (int index = 0; index < value.length(); ) {
            int codePoint = value.codePointAt(index);
            if (Character.isDigit(codePoint)
                    || (Character.isLetter(codePoint) && Character.UnicodeScript.of(codePoint)
                    == Character.UnicodeScript.LATIN)) {
                return true;
            }
            index += Character.charCount(codePoint);
        }
        return false;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC);
    }

    /**
     * Builds an NFKC view for matching while retaining exact UTF-16 ranges in the original text.
     * Normalizing grapheme clusters instead of the whole output lets replacements preserve every
     * unmatched user text exactly, including full-width characters and combining sequences.
     */
    private static NormalizedView normalizedView(String original) {
        BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
        iterator.setText(original);
        StringBuilder normalized = new StringBuilder(original.length());
        List<Integer> starts = new ArrayList<>();
        List<Integer> ends = new ArrayList<>();
        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            String cluster = normalize(original.substring(start, end));
            normalized.append(cluster);
            for (int index = 0; index < cluster.length(); index++) {
                starts.add(start);
                ends.add(end);
            }
        }
        int[] originalStarts = new int[starts.size()];
        int[] originalEnds = new int[ends.size()];
        for (int index = 0; index < starts.size(); index++) {
            originalStarts[index] = starts.get(index);
            originalEnds[index] = ends.get(index);
        }
        return new NormalizedView(normalized.toString(), originalStarts, originalEnds);
    }
}
