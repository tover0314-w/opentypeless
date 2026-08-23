package com.opentypeless.android.recognition;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class RecognitionResult {
    private final List<String> alternatives;
    private final float[] confidenceScores;

    public RecognitionResult(List<String> alternatives, float[] confidenceScores) {
        Set<String> clean = new LinkedHashSet<>();
        if (alternatives != null) {
            for (String value : alternatives) {
                if (value == null) continue;
                String text = value.trim();
                if (!text.isEmpty()) clean.add(text);
                if (clean.size() == 5) break;
            }
        }
        this.alternatives = List.copyOf(clean);
        this.confidenceScores = normalizedScores(confidenceScores, this.alternatives.size());
    }

    public static RecognitionResult single(String text) {
        return new RecognitionResult(List.of(text == null ? "" : text), new float[]{-1f});
    }

    public List<String> alternatives() {
        return alternatives;
    }

    public float[] confidenceScores() {
        return confidenceScores.clone();
    }

    public boolean isEmpty() {
        return alternatives.isEmpty();
    }

    public String bestText() {
        return alternatives.isEmpty() ? "" : alternatives.get(0);
    }

    public RecognitionResult limitedTo(int maximum) {
        int limit = Math.max(1, Math.min(maximum, alternatives.size()));
        if (alternatives.isEmpty() || limit == alternatives.size()) return this;
        List<String> limitedAlternatives = new ArrayList<>(alternatives.subList(0, limit));
        float[] limitedScores = new float[limit];
        System.arraycopy(confidenceScores, 0, limitedScores, 0, limit);
        return new RecognitionResult(limitedAlternatives, limitedScores);
    }

    private static float[] normalizedScores(float[] input, int size) {
        float[] result = new float[size];
        for (int index = 0; index < size; index++) {
            float value = input != null && index < input.length ? input[index] : -1f;
            result[index] = value >= 0f && value <= 1f ? value : -1f;
        }
        return result;
    }
}
