package com.opentypeless.android.transform;

import com.opentypeless.android.data.PersonalTerm;
import com.opentypeless.android.data.PersonalizationSnapshot;
import com.opentypeless.android.settings.ProcessingMode;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TranscriptIntegrityGuard {
    private static final Pattern PROTECTED = Pattern.compile(
            "(?:https?://|www\\.)[^\\s<>\\[\\](){}\\\"']*[\\p{L}\\p{N}/#=_~-]"
                    + "|[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}.-]+\\.[\\p{L}]{2,}"
                    + "|`[^`\\r\\n]{1,200}`"
                    + "|(?<![\\p{L}\\p{N}_])(?:[A-Za-z][A-Za-z0-9]*_[A-Za-z0-9_]+"
                    + "|[A-Za-z]+[A-Z][A-Za-z0-9]*"
                    + "|0x[0-9A-Fa-f]+)(?![\\p{L}\\p{N}_])"
                    + "|(?i:(?:[$€£¥￥]|USD|EUR|CNY|RMB)?\\s*[+-]?\\d+"
                    + "(?:[.,:/-]\\d+)*(?:%|％|元|美元|欧元)?)");
    private static final Pattern NEGATION = Pattern.compile(
            "(?iu)(?<![\\p{L}])(?:not|no|never|without|cannot|can't|don't|doesn't|isn't|won't)(?![\\p{L}])"
                    + "|不能|不要|沒有|没有|沒法|没法|不可|不|沒|没|無|无|未|別|别");
    private static final Pattern PROPER_NOUN = Pattern.compile(
            "(?<![\\p{L}\\p{N}_])(?:\\p{Lu}[\\p{Ll}\\p{M}]{1,}|\\p{Lu}{2,})"
                    + "(?![\\p{L}\\p{N}_])");
    private static final int MIN_UNITS_FOR_REWRITE_CHECK = 8;
    private static final int MIN_LEXICAL_OVERLAP_PERCENT = 60;

    private TranscriptIntegrityGuard() {}

    public static IntegrityResult validate(
            String source,
            String output,
            ProcessingMode mode,
            PersonalizationSnapshot snapshot) {
        List<String> reasons = new ArrayList<>();
        String cleanSource = source == null ? "" : source.trim();
        String cleanOutput = output == null ? "" : output.trim();
        if (cleanOutput.isEmpty()) reasons.add("AI returned empty text");
        if (cleanOutput.length() > 20_000) reasons.add("AI output is too long");
        double maximumRatio = mode == ProcessingMode.TRANSLATE ? 3.5 : 2.5;
        if (!cleanSource.isEmpty() && cleanOutput.length() > cleanSource.length() * maximumRatio + 80) {
            reasons.add("AI output expanded unexpectedly");
        }
        if (!multiset(PROTECTED, cleanSource).equals(multiset(PROTECTED, cleanOutput))) {
            reasons.add("AI changed a number, amount, URL, date-like value, or email address");
        }
        if (mode != ProcessingMode.TRANSLATE
                && !multiset(NEGATION, cleanSource).equals(multiset(NEGATION, cleanOutput))) {
            reasons.add("AI changed a negation");
        }
        if (mode != ProcessingMode.TRANSLATE) {
            protectProperNouns(cleanSource, cleanOutput, reasons);
            protectAgainstLargeRewrite(cleanSource, cleanOutput, reasons);
            if (lexicalUnits(cleanSource).size() >= MIN_UNITS_FOR_REWRITE_CHECK
                    && introducedCjkUnits(cleanSource, cleanOutput) >= 2) {
                reasons.add("AI introduced new CJK entity wording");
            }
        }
        for (PersonalTerm term : snapshot.terms()) {
            String canonical = term.canonical().trim();
            if (!canonical.isEmpty() && containsIgnoreCase(cleanSource, canonical)
                    && !containsIgnoreCase(cleanOutput, canonical)) {
                reasons.add("AI changed personal term: " + canonical);
            }
        }
        return reasons.isEmpty() ? IntegrityResult.ok() : new IntegrityResult(false, List.copyOf(reasons));
    }

    private static Map<String, Integer> multiset(Pattern pattern, String value) {
        Map<String, Integer> result = new HashMap<>();
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) {
            String token = matcher.group().replaceAll("\\s+", "");
            result.put(token, result.getOrDefault(token, 0) + 1);
        }
        return result;
    }

    private static void protectProperNouns(String source, String output, List<String> reasons) {
        Map<String, Integer> required = normalizedMultiset(PROPER_NOUN, source);
        if (required.isEmpty()) return;
        Map<String, Integer> outputUnits = unitMultiset(lexicalUnits(output));
        for (Map.Entry<String, Integer> entry : required.entrySet()) {
            if (outputUnits.getOrDefault(entry.getKey(), 0) < entry.getValue()) {
                reasons.add("AI changed a name or place: " + entry.getKey());
            }
        }
    }

    private static void protectAgainstLargeRewrite(
            String source,
            String output,
            List<String> reasons) {
        List<String> sourceUnits = lexicalUnits(source);
        List<String> outputUnits = lexicalUnits(output);
        if (sourceUnits.size() < MIN_UNITS_FOR_REWRITE_CHECK || outputUnits.isEmpty()) return;
        Map<String, Integer> sourceCounts = unitMultiset(sourceUnits);
        Map<String, Integer> outputCounts = unitMultiset(outputUnits);
        int overlap = 0;
        for (Map.Entry<String, Integer> entry : sourceCounts.entrySet()) {
            overlap += Math.min(entry.getValue(), outputCounts.getOrDefault(entry.getKey(), 0));
        }
        if (overlap * 100 < sourceUnits.size() * MIN_LEXICAL_OVERLAP_PERCENT
                || overlap * 100 < outputUnits.size() * MIN_LEXICAL_OVERLAP_PERCENT) {
            reasons.add("AI rewrote too much of the original wording");
        }
    }

    private static int introducedCjkUnits(String source, String output) {
        Map<String, Integer> remaining = new HashMap<>();
        for (String unit : lexicalUnits(source)) {
            if (isSingleCjkUnit(unit)) remaining.merge(unit, 1, Integer::sum);
        }
        int introduced = 0;
        for (String unit : lexicalUnits(output)) {
            if (!isSingleCjkUnit(unit)) continue;
            int count = remaining.getOrDefault(unit, 0);
            if (count == 0) introduced++;
            else remaining.put(unit, count - 1);
        }
        return introduced;
    }

    private static boolean isSingleCjkUnit(String value) {
        if (value.codePointCount(0, value.length()) != 1) return false;
        Character.UnicodeScript script = Character.UnicodeScript.of(value.codePointAt(0));
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private static Map<String, Integer> normalizedMultiset(Pattern pattern, String value) {
        Map<String, Integer> result = new HashMap<>();
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) {
            String token = normalizeUnit(matcher.group());
            result.merge(token, 1, Integer::sum);
        }
        return result;
    }

    private static Map<String, Integer> unitMultiset(List<String> values) {
        Map<String, Integer> result = new HashMap<>();
        for (String value : values) result.merge(value, 1, Integer::sum);
        return result;
    }

    private static List<String> lexicalUnits(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        List<String> units = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        for (int offset = 0; offset < normalized.length(); ) {
            int codePoint = normalized.codePointAt(offset);
            if (isCjk(codePoint)) {
                flushWord(word, units);
                units.add(new String(Character.toChars(codePoint)));
            } else if (Character.isLetterOrDigit(codePoint)
                    || Character.getType(codePoint) == Character.NON_SPACING_MARK
                    || Character.getType(codePoint) == Character.COMBINING_SPACING_MARK
                    || codePoint == '_') {
                word.appendCodePoint(codePoint);
            } else {
                flushWord(word, units);
            }
            offset += Character.charCount(codePoint);
        }
        flushWord(word, units);
        return units;
    }

    private static boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private static void flushWord(StringBuilder word, List<String> units) {
        if (word.length() == 0) return;
        units.add(word.toString());
        word.setLength(0);
    }

    private static String normalizeUnit(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static boolean containsIgnoreCase(String source, String value) {
        return source.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT));
    }
}
