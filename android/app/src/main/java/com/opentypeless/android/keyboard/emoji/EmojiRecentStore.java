package com.opentypeless.android.keyboard.emoji;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Objects;

/** Private, backup-excluded v1 store for the bounded Emoji MRU. */
public final class EmojiRecentStore {
    static final String STORE = "opentypeless_emoji_recents_v1";
    static final String VERSION = "format_version";
    static final String PAYLOAD = "recent_codepoints";

    private final SharedPreferences preferences;

    public EmojiRecentStore(Context context) {
        preferences = Objects.requireNonNull(context, "context")
                .getApplicationContext()
                .getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }

    public EmojiRecents load() {
        try {
            return EmojiRecentCodec.decode(
                    preferences.getInt(VERSION, -1),
                    preferences.getString(PAYLOAD, ""));
        } catch (RuntimeException unavailable) {
            return EmojiRecents.empty();
        }
    }

    public void save(EmojiRecents recents) {
        String payload = EmojiRecentCodec.encode(Objects.requireNonNull(recents, "recents"));
        preferences.edit()
                .putInt(VERSION, EmojiRecentCodec.FORMAT_VERSION)
                .putString(PAYLOAD, payload)
                .apply();
    }

    public void clear() {
        preferences.edit().clear().apply();
    }
}
