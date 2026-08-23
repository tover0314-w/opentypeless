package com.opentypeless.android.keyboard.emoji;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Bounded v1 payload codec. The Android adapter stores version and payload separately. */
public final class EmojiRecentCodec {
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_PAYLOAD_UTF16_UNITS = 4_096;
    private static final int MAX_CODE_POINTS_PER_ENTRY = 16;

    private EmojiRecentCodec() {}

    public static String encode(EmojiRecents recents) {
        Objects.requireNonNull(recents, "recents");
        ArrayList<String> encoded = new ArrayList<>();
        for (String emoji : recents.entries()) encoded.add(encodeEntry(emoji));
        String payload = String.join(",", encoded);
        if (payload.length() > MAX_PAYLOAD_UTF16_UNITS) {
            throw new IllegalStateException("bounded emoji payload exceeded");
        }
        return payload;
    }

    public static EmojiRecents decode(int version, String payload) {
        if (version != FORMAT_VERSION || payload == null || payload.isEmpty()) {
            return EmojiRecents.empty();
        }
        if (payload.length() > MAX_PAYLOAD_UTF16_UNITS) return EmojiRecents.empty();
        String[] encoded = payload.split(",", EmojiRecents.MAX_ENTRIES + 1);
        if (encoded.length > EmojiRecents.MAX_ENTRIES) return EmojiRecents.empty();
        ArrayList<String> decoded = new ArrayList<>(encoded.length);
        try {
            for (String entry : encoded) decoded.add(decodeEntry(entry));
        } catch (IllegalArgumentException malformed) {
            return EmojiRecents.empty();
        }
        return EmojiRecents.fromStored(decoded);
    }

    private static String encodeEntry(String emoji) {
        if (!EmojiCatalog.contains(emoji)) {
            throw new IllegalArgumentException("emoji is outside the pinned catalog");
        }
        ArrayList<String> codePoints = new ArrayList<>();
        emoji.codePoints().forEach(codePoint -> codePoints.add(
                Integer.toHexString(codePoint).toUpperCase(Locale.ROOT)));
        return String.join("-", codePoints);
    }

    private static String decodeEntry(String encoded) {
        if (encoded.isEmpty()) throw new IllegalArgumentException("empty entry");
        String[] parts = encoded.split("-", MAX_CODE_POINTS_PER_ENTRY + 1);
        if (parts.length > MAX_CODE_POINTS_PER_ENTRY) {
            throw new IllegalArgumentException("entry is too deep");
        }
        StringBuilder decoded = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 6) {
                throw new IllegalArgumentException("invalid code point token");
            }
            int codePoint = Integer.parseInt(part, 16);
            if (!Character.isValidCodePoint(codePoint)
                    || (codePoint >= Character.MIN_SURROGATE
                    && codePoint <= Character.MAX_SURROGATE)) {
                throw new IllegalArgumentException("invalid code point");
            }
            decoded.appendCodePoint(codePoint);
        }
        return decoded.toString();
    }
}
