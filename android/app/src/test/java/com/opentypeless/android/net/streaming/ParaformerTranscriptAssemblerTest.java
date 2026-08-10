package com.opentypeless.android.net.streaming;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class ParaformerTranscriptAssemblerTest {
    @Test
    public void revisesCurrentSentenceAndKeepsCompletedPrefixStable() {
        ParaformerTranscriptAssembler assembler = new ParaformerTranscriptAssembler();

        assertEquals("今天天气", assembler.accept(result(0, "今天天气", false)).text());
        assertEquals("今天天气很好。", assembler.accept(result(0, "今天天气很好。", true)).text());
        ParaformerTranscriptAssembler.Snapshot next =
                assembler.accept(result(1_200, "我们去", false));

        assertEquals("今天天气很好。", next.stableText());
        assertEquals("我们去", next.unstableText());
        assertEquals(
                "今天天气很好。我们去公园。",
                assembler.accept(result(1_200, "我们去公园。", true)).text());
    }

    @Test
    public void duplicateFinalForSameSentenceReplacesInsteadOfAppending() {
        ParaformerTranscriptAssembler assembler = new ParaformerTranscriptAssembler();
        assembler.accept(result(10, "你好", true));
        assembler.accept(result(10, "你好。", true));

        assertEquals("你好。", assembler.finalText());
    }

    @Test
    public void rejectsAnUnboundedMultiSentenceTranscriptWithoutMutatingTheAcceptedPrefix() {
        ParaformerTranscriptAssembler assembler = new ParaformerTranscriptAssembler();
        String accepted = "a".repeat(19_999);
        assembler.accept(result(0, accepted, true));

        assertThrows(
                IllegalArgumentException.class,
                () -> assembler.accept(result(1, "bc", true)));
        assertEquals(accepted, assembler.finalText());
    }

    @Test
    public void missingBeginTimeStillReplacesTheFirstPartialWithItsFinalSentence() {
        ParaformerTranscriptAssembler assembler = new ParaformerTranscriptAssembler();

        assembler.accept(result(-1, "你好", false));
        assembler.accept(result(-1, "你好。", true));

        assertEquals("你好。", assembler.finalText());
    }

    @Test
    public void preservesProviderSuppliedSpacingBetweenEnglishSentences() {
        ParaformerTranscriptAssembler assembler = new ParaformerTranscriptAssembler();
        assembler.accept(result(0, "Hello.", true));
        assembler.accept(result(1_000, " How are", false));

        assertEquals("Hello. How are", assembler.finalText());
    }

    private static ParaformerProtocol.Event result(long begin, String text, boolean end) {
        return new ParaformerProtocol.Event(
                ParaformerProtocol.EventType.RESULT,
                begin,
                text,
                end,
                "",
                "");
    }
}
