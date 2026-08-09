package com.opentypeless.android.recognition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public final class RecognitionRequestTest {
    @Test
    public void sanitizesExternalIntentValues() {
        RecognitionRequest request = new RecognitionRequest(
                "  zh-CN  ",
                "  com.example.caller  ",
                "  Say something  ",
                100,
                true);

        assertEquals("zh-CN", request.language());
        assertEquals("com.example.caller", request.callingPackage());
        assertEquals("Say something", request.prompt());
        assertEquals(5, request.maxResults());
    }

    @Test
    public void defaultsAreConservative() {
        RecognitionRequest request = RecognitionRequest.defaults();

        assertEquals("", request.language());
        assertEquals(1, request.maxResults());
        assertFalse(request.partialResults());
    }
}
