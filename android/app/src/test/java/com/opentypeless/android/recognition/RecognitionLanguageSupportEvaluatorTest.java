package com.opentypeless.android.recognition;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

public final class RecognitionLanguageSupportEvaluatorTest {
    @Test
    public void resolvesInstalledPendingDownloadOnlineAndUnsupportedExactly() {
        assertEquals(
                RecognitionLanguageSupportEvaluator.Outcome.INSTALLED,
                evaluate("zh_CN").outcome());
        assertEquals(
                RecognitionLanguageSupportEvaluator.Outcome.DOWNLOAD_PENDING,
                evaluate("fr-FR").outcome());
        assertEquals(
                RecognitionLanguageSupportEvaluator.Outcome.DOWNLOAD_AVAILABLE,
                evaluate("JA-jp").outcome());
        assertEquals(
                RecognitionLanguageSupportEvaluator.Outcome.ONLINE_ONLY,
                evaluate("de-DE").outcome());
        assertEquals(
                RecognitionLanguageSupportEvaluator.Outcome.UNSUPPORTED,
                evaluate("en").outcome());
        assertEquals(
                RecognitionLanguageSupportEvaluator.Outcome.LANGUAGE_UNSPECIFIED,
                evaluate(" ").outcome());
    }

    private static RecognitionLanguageSupportEvaluator.Evaluation evaluate(String language) {
        return RecognitionLanguageSupportEvaluator.evaluate(
                language,
                List.of("zh-CN"),
                List.of("fr-FR"),
                List.of("ja-JP"),
                List.of("de-DE"));
    }
}
