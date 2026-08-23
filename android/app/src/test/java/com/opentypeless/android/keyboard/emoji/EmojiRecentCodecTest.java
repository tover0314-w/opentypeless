package com.opentypeless.android.keyboard.emoji;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class EmojiRecentCodecTest {
    @Test
    public void v1RoundTripPreservesMultiCodePointEmojiOrder() {
        EmojiRecents recents = EmojiRecents.empty()
                .record("😀")
                .record("🐻‍❄️")
                .record("❤️");

        String encoded = EmojiRecentCodec.encode(recents);
        EmojiRecents decoded = EmojiRecentCodec.decode(
                EmojiRecentCodec.FORMAT_VERSION, encoded);

        assertEquals(recents.entries(), decoded.entries());
        assertTrue(encoded.matches("[0-9A-F,-]+"));
    }

    @Test
    public void unknownVersionMalformedUnknownAndOversizedPayloadsFailClosed() {
        assertTrue(EmojiRecentCodec.decode(2, "1F600").isEmpty());
        assertTrue(EmojiRecentCodec.decode(1, "not-hex").isEmpty());
        assertTrue(EmojiRecentCodec.decode(1, "41").isEmpty());
        assertTrue(EmojiRecentCodec.decode(
                1, "1".repeat(EmojiRecentCodec.MAX_PAYLOAD_UTF16_UNITS + 1)).isEmpty());
    }
}
