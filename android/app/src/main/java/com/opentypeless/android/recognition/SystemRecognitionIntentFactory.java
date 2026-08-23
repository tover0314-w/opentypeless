package com.opentypeless.android.recognition;

import android.content.Intent;
import android.os.Build;
import android.speech.RecognizerIntent;

import com.opentypeless.android.data.CorrectionRule;
import com.opentypeless.android.data.PersonalTerm;
import com.opentypeless.android.data.PersonalizationSnapshot;
import com.opentypeless.android.settings.AppSettings;
import com.opentypeless.android.settings.RecognitionBackend;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Single source of truth for start, language-support check, and model-download extras. */
final class SystemRecognitionIntentFactory {
    private SystemRecognitionIntentFactory() {}

    static Intent create(AppSettings settings, PersonalizationSnapshot personalization) {
        return create(
                settings.recognitionBackend(),
                settings.language(),
                true,
                3,
                personalization);
    }

    /** Minimal request for support checks and model downloads; never includes learned text. */
    static Intent createCapabilityRequest(AppSettings settings) {
        if (settings == null) throw new IllegalArgumentException("Settings are required");
        return create(
                settings.recognitionBackend(),
                settings.language(),
                false,
                1,
                new ArrayList<>());
    }

    static Intent create(
            RecognitionBackend recognitionBackend,
            String language,
            boolean partialResults,
            int maxResults,
            List<String> biasingTerms) {
        return create(
                recognitionBackend,
                language,
                partialResults,
                maxResults,
                new ArrayList<>(biasingTerms == null ? List.of() : biasingTerms));
    }

    private static Intent create(
            RecognitionBackend recognitionBackend,
            String language,
            boolean partialResults,
            int maxResults,
            PersonalizationSnapshot personalization) {
        return create(
                recognitionBackend,
                language,
                partialResults,
                maxResults,
                biasingStrings(personalization));
    }

    private static Intent create(
            RecognitionBackend recognitionBackend,
            String language,
            boolean partialResults,
            int maxResults,
            ArrayList<String> biasing) {
        if (recognitionBackend != RecognitionBackend.SYSTEM_ON_DEVICE
                && recognitionBackend != RecognitionBackend.SYSTEM_DEFAULT) {
            throw new IllegalArgumentException("Android recognition requires a system backend");
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partialResults);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, Math.max(1, Math.min(maxResults, 5)));
        intent.putExtra(
                RecognizerIntent.EXTRA_PREFER_OFFLINE,
                recognitionBackend == RecognitionBackend.SYSTEM_ON_DEVICE);
        String normalizedLanguage = language == null ? "" : language.trim();
        if (!normalizedLanguage.isEmpty()) {
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, normalizedLanguage);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            intent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!biasing.isEmpty()) {
                intent.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, biasing);
            }
            intent.putExtra(
                    RecognizerIntent.EXTRA_ENABLE_FORMATTING,
                    RecognizerIntent.FORMATTING_OPTIMIZE_LATENCY);
        }
        return intent;
    }

    static ArrayList<String> biasingStrings(PersonalizationSnapshot snapshot) {
        PersonalizationSnapshot safe = snapshot == null
                ? PersonalizationSnapshot.empty()
                : snapshot;
        Set<String> values = new LinkedHashSet<>();
        List<PersonalTerm> terms = safe.terms() == null ? List.of() : safe.terms();
        int termLimit = Math.min(terms.size(), 512);
        for (int index = 0; index < termLimit; index++) {
            PersonalTerm term = terms.get(index);
            if (term == null) continue;
            addBias(values, term.canonical());
            if (term.aliases() == null || term.aliases().length() <= 4_096) {
                for (String alias : term.aliasList()) addBias(values, alias);
            }
            if (values.size() >= 50) break;
        }
        if (values.size() < 50) {
            List<CorrectionRule> corrections = safe.corrections() == null
                    ? List.of()
                    : safe.corrections();
            int correctionLimit = Math.min(corrections.size(), 512);
            for (int index = 0; index < correctionLimit; index++) {
                CorrectionRule rule = corrections.get(index);
                if (rule == null) continue;
                addBias(values, rule.replacement());
                if (values.size() >= 50) break;
            }
        }
        return new ArrayList<>(values);
    }

    private static void addBias(Set<String> values, String value) {
        if (value == null || value.length() > 320) return;
        String clean = value.trim();
        if (!clean.isEmpty()
                && clean.codePointCount(0, clean.length()) <= 80
                && wellFormedUtf16(clean)) {
            values.add(clean);
        }
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
}
