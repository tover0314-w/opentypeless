package com.opentypeless.android.offline;

import static org.junit.Assert.assertEquals;

import com.opentypeless.android.context.FieldKind;

import org.junit.Test;

public final class SafePunctuationRestorerTest {
    @Test
    public void acceptsPunctuationOnlyRevision() {
        assertEquals(
                "我现在正在使用百度输入法，它会自动纠正错误词汇。",
                SafePunctuationRestorer.choose(
                        "我现在正在使用百度输入法它会自动纠正错误词汇",
                        "我现在正在使用百度输入法，它会自动纠正错误词汇。",
                        FieldKind.LONG_TEXT));
    }

    @Test
    public void rejectsNumberOrWordMutationAndAddsOnlyTerminalPunctuation() {
        assertEquals(
                "会议时间是二零二六年八月十日。",
                SafePunctuationRestorer.choose(
                        "会议时间是二零二六年八月十日",
                        "会议时间是2026年8月10日。",
                        FieldKind.GENERAL));
        assertEquals(
                "Alice approved the budget.",
                SafePunctuationRestorer.choose(
                        "Alice approved the budget",
                        "Bob approved the budget.",
                        FieldKind.GENERAL));
    }

    @Test
    public void doesNotAddPunctuationToSearchOrStructuredFields() {
        assertEquals(
                "小米15 输入法",
                SafePunctuationRestorer.choose(
                        "小米15 输入法",
                        "小米15输入法。",
                        FieldKind.SEARCH));
        assertEquals(
                "example.com",
                SafePunctuationRestorer.choose(
                        "example.com",
                        "example.com。",
                        FieldKind.URI));
    }

    @Test
    public void preservesExistingTerminalPunctuationOnRejectedCandidate() {
        assertEquals(
                "不要改数字 42！",
                SafePunctuationRestorer.choose(
                        "不要改数字 42！",
                        "不要改数字 43。",
                        FieldKind.SHORT_MESSAGE));
    }

    @Test
    public void rejectsChangedEnglishWordBoundaries() {
        assertEquals(
                "I ordered ice cream.",
                SafePunctuationRestorer.choose(
                        "I ordered ice cream",
                        "I ordered icecream.",
                        FieldKind.GENERAL));
    }

    @Test
    public void rejectsRecasingDecimalMutationAndParagraphFlattening() {
        assertEquals(
                "OpenTypeless costs 3.14 dollars.",
                SafePunctuationRestorer.choose(
                        "OpenTypeless costs 3.14 dollars",
                        "opentypeless costs 314 dollars.",
                        FieldKind.GENERAL));
        assertEquals(
                "first paragraph\nsecond paragraph.",
                SafePunctuationRestorer.choose(
                        "first paragraph\nsecond paragraph",
                        "first paragraph. second paragraph.",
                        FieldKind.LONG_TEXT));
    }
}
