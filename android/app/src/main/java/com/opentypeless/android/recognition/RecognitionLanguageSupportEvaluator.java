package com.opentypeless.android.recognition;

import java.util.List;
import java.util.Locale;

final class RecognitionLanguageSupportEvaluator {
    enum Outcome {
        INSTALLED,
        DOWNLOAD_PENDING,
        DOWNLOAD_AVAILABLE,
        ONLINE_ONLY,
        UNSUPPORTED,
        LANGUAGE_UNSPECIFIED
    }

    record Evaluation(Outcome outcome, String language) {}

    private RecognitionLanguageSupportEvaluator() {}

    static Evaluation evaluate(
            String requestedLanguage,
            List<String> installed,
            List<String> pending,
            List<String> supportedOnDevice,
            List<String> online) {
        String requested = normalize(requestedLanguage);
        if (requested.isEmpty()) {
            return new Evaluation(Outcome.LANGUAGE_UNSPECIFIED, "");
        }
        if (contains(installed, requested)) return new Evaluation(Outcome.INSTALLED, requested);
        if (contains(pending, requested)) return new Evaluation(Outcome.DOWNLOAD_PENDING, requested);
        if (contains(supportedOnDevice, requested)) {
            return new Evaluation(Outcome.DOWNLOAD_AVAILABLE, requested);
        }
        if (contains(online, requested)) return new Evaluation(Outcome.ONLINE_ONLY, requested);
        return new Evaluation(Outcome.UNSUPPORTED, requested);
    }

    private static boolean contains(List<String> values, String requested) {
        if (values == null) return false;
        LanguageTag requestedTag = LanguageTag.parse(requested);
        for (String value : values) {
            String normalized = normalize(value);
            if (normalized.equals(requested)
                    || requestedTag.compatibleWith(LanguageTag.parse(normalized))) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : value.trim().replace('_', '-').toLowerCase(Locale.ROOT);
    }

    private record LanguageTag(String language, String script) {
        static LanguageTag parse(String value) {
            Locale locale = Locale.forLanguageTag(normalize(value));
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
