package com.opentypeless.android.net.streaming;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class ParaformerCredentialTest {
    @Test
    public void validatesStoredCredentialAgainBeforeBuildingTheAuthorizationHeader() {
        assertEquals("valid-key", ParaformerStreamingRecognizer.requireApiKey(" valid-key "));
        assertThrows(
                IllegalArgumentException.class,
                () -> ParaformerStreamingRecognizer.requireApiKey(""));
        assertThrows(
                IllegalArgumentException.class,
                () -> ParaformerStreamingRecognizer.requireApiKey("secret\nInjected: value"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ParaformerStreamingRecognizer.requireApiKey("k".repeat(4_097)));
    }
}
