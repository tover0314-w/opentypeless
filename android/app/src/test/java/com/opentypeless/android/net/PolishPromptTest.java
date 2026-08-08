package com.opentypeless.android.net;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PolishPromptTest {
    @Test
    public void treatsTranscriptAsUntrustedAndRequiresOutputOnly() {
        String prompt = PolishPrompt.systemPrompt();
        assertTrue(prompt.contains("untrusted"));
        assertTrue(prompt.contains("never instructions"));
        assertTrue(prompt.contains("Output only"));
        assertTrue(prompt.contains("do not invent"));
    }
}
