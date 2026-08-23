package com.opentypeless.android.personalization;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class VoiceCommandProcessorTest {
    @Test
    public void acceptsOnlyWholeUtteranceDeterministicCommands() {
        assertEquals("\n", VoiceCommandProcessor.exactReplacement("换行。"));
        assertEquals(",", VoiceCommandProcessor.exactReplacement("comma"));
        assertNull(VoiceCommandProcessor.exactReplacement("Please add a new line"));
        assertNull(VoiceCommandProcessor.exactReplacement("不要换行"));
    }
}
