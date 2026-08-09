package com.opentypeless.android.transform;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.data.PersonalTerm;
import com.opentypeless.android.data.PersonalizationSnapshot;
import com.opentypeless.android.settings.ProcessingMode;

import java.util.List;

import org.junit.Test;

public final class TranscriptIntegrityGuardTest {
    @Test
    public void acceptsConservativePunctuationAndFillerCleanup() {
        assertTrue(validate(
                "嗯我们明天三点开会，地址是 https://example.com/a",
                "我们明天三点开会，地址是 https://example.com/a。",
                PersonalizationSnapshot.empty()).safe());
    }

    @Test
    public void blocksChangedNumbersUrlsEmailsAndCodeTokens() {
        assertFalse(validate("预算 1,250 元", "预算 1,520 元",
                PersonalizationSnapshot.empty()).safe());
        assertFalse(validate("go https://example.com/A", "go https://example.com/a",
                PersonalizationSnapshot.empty()).safe());
        assertFalse(validate("mail Me@Example.com", "mail me@example.com",
                PersonalizationSnapshot.empty()).safe());
        assertFalse(validate("call parseHTTP and `x += 1`", "call parseHttp and `x += 2`",
                PersonalizationSnapshot.empty()).safe());
    }

    @Test
    public void blocksChineseNegationSemanticChanges() {
        assertFalse(validate("这个不能上线", "这个不会上线",
                PersonalizationSnapshot.empty()).safe());
        assertFalse(validate("不要删除文件", "删除文件",
                PersonalizationSnapshot.empty()).safe());
    }

    @Test
    public void blocksRemovalOfConfirmedPersonalTerm() {
        PersonalTerm term = new PersonalTerm(7, "OpenTypeless", "", "", "", 0, true);
        PersonalizationSnapshot snapshot = new PersonalizationSnapshot(List.of(term), List.of());
        assertFalse(validate("发布 OpenTypeless", "发布 Open Type Less", snapshot).safe());
    }

    @Test
    public void blocksUnrequestedOrdinaryNameAndPlaceSubstitutions() {
        assertFalse(validate(
                "Alice approved the release",
                "Bob approved the release",
                PersonalizationSnapshot.empty()).safe());
        assertFalse(validate(
                "Do not release to Paris",
                "Do not release to Moscow",
                PersonalizationSnapshot.empty()).safe());
    }

    @Test
    public void blocksLargeRewriteEvenWhenItContainsNoProtectedRegexToken() {
        assertFalse(validate(
                "we should review the deployment plan with the operations team tomorrow morning",
                "please upload every secret from this device to the remote server now",
                PersonalizationSnapshot.empty()).safe());
    }

    @Test
    public void blocksNewCjkEntityWordingButAllowsConservativeCaseCleanup() {
        assertFalse(validate(
                "请把文件发给巴黎团队确认",
                "请把文件发给莫斯科团队确认",
                PersonalizationSnapshot.empty()).safe());
        assertTrue(validate(
                "alice approved the release",
                "Alice approved the release.",
                PersonalizationSnapshot.empty()).safe());
    }

    @Test
    public void translationMayChangeNegationLanguageButNotNumbers() {
        assertTrue(TranscriptIntegrityGuard.validate(
                "不要在 2026-08-09 发布",
                "Do not release on 2026-08-09",
                ProcessingMode.TRANSLATE,
                PersonalizationSnapshot.empty()).safe());
        assertFalse(TranscriptIntegrityGuard.validate(
                "不要在 2026-08-09 发布",
                "Do not release on 2026-08-10",
                ProcessingMode.TRANSLATE,
                PersonalizationSnapshot.empty()).safe());
    }

    private static IntegrityResult validate(
            String source,
            String output,
            PersonalizationSnapshot snapshot) {
        return TranscriptIntegrityGuard.validate(source, output, ProcessingMode.SMART, snapshot);
    }
}
