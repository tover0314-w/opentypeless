package com.opentypeless.android.keyboard.emoji;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.opentypeless.android.R;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Capability-free categorized Emoji renderer. It never receives an editor or persistence store. */
public final class KeyboardEmojiPanel {
    public interface Listener {
        void onEmojiSelected(String emoji);

        void onClose();
    }

    public static final String ROOT_TAG = "opentypeless-emoji-panel";
    public static final String GRID_TAG = "opentypeless-emoji-grid";
    public static final String CLOSE_TAG = "opentypeless-emoji-close";
    public static final int MINIMUM_TOUCH_TARGET_DP = 48;
    private static final int PANEL_MINIMUM_HEIGHT_DP = 252;

    private final Context context;
    private final Listener listener;
    private final LinearLayout root;
    private final LinearLayout categoryStrip;
    private final GridLayout grid;
    private final TextView empty;
    private final Button close;
    private final Map<EmojiCatalog.Category, Button> categoryButtons =
            new EnumMap<>(EmojiCatalog.Category.class);
    private final int columns;
    private EmojiRecents recents = EmojiRecents.empty();
    private boolean recentsVisible;
    private EmojiCatalog.Category selected = EmojiCatalog.Category.SMILEYS;

    public KeyboardEmojiPanel(Context context, Listener listener) {
        this.context = Objects.requireNonNull(context, "context");
        this.listener = Objects.requireNonNull(listener, "listener");
        columns = context.getResources().getConfiguration().screenWidthDp < 390 ? 6 : 7;

        root = new LinearLayout(context);
        root.setTag(ROOT_TAG);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setMinimumHeight(dp(PANEL_MINIMUM_HEIGHT_DP));
        root.setPadding(dp(4), dp(2), dp(4), dp(4));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(context);
        title.setText(R.string.ime_emoji_title);
        title.setTextColor(context.getColor(R.color.ime_on_surface));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        title.setPadding(dp(8), 0, dp(8), 0);
        header.addView(title, new LinearLayout.LayoutParams(
                0, dp(MINIMUM_TOUCH_TARGET_DP), 1f));
        close = actionButton(
                context.getString(R.string.ime_emoji_close),
                context.getString(R.string.ime_cd_emoji_close),
                CLOSE_TAG,
                ignored -> listener.onClose());
        header.addView(close, new LinearLayout.LayoutParams(
                dp(64), dp(MINIMUM_TOUCH_TARGET_DP)));
        root.addView(header, matchWrap());

        HorizontalScrollView categories = new HorizontalScrollView(context);
        categories.setHorizontalScrollBarEnabled(false);
        categories.setFillViewport(true);
        categoryStrip = new LinearLayout(context);
        categoryStrip.setOrientation(LinearLayout.HORIZONTAL);
        addCategory(EmojiCatalog.Category.RECENT, "◷", R.string.ime_emoji_category_recent);
        addCategory(EmojiCatalog.Category.SMILEYS, "😀", R.string.ime_emoji_category_smileys);
        addCategory(EmojiCatalog.Category.PEOPLE, "👋", R.string.ime_emoji_category_people);
        addCategory(EmojiCatalog.Category.ANIMALS, "🐻", R.string.ime_emoji_category_animals);
        addCategory(EmojiCatalog.Category.FOOD, "🍎", R.string.ime_emoji_category_food);
        addCategory(EmojiCatalog.Category.ACTIVITIES, "⚽", R.string.ime_emoji_category_activities);
        addCategory(EmojiCatalog.Category.TRAVEL, "🚗", R.string.ime_emoji_category_travel);
        addCategory(EmojiCatalog.Category.OBJECTS, "💡", R.string.ime_emoji_category_objects);
        addCategory(EmojiCatalog.Category.SYMBOLS, "❤️", R.string.ime_emoji_category_symbols);
        categories.addView(categoryStrip, wrapMatch());
        root.addView(categories, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(MINIMUM_TOUCH_TARGET_DP)));

        grid = new GridLayout(context);
        grid.setTag(GRID_TAG);
        grid.setColumnCount(columns);
        grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        grid.setUseDefaultMargins(false);
        root.addView(grid, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        empty = new TextView(context);
        empty.setText(R.string.ime_emoji_recent_empty);
        empty.setTextColor(context.getColor(R.color.ime_on_surface_variant));
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        empty.setGravity(Gravity.CENTER);
        empty.setMinHeight(dp(MINIMUM_TOUCH_TARGET_DP * 2));
        root.addView(empty, matchWrap());
        // Do not allocate a 21-button page during ordinary IME creation. The first explicit open
        // renders exactly one bounded category; QWERTY/Rime hot-path setup remains data-free.
        clear();
    }

