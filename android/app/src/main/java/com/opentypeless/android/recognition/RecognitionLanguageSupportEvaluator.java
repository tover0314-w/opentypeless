package com.opentypeless.android.recognition;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

final class RecognitionLanguageSupportEvaluator {
    private static final int MAX_LANGUAGE_ENTRIES = 256;
    private static final int MAX_LANGUAGE_UTF16_UNITS = 128;
    private static final int MAX_LANGUAGE_CODE_POINTS = 64;

    enum Outcome {
        INSTALLED,
        DOWNLOAD_PENDING,
        DOWNLOAD_AVAILABLE,
        ONLINE_ONLY,
        UNSUPPORTED,
        LANGUAGE_UNSPECIFIED,
        INVALID_RESPONSE
    }

    record Evaluation(Outcome outcome, String language) {
        Evaluation {
            outcome = Objects.requireNonNull(outcome, "outcome");
            language = language == null ? "" : language;
            if (language.length() > MAX_LANGUAGE_UTF16_UNITS) {
                throw new IllegalArgumentException("Language tag is too long");
            }
        }

        @Override
        public String toString() {
            return "Evaluation{outcome=" + outcome + ", language=<redacted>}";
        }
    }

    private enum Match { YES, NO, INVALID }

    private RecognitionLanguageSupportEvaluator() {}

    static Evaluation evaluate(
            String requestedLanguage,
            List<String> installed,
            List<String> pending,
            List<String> supportedOnDevice,
            List<String> online) {
        try {
            String requested = normalize(requestedLanguage);
            if (requested == null) return invalid();
            if (requested.isEmpty()) {
                return new Evaluation(Outcome.LANGUAGE_UNSPECIFIED, "");
            }
            Match installedMatch = contains(installed, requested);
            Match pendingMatch = contains(pending, requested);
            Match supportedMatch = contains(supportedOnDevice, requested);
            Match onlineMatch = contains(online, requested);
            if (installedMatch == Match.INVALID
                    || pendingMatch == Match.INVALID
                    || supportedMatch == Match.INVALID
                    || onlineMatch == Match.INVALID) {
                return invalid();
            }
            if (installedMatch == Match.YES) return new Evaluation(Outcome.INSTALLED, requested);
            if (pendingMatch == Match.YES) {
                return new Evaluation(Outcome.DOWNLOAD_PENDING, requested);
            }
            if (supportedMatch == Match.YES) {
                return new Evaluation(Outcome.DOWNLOAD_AVAILABLE, requested);
            }
            if (onlineMatch == Match.YES) return new Evaluation(Outcome.ONLINE_ONLY, requested);
            return new Evaluation(Outcome.UNSUPPORTED, requested);
        } catch (RuntimeException ignored) {
            return invalid();
        }
    }

    private static Match contains(List<String> values, String requested) {
        if (values == null) return Match.NO;
        int size = values.size();
        if (size < 0 || size > MAX_LANGUAGE_ENTRIES) return Match.INVALID;
        LanguageTag requestedTag = LanguageTag.parse(requested);
        for (int index = 0; index < size; index++) {
            String value = values.get(index);
            String normalized = normalize(value);
            if (normalized == null) return Match.INVALID;
            if (normalized.equals(requested)
                    || requestedTag.compatibleWith(LanguageTag.parse(normalized))) {
                return Match.YES;
            }
        }
        return Match.NO;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        if (value.length() > MAX_LANGUAGE_UTF16_UNITS || !wellFormedUtf16(value)) return null;
        String clean = value.trim().replace('_', '-').toLowerCase(Locale.ROOT);
        if (clean.length() > MAX_LANGUAGE_UTF16_UNITS
                || clean.codePointCount(0, clean.length()) > MAX_LANGUAGE_CODE_POINTS
                || !wellFormedUtf16(clean)) {
            return null;
        }
        return clean;
    }

    private static Evaluation invalid() {
        return new Evaluation(Outcome.INVALID_RESPONSE, "");
    }

    private static boolean wellFormedUtf16(String value) {
        for (int index = 0; index < value.length(); ) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return false;
                }
                index += 2;
            } else if (Character.isLowSurrogate(unit)) {
                return false;
            } else {
                index++;
            }
        }
        return true;
    }

    private record LanguageTag(String language, String script) {
        static LanguageTag parse(String value) {
            String normalized = normalize(value);
            if (normalized == null) return new LanguageTag("", "");
            Locale locale = Locale.forLanguageTag(normalized);
            String language = locale.getLanguage().toLowerCase(Locale.ROOT);
            if (language.equals("cmn")) language = "zh";
            String script = locale.getScript();
            if (language.equals("zh") && script.isEmpty()) {
                script = switch (locale.getCountry().toUpperCase(Locale.ROOT)) {
                    case "CN", "SG", "MY" -> "Hans";
                    case "TW", "HK", "MO" -> "Hant";
                    default -> "";
                };
            }
            return new LanguageTag(language, script.toLowerCase(Locale.ROOT));
        }

        boolean compatibleWith(LanguageTag candidate) {
            if (language.isEmpty() || !language.equals(candidate.language)) return false;
            return script.isEmpty()
                    || candidate.script.isEmpty()
                    || script.equals(candidate.script);
        }
    }
}
