package com.opentypeless.android.keyboard.emoji;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class EmojiRecentStoreInstrumentedTest {
    @Test
    public void v1StorePersistsOnlyBoundedCatalogCodePoints() {
        Context context = ApplicationProvider.getApplicationContext();
        EmojiRecentStore store = new EmojiRecentStore(context);
        store.clear();
        try {
            EmojiRecents saved = EmojiRecents.empty()
                    .record("😀")
                    .record("🐻‍❄️")
                    .record("❤️");
            store.save(saved);

            assertEquals(saved.entries(), store.load().entries());
            SharedPreferences raw = context.getSharedPreferences(
                    EmojiRecentStore.STORE, Context.MODE_PRIVATE);
            assertEquals(EmojiRecentCodec.FORMAT_VERSION,
                    raw.getInt(EmojiRecentStore.VERSION, -1));
            assertTrue(raw.getString(EmojiRecentStore.PAYLOAD, "")
                    .matches("[0-9A-F,-]+"));
            assertEquals(2, raw.getAll().size());
        } finally {
            store.clear();
        }
    }

    @Test
    public void corruptOrUnknownStoredFormatFailsClosed() {
        Context context = ApplicationProvider.getApplicationContext();
        EmojiRecentStore store = new EmojiRecentStore(context);
        SharedPreferences raw = context.getSharedPreferences(
                EmojiRecentStore.STORE, Context.MODE_PRIVATE);
        try {
            raw.edit()
                    .putInt(EmojiRecentStore.VERSION, EmojiRecentCodec.FORMAT_VERSION)
                    .putString(EmojiRecentStore.PAYLOAD, "1F600,not-hex")
                    .commit();
            assertTrue(store.load().isEmpty());

            raw.edit()
                    .putInt(EmojiRecentStore.VERSION, 99)
                    .putString(EmojiRecentStore.PAYLOAD, "1F600")
                    .commit();
            assertTrue(store.load().isEmpty());

            raw.edit()
                    .clear()
                    .putString(EmojiRecentStore.PAYLOAD, "1F600")
                    .commit();
            assertTrue(store.load().isEmpty());
        } finally {
            store.clear();
        }
    }
}
