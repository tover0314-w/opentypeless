package com.opentypeless.android.keyboard.emoji;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public final class EmojiCatalogTest {
    @Test
    public void everyBrowseCategoryIsBoundedNonEmptyAndGloballyUnique() {
        Set<String> observed = new HashSet<>();

        for (EmojiCatalog.Category category : EmojiCatalog.browseCategories()) {
            assertFalse(EmojiCatalog.emoji(category).isEmpty());
            assertTrue(EmojiCatalog.emoji(category).size() <= 21);
            for (String emoji : EmojiCatalog.emoji(category)) {
                assertTrue(observed.add(emoji));
                assertTrue(EmojiCatalog.contains(emoji));
            }
        }

        assertEquals(EmojiCatalog.size(), observed.size());
        assertEquals(168, observed.size());
    }

    @Test
    public void recentIsRuntimeOnlyAndUnknownValuesAreRejected() {
        assertTrue(EmojiCatalog.emoji(EmojiCatalog.Category.RECENT).isEmpty());
        assertFalse(EmojiCatalog.contains("not emoji"));
        assertFalse(EmojiCatalog.contains(null));
        assertThrows(NullPointerException.class, () -> EmojiCatalog.emoji(null));
    }
}
