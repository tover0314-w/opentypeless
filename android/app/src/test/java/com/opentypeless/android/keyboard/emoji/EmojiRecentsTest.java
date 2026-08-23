package com.opentypeless.android.keyboard.emoji;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class EmojiRecentsTest {
    @Test
    public void recordingMovesKnownEmojiToFrontAndKeepsTheMruBound() {
        EmojiRecents recents = EmojiRecents.empty();
        List<String> known = new ArrayList<>();
        for (EmojiCatalog.Category category : EmojiCatalog.browseCategories()) {
            known.addAll(EmojiCatalog.emoji(category));
        }
        for (int index = 0; index < EmojiRecents.MAX_ENTRIES + 3; index++) {
            recents = recents.record(known.get(index));
        }

        String repeated = known.get(EmojiRecents.MAX_ENTRIES);
        recents = recents.record(repeated);

        assertEquals(EmojiRecents.MAX_ENTRIES, recents.entries().size());
        assertEquals(repeated, recents.entries().get(0));
        assertEquals(1, recents.entries().stream().filter(repeated::equals).count());
    }

    @Test
    public void storedInputDropsUnknownAndDuplicateValuesWithoutLoggingBodies() {
        EmojiRecents recents = EmojiRecents.fromStored(List.of("😀", "unknown", "😀", "😃"));

        assertEquals(List.of("😀", "😃"), recents.entries());
        assertTrue(recents.toString().contains("count=2"));
        assertFalse(recents.toString().contains("😀"));
        assertThrows(IllegalArgumentException.class, () -> recents.record("unknown"));
    }
}
