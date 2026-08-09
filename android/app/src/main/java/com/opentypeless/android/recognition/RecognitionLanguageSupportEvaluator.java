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
        for (String value : values) {
            if (normalize(value).equals(requested)) return true;
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : value.trim().replace('_', '-').toLowerCase(Locale.ROOT);
    }
}
