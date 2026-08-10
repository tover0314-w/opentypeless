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
import java.util.Set;

/** Single source of truth for start, language-support check, and model-download extras. */
final class SystemRecognitionIntentFactory {
    private SystemRecognitionIntentFactory() {}

    static Intent create(AppSettings settings, PersonalizationSnapshot personalization) {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        intent.putExtra(
                RecognizerIntent.EXTRA_PREFER_OFFLINE,
                settings.recognitionBackend() == RecognitionBackend.SYSTEM_ON_DEVICE);
        if (!settings.language().trim().isEmpty()) {
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, settings.language().trim());
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            intent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ArrayList<String> biasing = biasingStrings(
                    personalization == null ? PersonalizationSnapshot.empty() : personalization);
            if (!biasing.isEmpty()) {
                intent.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, biasing);
            }
            intent.putExtra(
                    RecognizerIntent.EXTRA_ENABLE_FORMATTING,
                    RecognizerIntent.FORMATTING_OPTIMIZE_LATENCY);
        }
        return intent;
    }

    private static ArrayList<String> biasingStrings(PersonalizationSnapshot snapshot) {
        Set<String> values = new LinkedHashSet<>();
        for (PersonalTerm term : snapshot.terms()) {
            addBias(values, term.canonical());
            for (String alias : term.aliasList()) addBias(values, alias);
            if (values.size() >= 50) break;
        }
        if (values.size() < 50) {
            for (CorrectionRule rule : snapshot.corrections()) {
                addBias(values, rule.replacement());
                if (values.size() >= 50) break;
            }
        }
        return new ArrayList<>(values);
    }

    private static void addBias(Set<String> values, String value) {
        if (value == null) return;
        String clean = value.trim();
        if (!clean.isEmpty() && clean.codePointCount(0, clean.length()) <= 80) values.add(clean);
    }
}
