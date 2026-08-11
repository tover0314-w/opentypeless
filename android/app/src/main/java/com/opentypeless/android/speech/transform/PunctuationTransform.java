package com.opentypeless.android.speech.transform;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Punctuation-only candidate gate that preserves lexical content and protected literal spelling. */
public final class PunctuationTransform {
    private static final int MAX_CODE_POINTS = 20_000;
    private static final Pattern PROTECTED = Pattern.compile(
            "(?iu)(?:https?://|www\\.)[^\\s<>\\[\\](){}\\\"']+"
                    + "|[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}.-]+\\.[\\p{L}]{2,}"
                    + "|`[^`\\r\\n]{1,200}`"
                    + "|(?<![\\p{L}\\p{N}_])(?:[$€£¥￥]|USD|EUR|CNY|RMB)?\\s*[+-]?\\d+"
                    + "(?:[.,:/-]\\d+)*(?:%|％|元|美元|欧元)?(?![\\p{L}\\p{N}_])");

    private PunctuationTransform() {}

    /** Shared fail-closed gate used by both Speech Core v2 and legacy recovery paths. */
    public static boolean preservesLexicalContent(String source, String candidate) {
        String safeSource = source == null ? "" : source;
        String safeCandidate = candidate == null ? "" : candidate;
        return !safeSource.isBlank()
                && !safeCandidate.isBlank()
                && safeCandidate.codePointCount(0, safeCandidate.length()) <= MAX_CODE_POINTS
                && newlineCount(safeSource) == newlineCount(safeCandidate)
                && lexicalKey(safeSource).equals(lexicalKey(safeCandidate))
                && protectedLiterals(safeSource).equals(protectedLiterals(safeCandidate));
    }

    /** Punctuation-insensitive, case-sensitive key for diagnostics and native smoke tests. */
    public static String contentKey(String value) {
        return lexicalKey(value == null ? "" : value);
    }

    static Decision apply(String source, String candidate) {
        String safeSource = source == null ? "" : source;
        if (safeSource.isBlank()) return Decision.unchanged(safeSource, "empty source");
        if (candidate == null || candidate.isBlank()) {
            String fallback = appendTerminal(safeSource);
            return fallback.equals(safeSource)
                    ? Decision.unchanged(safeSource, "terminal punctuation already present")
                    : Decision.applied(fallback, "safe terminal punctuation");
        }
        String safeCandidate = candidate;
        if (safeCandidate.codePointCount(0, safeCandidate.length()) > MAX_CODE_POINTS) {
            return Decision.rejected(
                    safeSource, appendTerminal(safeSource), "punctuation candidate is too long");
        }
        if (newlineCount(safeSource) != newlineCount(safeCandidate)) {
            return Decision.rejected(
                    safeSource, appendTerminal(safeSource),
                    "punctuation candidate changed paragraphs");
        }
        if (!lexicalKey(safeSource).equals(lexicalKey(safeCandidate))) {
            return Decision.rejected(
                    safeSource, appendTerminal(safeSource),
                    "punctuation candidate changed words");
        }
        if (!protectedLiterals(safeSource).equals(protectedLiterals(safeCandidate))) {
            return Decision.rejected(
                    safeSource,
                    appendTerminal(safeSource),
                    "punctuation candidate changed a protected literal");
        }
        return safeCandidate.equals(safeSource)
                ? Decision.unchanged(safeSource, "candidate is byte-identical")
                : Decision.applied(safeCandidate, "punctuation-only candidate accepted");
    }

    private static String lexicalKey(String value) {
        // Punctuation restoration is not allowed to silently recase English names or acronyms.
        // NFKC still makes compatibility punctuation/spaces comparable, but lexical code points
        // remain case-sensitive and must survive byte-for-byte after normalization.
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        StringBuilder result = new StringBuilder(normalized.length());
        boolean pendingSpace = false;
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isLetterOrDigit(codePoint)
                    || Character.getType(codePoint) == Character.NON_SPACING_MARK
                    || Character.getType(codePoint) == Character.COMBINING_SPACING_MARK
                    || codePoint == '_') {
                if (pendingSpace && result.length() > 0) result.append(' ');
                pendingSpace = false;
                result.appendCodePoint(codePoint);
            } else if (Character.isWhitespace(codePoint)) {
                pendingSpace = result.length() > 0;
            }
        }
        return result.toString();
    }

    private static List<String> protectedLiterals(String value) {
        ArrayList<String> result = new ArrayList<>();
        Matcher matcher = PROTECTED.matcher(Normalizer.normalize(value, Normalizer.Form.NFKC));
        while (matcher.find()) {
            result.add(matcher.group().replaceAll("\\s+", "").toLowerCase(Locale.ROOT));
        }
        return List.copyOf(result);
    }

    private static int newlineCount(String value) {
        int count = 0;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == '\n') count++;
        }
        return count;
    }

    private static String appendTerminal(String value) {
        int trailingStart = value.length();
        while (trailingStart > 0) {
            int codePoint = value.codePointBefore(trailingStart);
            if (!Character.isWhitespace(codePoint)) break;
            trailingStart -= Character.charCount(codePoint);
        }
        if (trailingStart == 0) return value;
        int last = value.codePointBefore(trailingStart);
        if (".!?。！？…".codePoints().anyMatch(candidate -> candidate == last)) return value;
        boolean containsHan = value.substring(0, trailingStart).codePoints()
                .anyMatch(codePoint -> Character.UnicodeScript.of(codePoint)
                        == Character.UnicodeScript.HAN);
        return value.substring(0, trailingStart)
                + (containsHan ? "。" : ".")
                + value.substring(trailingStart);
    }

    record Decision(String text, boolean changed, boolean rejected, String reason) {
        private static Decision applied(String text, String reason) {
            return new Decision(text, true, false, reason);
        }

        private static Decision unchanged(String text, String reason) {
            return new Decision(text, false, false, reason);
        }

        private static Decision rejected(String source, String fallback, String reason) {
            return new Decision(fallback, !fallback.equals(source), true, reason);
        }
    }
}
