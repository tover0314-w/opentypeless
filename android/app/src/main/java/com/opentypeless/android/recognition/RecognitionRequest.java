package com.opentypeless.android.recognition;

public record RecognitionRequest(
        String language,
        String callingPackage,
        String prompt,
        int maxResults,
        boolean partialResults) {

    private static final int MAX_LANGUAGE_LENGTH = 80;
    private static final int MAX_PACKAGE_LENGTH = 240;
    private static final int MAX_PROMPT_LENGTH = 500;

    public RecognitionRequest {
        language = limited(language, MAX_LANGUAGE_LENGTH);
        callingPackage = limited(callingPackage, MAX_PACKAGE_LENGTH);
        prompt = limited(prompt, MAX_PROMPT_LENGTH);
        maxResults = Math.max(1, Math.min(maxResults, 5));
    }

    public static RecognitionRequest defaults() {
        return new RecognitionRequest("", "", "", 1, false);
    }

    private static String limited(String value, int maximum) {
        if (value == null) return "";
        String clean = value.trim();
        return clean.length() <= maximum ? clean : clean.substring(0, maximum);
    }
}
