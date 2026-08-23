package com.opentypeless.android.keyboard.emoji;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Small, immutable Unicode Emoji 15.1 inventory for the latency-critical IME panel. */
public final class EmojiCatalog {
    public enum Category {
        RECENT,
        SMILEYS,
        PEOPLE,
        ANIMALS,
        FOOD,
        ACTIVITIES,
        TRAVEL,
        OBJECTS,
        SYMBOLS
    }

    private static final List<String> SMILEYS = List.of(
            "😀", "😃", "😄", "😁", "😆", "😅", "😂",
            "🤣", "😊", "😇", "🙂", "🙃", "😉", "😌",
            "😍", "🥰", "😘", "😗", "😙", "😚", "😋");
    private static final List<String> PEOPLE = List.of(
            "👋", "🤚", "🖐️", "✋", "🖖", "🫱", "🫲",
            "🫳", "🫴", "👌", "🤌", "🤏", "✌️", "🤞",
            "🫰", "🤟", "🤘", "🤙", "👈", "👉", "👆");
    private static final List<String> ANIMALS = List.of(
            "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻",
            "🐼", "🐻‍❄️", "🐨", "🐯", "🦁", "🐮", "🐷",
            "🐸", "🐵", "🙈", "🙉", "🙊", "🐔", "🐧");
    private static final List<String> FOOD = List.of(
            "🍎", "🍐", "🍊", "🍋", "🍌", "🍉", "🍇",
            "🍓", "🫐", "🍈", "🍒", "🍑", "🥭", "🍍",
            "🥥", "🥝", "🍅", "🍆", "🥑", "🥦", "🥬");
    private static final List<String> ACTIVITIES = List.of(
            "⚽", "🏀", "🏈", "⚾", "🥎", "🎾", "🏐",
            "🏉", "🥏", "🎱", "🪀", "🏓", "🏸", "🏒",
            "🏑", "🥍", "🏏", "🪃", "🥅", "⛳", "🪁");
    private static final List<String> TRAVEL = List.of(
            "🚗", "🚕", "🚙", "🚌", "🚎", "🏎️", "🚓",
            "🚑", "🚒", "🚐", "🛻", "🚚", "🚛", "🚜",
            "🛵", "🏍️", "🚲", "🛴", "🚆", "✈️", "🚀");
    private static final List<String> OBJECTS = List.of(
            "⌚", "📱", "💻", "⌨️", "🖥️", "🖨️", "🖱️",
            "💽", "💾", "💿", "📷", "🎥", "📞", "☎️",
            "📺", "📻", "🎙️", "⏰", "🔦", "💡", "📚");
    private static final List<String> SYMBOLS = List.of(
            "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤",
            "🤍", "🤎", "💔", "❣️", "💕", "💞", "💓",
            "💗", "💖", "💘", "💝", "💟", "💌", "⭐");
    private static final List<Category> BROWSE_CATEGORIES = List.of(
            Category.SMILEYS,
            Category.PEOPLE,
            Category.ANIMALS,
            Category.FOOD,
            Category.ACTIVITIES,
            Category.TRAVEL,
            Category.OBJECTS,
            Category.SYMBOLS);
    private static final Map<Category, List<String>> INVENTORY = buildInventory();
    private static final Set<String> KNOWN = buildKnownSet();

    private EmojiCatalog() {}

    public static List<Category> browseCategories() {
        return BROWSE_CATEGORIES;
    }

    public static List<String> emoji(Category category) {
        Objects.requireNonNull(category, "category");
        if (category == Category.RECENT) return List.of();
        List<String> values = INVENTORY.get(category);
        if (values == null) throw new IllegalArgumentException("unknown category");
        return values;
    }

    public static boolean contains(String emoji) {
        return emoji != null && KNOWN.contains(emoji);
    }

    public static int size() {
        return KNOWN.size();
    }

    private static Map<Category, List<String>> buildInventory() {
        EnumMap<Category, List<String>> values = new EnumMap<>(Category.class);
        values.put(Category.SMILEYS, SMILEYS);
        values.put(Category.PEOPLE, PEOPLE);
        values.put(Category.ANIMALS, ANIMALS);
        values.put(Category.FOOD, FOOD);
        values.put(Category.ACTIVITIES, ACTIVITIES);
        values.put(Category.TRAVEL, TRAVEL);
        values.put(Category.OBJECTS, OBJECTS);
        values.put(Category.SYMBOLS, SYMBOLS);
        return Map.copyOf(values);
    }

    private static Set<String> buildKnownSet() {
        HashSet<String> values = new HashSet<>();
        for (Category category : BROWSE_CATEGORIES) {
            for (String emoji : INVENTORY.get(category)) {
                if (emoji == null || emoji.isEmpty() || !values.add(emoji)) {
                    throw new IllegalStateException("invalid or duplicate emoji inventory entry");
                }
            }
        }
        return Set.copyOf(values);
    }
}
