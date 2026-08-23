package com.opentypeless.android.recognition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.util.List;
import java.util.AbstractList;
import java.util.ArrayList;

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

    @Test
    public void acceptsAndroidChineseAliasesWithoutMixingWritingSystems() {
        assertEquals(
                RecognitionLanguageSupportEvaluator.Outcome.INSTALLED,
                RecognitionLanguageSupportEvaluator.evaluate(
                        "cmn-Hans-CN",
                        List.of("zh-CN"),
                        List.of(),
                        List.of(),
                        List.of()).outcome());
        assertEquals(
                RecognitionLanguageSupportEvaluator.Outcome.INSTALLED,
                RecognitionLanguageSupportEvaluator.evaluate(
                        "zh-Hans",
                        List.of("cmn_CN"),
                        List.of(),
                        List.of(),
                        List.of()).outcome());
        assertEquals(
                RecognitionLanguageSupportEvaluator.Outcome.UNSUPPORTED,
                RecognitionLanguageSupportEvaluator.evaluate(
                        "zh-TW",
                        List.of("zh-CN"),
                        List.of(),
                        List.of(),
                        List.of()).outcome());
    }

    @Test
    public void languageOnlyRequestAcceptsARegionalModel() {
        assertEquals(
                RecognitionLanguageSupportEvaluator.Outcome.INSTALLED,
                RecognitionLanguageSupportEvaluator.evaluate(
                        "en",
                        List.of("en-US"),
                        List.of(),
                        List.of(),
                        List.of()).outcome());
    }

    @Test
    public void rejectsOversizedMalformedAndHostileOemLanguageCollections() {
        ArrayList<String> tooMany = new ArrayList<>();
        for (int index = 0; index < 257; index++) tooMany.add("en-US");
        assertEquals(
                RecognitionLanguageSupportEvaluator.Outcome.INVALID_RESPONSE,
                RecognitionLanguageSupportEvaluator.evaluate(
                        "en-US",
                        tooMany,
                        List.of(),
                        List.of(),
                        List.of()).outcome());
        assertEquals(
                RecognitionLanguageSupportEvaluator.Outcome.INVALID_RESPONSE,
                RecognitionLanguageSupportEvaluator.evaluate(
                        "en-US",
                        List.of("x".repeat(129)),
                        List.of(),
                        List.of(),
                        List.of()).outcome());
        assertEquals(
                RecognitionLanguageSupportEvaluator.Outcome.INVALID_RESPONSE,
                RecognitionLanguageSupportEvaluator.evaluate(
                        "en-US",
                        List.of("en\uD800"),
                        List.of(),
                        List.of(),
                        List.of()).outcome());
        assertEquals(
                RecognitionLanguageSupportEvaluator.Outcome.INVALID_RESPONSE,
                RecognitionLanguageSupportEvaluator.evaluate(
                        "en-US",
                        new AbstractList<>() {
                            @Override public String get(int index) {
                                throw new IllegalStateException("oem-secret-body");
                            }
                            @Override public int size() { return 1; }
                        },
                        List.of(),
                        List.of(),
                        List.of()).outcome());
    }

    @Test
    public void evaluatorDiagnosticsNeverExposeRequestedLanguage() {
        RecognitionLanguageSupportEvaluator.Evaluation evaluation =
                RecognitionLanguageSupportEvaluator.evaluate(
                        "private-language-tag",
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of());

        assertFalse(evaluation.toString().contains("private-language-tag"));
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