    public LinearLayout root() {
        return root;
    }

    public Button closeButton() {
        return close;
    }

    public Button categoryButton(EmojiCatalog.Category category) {
        return categoryButtons.get(Objects.requireNonNull(category, "category"));
    }

    public GridLayout grid() {
        return grid;
    }

    public EmojiCatalog.Category selectedCategory() {
        return selected;
    }

    public void render(EmojiRecents nextRecents, boolean allowRecents) {
        recents = Objects.requireNonNull(nextRecents, "nextRecents");
        recentsVisible = allowRecents;
        Button recent = categoryButtons.get(EmojiCatalog.Category.RECENT);
        recent.setVisibility(allowRecents ? View.VISIBLE : View.GONE);
        if (!allowRecents && selected == EmojiCatalog.Category.RECENT) {
            selected = EmojiCatalog.Category.SMILEYS;
        } else if (allowRecents && !recents.isEmpty()) {
            selected = EmojiCatalog.Category.RECENT;
        }
        renderSelected();
    }

    public void recordSelection(EmojiRecents nextRecents) {
        recents = Objects.requireNonNull(nextRecents, "nextRecents");
        if (selected == EmojiCatalog.Category.RECENT) renderSelected();
    }

    public void clear() {
        recents = EmojiRecents.empty();
        recentsVisible = false;
        selected = EmojiCatalog.Category.SMILEYS;
        grid.removeAllViews();
        empty.setVisibility(View.GONE);
    }

    private void addCategory(EmojiCatalog.Category category, String label, int description) {
        Button button = actionButton(
                label,
                context.getString(description),
                "opentypeless-emoji-category-" + category.name().toLowerCase(),
                ignored -> selectCategory(category));
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        categoryButtons.put(category, button);
        categoryStrip.addView(button, new LinearLayout.LayoutParams(
                dp(MINIMUM_TOUCH_TARGET_DP), dp(MINIMUM_TOUCH_TARGET_DP)));
    }

    private void selectCategory(EmojiCatalog.Category category) {
        if (category == EmojiCatalog.Category.RECENT && !recentsVisible) return;
        selected = category;
        renderSelected();
    }

    private void renderSelected() {
        List<String> entries = selected == EmojiCatalog.Category.RECENT
                ? recents.entries()
                : EmojiCatalog.emoji(selected);
        grid.removeAllViews();
        for (String emoji : entries) grid.addView(emojiButton(emoji), emojiCell());
        boolean showEmpty = selected == EmojiCatalog.Category.RECENT && entries.isEmpty();
        empty.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
        grid.setVisibility(showEmpty ? View.GONE : View.VISIBLE);
        for (Map.Entry<EmojiCatalog.Category, Button> entry : categoryButtons.entrySet()) {
            entry.getValue().setSelected(entry.getKey() == selected);
        }
    }

    private Button emojiButton(String emoji) {
        Button button = actionButton(
                emoji,
                context.getString(R.string.ime_cd_insert_emoji, emoji),
                "opentypeless-emoji-" + codePointTag(emoji),
                ignored -> listener.onEmojiSelected(emoji));
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        button.setPadding(0, 0, 0, 0);
        return button;
    }

    private GridLayout.LayoutParams emojiCell() {
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = dp(MINIMUM_TOUCH_TARGET_DP);
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        return params;
    }

    private Button actionButton(
            String label,
            String description,
            String tag,
            View.OnClickListener listener) {
        Button button = new Button(context);
        button.setTag(tag);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(context.getColor(R.color.ime_on_surface));
        button.setContentDescription(description);
        button.setBackgroundResource(R.drawable.ime_key_background);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(MINIMUM_TOUCH_TARGET_DP));
        button.setMinimumHeight(dp(MINIMUM_TOUCH_TARGET_DP));
        button.setPadding(dp(4), 0, dp(4), 0);
        button.setOnClickListener(listener);
        return button;
    }

    private static String codePointTag(String emoji) {
        ArrayList<String> values = new ArrayList<>();
        emoji.codePoints().forEach(value -> values.add(Integer.toHexString(value)));
        return String.join("-", values);
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams wrapMatch() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
