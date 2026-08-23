package com.opentypeless.android.keyboard.latin;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.opentypeless.android.R;
import com.opentypeless.android.keyboard.feedback.KeyboardFeedback;
import com.opentypeless.android.keyboard.field.KeyboardFieldProfile;
import com.opentypeless.android.keyboard.switching.KeyboardEngineSelection;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Capability-free four-row QWERTY and paged-symbol view for KBD-002/KBD-003/KBD-009. */
public final class LatinKeyboardLayout {
    public static final String ROOT_TAG = "opentypeless-latin-qwerty";
    public static final String SHIFT_TAG = "opentypeless-latin-shift";

    // KBD-009 portrait sizing slice: match the compact Xiaohe geometry while keeping the
    // existing four-row structure and the 48dp minimum touch-height contract.
    private static final int KEY_HEIGHT_DP = 50;
    private static final int KEY_SIDE_MARGIN_DP = 1;
    private static final int KEY_VERTICAL_MARGIN_DP = 2;
    private static final float LETTER_KEY_TEXT_SIZE_SP = 22f;
    private static final float FUNCTION_KEY_TEXT_SIZE_SP = 16f;

    @FunctionalInterface
    public interface KeyFactory {
        Button create(String label, String contentDescription, float weight, Runnable action);
    }

    public interface Listener {
        void insertText(String text);

        void deleteBackward();

        void performEnter();

        void importRimeResources();

        void showKeyboardPicker();

        void switchInputEngine();
    }

    private static final String[] LETTER_ROWS = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
    private static final String[] LONG_PRESS_ROWS = {"1234567890", "@#$%&-+()", "*\"':;!?"};
    private static final String[][] SYMBOL_ROWS_PRIMARY = {
        {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
        {"@", "#", "$", "%", "&", "-", "+", "(", ")", "/"},
        {"*", "\"", "'", ":", ";", "!", "?", ",", "."}
    };
    private static final String[][] SYMBOL_ROWS_SECONDARY = {
        {"~", "`", "|", "•", "√", "π", "÷", "×", "§", "∆"},
        {"€", "£", "¥", "₩", "¢", "^", "°", "=", "{", "}"},
        {"\\", "_", "[", "]", "<", ">", "…", "¿", "¡"}
    };
    private static final String[][] PHONE_ROWS = {
        {"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9", "+", "0", "*", "#"}
    };
    private static final String[][] NUMBER_ROWS = {
        {"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9", "-", "0", "."}
    };
    private static final String[][] DATE_ROWS = {
        {"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9", "/", "0", "-", "."}
    };

    private final Context context;
    private final KeyFactory keyFactory;
    private final Listener listener;
    private final KeyboardFeedback feedback;
    private final LatinKeyboardState state = new LatinKeyboardState();
    private final LinearLayout root;
    private final LinearLayout firstRow;
    private final LinearLayout secondRow;
    private final LinearLayout thirdRow;
    private final LinearLayout bottomRow;
    private final Map<Character, Button> letters = new LinkedHashMap<>();
    private final Map<Character, DownFlickGesture> letterFlickGestures = new LinkedHashMap<>();
    private final Map<String, Button> symbols = new LinkedHashMap<>();
    private final Button shiftButton;
    private final Button symbolsToggleButton;
    private final Button symbolPageButton;
    private final Button spaceButton;
    private final Button deleteButton;
    private final Button enterButton;
    private final Button switchKeyboardButton;
    private final Button engineSwitchButton;
    private final Button commaButton;
    private final Button periodButton;
    private final BoundedDeleteRepeater deleteRepeater;
    private final List<Button> profileShortcutButtons = new ArrayList<>();
    private KeyboardFieldProfile fieldProfile = KeyboardFieldProfile.GENERAL;
    private boolean inputEnabled = true;
    private boolean suppressDeleteClick;

    public LatinKeyboardLayout(Context context, KeyFactory keyFactory, Listener listener) {
        this(context, keyFactory, listener, KeyboardFeedback.NONE);
    }

    public LatinKeyboardLayout(
            Context context,
            KeyFactory keyFactory,
            Listener listener,
            KeyboardFeedback feedback) {
        this(context, keyFactory, listener, feedback, null);
    }

    LatinKeyboardLayout(
            Context context,
            KeyFactory keyFactory,
            Listener listener,
            KeyboardFeedback feedback,
            BoundedDeleteRepeater.Scheduler repeatScheduler) {
        this.context = Objects.requireNonNull(context, "context");
        this.keyFactory = Objects.requireNonNull(keyFactory, "keyFactory");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setTag(ROOT_TAG);

        firstRow = row();
        secondRow = row();
        thirdRow = row();
        shiftButton = createKey(
                context.getString(R.string.ime_key_shift),
                context.getString(R.string.ime_cd_shift),
                1.45f,
                this::pressShift,
                false);
        shiftButton.setTag(SHIFT_TAG);
        deleteButton = createKey(
                context.getString(R.string.ime_key_delete),
                context.getString(R.string.ime_cd_delete),
                1.45f,
                this::deleteOnceUnlessSuppressed,
                false);
        BoundedDeleteRepeater.Scheduler scheduler = repeatScheduler == null
                ? (action, delayMillis) -> {
                    deleteButton.postDelayed(action, delayMillis);
                    return () -> deleteButton.removeCallbacks(action);
                }
                : repeatScheduler;
        deleteRepeater = new BoundedDeleteRepeater(scheduler);
        configureDeleteRepeat();
        root.addView(firstRow, matchWrap());
        root.addView(secondRow, matchWrap());
        root.addView(thirdRow, matchWrap());

        bottomRow = row();
        symbolsToggleButton = createKey(
                context.getString(R.string.ime_key_symbols),
                context.getString(R.string.ime_cd_open_symbols),
                1.15f,
                this::toggleSymbols,
                false);
        addWeighted(bottomRow, symbolsToggleButton, 1.15f);
        symbolPageButton = createKey(
                context.getString(R.string.ime_key_symbols_next_page),
                context.getString(R.string.ime_cd_symbols_next_page),
                1.15f,
                this::toggleSymbolPage,
                false);
        addWeighted(bottomRow, symbolPageButton, 1.15f);
        for (int index = 0; index < 3; index++) {
            int shortcutIndex = index;
            Button shortcut = createKey("", "", .9f,
                    () -> emitProfileShortcut(shortcutIndex), false);
            shortcut.setVisibility(View.GONE);
            profileShortcutButtons.add(shortcut);
            addWeighted(bottomRow, shortcut, .9f);
        }
        switchKeyboardButton = createKey(
                context.getString(R.string.ime_key_import_rime),
                context.getString(R.string.ime_cd_import_rime),
                1.2f,
                listener::importRimeResources,
                false);
        switchKeyboardButton.setOnLongClickListener(ignored -> consumeKeyboardPickerLongPress());
        addWeighted(bottomRow, switchKeyboardButton, 1.2f);
        engineSwitchButton = createKey(
                context.getString(R.string.ime_key_engine_latin),
                context.getString(R.string.ime_cd_engine_latin),
                1.2f,
                listener::switchInputEngine,
                false);
        engineSwitchButton.setOnLongClickListener(ignored -> consumeKeyboardPickerLongPress());
        engineSwitchButton.setVisibility(View.GONE);
        addWeighted(bottomRow, engineSwitchButton, 1.2f);
        commaButton = createKey(
                ",",
                context.getString(R.string.ime_cd_comma),
                .8f,
                () -> listener.insertText(","),
                false);
        addWeighted(bottomRow, commaButton, .8f);
        spaceButton = createKey(
                context.getString(R.string.ime_key_space),
                context.getString(R.string.ime_cd_space),
                3.6f,
                () -> listener.insertText(" "),
                false);
        addWeighted(bottomRow, spaceButton, 3.6f);
        periodButton = createKey(
                ".",
                context.getString(R.string.ime_cd_period),
                .8f,
                () -> listener.insertText("."),
                false);
        addWeighted(bottomRow, periodButton, .8f);
        enterButton = createKey(
                context.getString(R.string.ime_key_enter),
                context.getString(R.string.ime_cd_enter),
                1.2f,
                listener::performEnter,
                false);
        addWeighted(bottomRow, enterButton, 1.2f);
        root.addView(bottomRow, matchWrap());
        refreshLayer();
    }

    public LinearLayout root() {
        return root;
    }

    public Button shiftButton() {
        return shiftButton;
    }

    public Button symbolsToggleButton() {
        return symbolsToggleButton;
    }

    public Button symbolPageButton() {
        return symbolPageButton;
    }

    public Button letterButton(char lowercaseAscii) {
        Button button = letters.get(lowercaseAscii);
        if (button == null) throw new IllegalArgumentException("unknown letter");
        return button;
    }

    public Button spaceButton() {
        return spaceButton;
    }

    public Button deleteButton() {
        return deleteButton;
    }

    public Button enterButton() {
        return enterButton;
    }

    public Button switchKeyboardButton() {
        return switchKeyboardButton;
    }

    public Button engineSwitchButton() {
        return engineSwitchButton;
    }

    public Button commaButton() {
        return commaButton;
    }

    public Button periodButton() {
        return periodButton;
    }

    public Button symbolButton(String symbol) {
        Button button = symbols.get(symbol);
        if (button == null) throw new IllegalArgumentException("unknown or hidden symbol");
        return button;
    }

    public LatinKeyboardState.ShiftMode shiftMode() {
        return state.shiftMode();
    }

    public LatinKeyboardState.Layer layer() {
        return state.layer();
    }

    public KeyboardFieldProfile fieldProfile() {
        return fieldProfile;
    }

    public Button profileShortcutButton(int index) {
        if (index < 0 || index >= profileShortcutButtons.size()) {
            throw new IllegalArgumentException("shortcut index out of range");
        }
        return profileShortcutButtons.get(index);
    }

    public void setFieldProfile(KeyboardFieldProfile profile) {
        KeyboardFieldProfile next = Objects.requireNonNull(profile, "profile");
        if (fieldProfile == next) return;
        fieldProfile = next;
        state.resetToLetters();
        refreshLayer();
    }

    public void setInputEnabled(boolean enabled) {
        if (!enabled) cancelTransientGestures();
        inputEnabled = enabled;
        shiftButton.setEnabled(enabled);
        symbolsToggleButton.setEnabled(enabled);
        symbolPageButton.setEnabled(enabled);
        spaceButton.setEnabled(enabled);
        deleteButton.setEnabled(enabled);
        enterButton.setEnabled(enabled);
        switchKeyboardButton.setEnabled(enabled);
        engineSwitchButton.setEnabled(enabled);
        commaButton.setEnabled(enabled);
        periodButton.setEnabled(enabled);
        for (Button button : letters.values()) button.setEnabled(enabled);
        for (Button button : symbols.values()) button.setEnabled(enabled);
        for (Button button : profileShortcutButtons) button.setEnabled(enabled);
    }

    /** Stops touch-owned work when the editor or IME view leaves its active lifecycle. */
    public void cancelTransientGestures() {
        deleteRepeater.stop();
        deleteButton.setPressed(false);
        for (Map.Entry<Character, DownFlickGesture> entry : letterFlickGestures.entrySet()) {
            entry.getValue().cancel();
            Button button = letters.get(entry.getKey());
            if (button != null) {
                button.cancelLongPress();
                button.setPressed(false);
            }
        }
    }

    public void setEngineSelection(KeyboardEngineSelection selection) {
        KeyboardEngineSelection safe = Objects.requireNonNull(selection, "selection");
        state.resetToLetters();
        // Keep one stable language slot: a verified local Rime engine gets the short press;
        // otherwise the slot opens the explicit local-import flow. System IME selection remains
        // available only through the long press, so the control never pretends external Xiaohe is
        // the built-in engine.
        switchKeyboardButton.setVisibility(safe.hasAlternative() ? View.GONE : View.VISIBLE);
        engineSwitchButton.setVisibility(safe.hasAlternative() ? View.VISIBLE : View.GONE);
        boolean latin = safe.active() == KeyboardEngineSelection.Engine.LATIN;
        engineSwitchButton.setText(latin
                ? R.string.ime_key_engine_latin
                : R.string.ime_key_engine_rime);
        engineSwitchButton.setContentDescription(context.getString(latin
                ? R.string.ime_cd_engine_latin
                : R.string.ime_cd_engine_rime));
        refreshLayer();
    }

    private void populateLetterRow(
            LinearLayout row, String rowLetters, String longPressSymbols, float sideWeight) {
        if (rowLetters.length() != longPressSymbols.length()) {
            throw new IllegalStateException("letter and long-press rows must have equal length");
        }
        if (sideWeight > 0f) addSpacer(row, sideWeight);
        for (int index = 0; index < rowLetters.length(); index++) {
            addLetter(row, rowLetters.charAt(index), longPressSymbols.substring(index, index + 1));
        }
        if (sideWeight > 0f) addSpacer(row, sideWeight);
    }

    private void addLetter(LinearLayout row, char letter, String longPressSymbol) {
        Button button = createKey(
                Character.toString(letter),
                context.getString(
                        R.string.ime_cd_letter_with_long_press,
                        Character.toString(letter),
                        longPressSymbol),
                1f,
                () -> {
                    listener.insertText(state.consumeLetter(letter));
                    refreshLabels();
                },
                true);
        button.setTag("opentypeless-latin-letter-" + letter);
        button.setSingleLine(false);
        button.setMaxLines(2);
        button.setIncludeFontPadding(false);
        button.setLineSpacing(-dp(4), 1f);
        button.setGravity(Gravity.CENTER);
        setLetterDisplay(button, Character.toString(letter), longPressSymbol);
        DownFlickGesture flickGesture = new DownFlickGesture(Math.max(
                dp(12), ViewConfiguration.get(context).getScaledTouchSlop()));
        configureLetterFlick(button, longPressSymbol, flickGesture);
        button.setOnLongClickListener(ignored -> {
            if (!flickGesture.commitLongPress()) return true;
            feedback.onLongPress(button);
            listener.insertText(longPressSymbol);
            return true;
        });
        letters.put(letter, button);
        letterFlickGestures.put(letter, flickGesture);
        addWeighted(row, button, 1f);
    }

    @SuppressLint("ClickableViewAccessibility") // Tap delegates to Button.performClick.
    private void configureLetterFlick(
            Button button, String alternate, DownFlickGesture gesture) {
        button.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    if (!inputEnabled) return true;
                    gesture.down(event.getX(), event.getY());
                    return false;
                }
                case MotionEvent.ACTION_MOVE -> {
                    boolean consume = gesture.move(event.getX(), event.getY());
                    if (consume) {
                        button.cancelLongPress();
                        button.setPressed(false);
                    }
                    return consume;
                }
                case MotionEvent.ACTION_UP -> {
                    DownFlickGesture.ReleaseAction action = gesture.up();
                    if (action == DownFlickGesture.ReleaseAction.DELEGATE_TAP) return false;
                    button.cancelLongPress();
                    button.setPressed(false);
                    if (action == DownFlickGesture.ReleaseAction.COMMIT_ALTERNATE
                            && inputEnabled) {
                        feedback.onPress(button);
                        listener.insertText(alternate);
                    }
                    return true;
                }
                case MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> {
                    gesture.cancel();
                    button.cancelLongPress();
                    button.setPressed(false);
                    return false;
                }
                case MotionEvent.ACTION_POINTER_DOWN -> {
                    gesture.cancel();
                    button.cancelLongPress();
                    button.setPressed(false);
                    return true;
                }
                default -> {
                    return false;
                }
            }
        });
    }

    private boolean consumeKeyboardPickerLongPress() {
        View source = engineSwitchButton.getVisibility() == View.VISIBLE
                ? engineSwitchButton
                : switchKeyboardButton;
        feedback.onLongPress(source);
        listener.showKeyboardPicker();
        return true;
    }

    private void addSymbol(LinearLayout row, String symbol) {
        Button button = createKey(symbol, symbol, 1f, () -> listener.insertText(symbol), true);
        button.setTag("opentypeless-symbol-" + symbol);
        button.setEnabled(inputEnabled);
        symbols.put(symbol, button);
        addWeighted(row, button, 1f);
    }

    private void toggleSymbols() {
        state.pressSymbolsToggle();
        refreshLayer();
    }

    private void toggleSymbolPage() {
        state.pressSymbolPage();
        refreshLayer();
    }

    private void emitProfileShortcut(int index) {
        String[] shortcuts = shortcutsFor(fieldProfile);
        if (index < 0 || index >= shortcuts.length) {
            throw new IllegalStateException("hidden profile shortcut invoked");
        }
        listener.insertText(shortcuts[index]);
    }

    private void refreshLayer() {
        firstRow.removeAllViews();
        secondRow.removeAllViews();
        thirdRow.removeAllViews();
        letters.clear();
        letterFlickGestures.clear();
        symbols.clear();
        if (fieldProfile.usesNumericPanel()) {
            String[][] rows = switch (fieldProfile) {
                case PHONE -> PHONE_ROWS;
                case NUMBER -> NUMBER_ROWS;
                case DATE -> DATE_ROWS;
                default -> throw new IllegalStateException("numeric profile mismatch");
            };
            for (String symbol : rows[0]) addSymbol(firstRow, symbol);
            for (String symbol : rows[1]) addSymbol(secondRow, symbol);
            for (String symbol : rows[2]) addSymbol(thirdRow, symbol);
            addWeighted(thirdRow, deleteButton, 1.45f);
            symbolsToggleButton.setVisibility(View.GONE);
            symbolPageButton.setVisibility(View.GONE);
            configureProfileShortcuts(new String[0]);
            spaceButton.setVisibility(View.GONE);
            commaButton.setVisibility(View.GONE);
            periodButton.setVisibility(View.GONE);
        } else if (state.layer() == LatinKeyboardState.Layer.LETTERS) {
            populateLetterRow(firstRow, LETTER_ROWS[0], LONG_PRESS_ROWS[0], 0f);
            populateLetterRow(secondRow, LETTER_ROWS[1], LONG_PRESS_ROWS[1], 0.5f);
            addWeighted(thirdRow, shiftButton, 1.45f);
            populateLetterRow(thirdRow, LETTER_ROWS[2], LONG_PRESS_ROWS[2], 0f);
            addWeighted(thirdRow, deleteButton, 1.45f);
            symbolPageButton.setVisibility(View.GONE);
            symbolsToggleButton.setVisibility(View.VISIBLE);
            symbolsToggleButton.setText(R.string.ime_key_symbols);
            symbolsToggleButton.setContentDescription(
                    context.getString(R.string.ime_cd_open_symbols));
            refreshLabels();
            configureProfileShortcuts(shortcutsFor(fieldProfile));
            spaceButton.setVisibility(View.VISIBLE);
            commaButton.setVisibility(View.VISIBLE);
            periodButton.setVisibility(View.VISIBLE);
        } else {
            String[][] rows = state.layer() == LatinKeyboardState.Layer.SYMBOLS_PRIMARY
                    ? SYMBOL_ROWS_PRIMARY
                    : SYMBOL_ROWS_SECONDARY;
            for (String symbol : rows[0]) addSymbol(firstRow, symbol);
            for (String symbol : rows[1]) addSymbol(secondRow, symbol);
            for (String symbol : rows[2]) addSymbol(thirdRow, symbol);
            addWeighted(thirdRow, deleteButton, 1.45f);
            symbolPageButton.setVisibility(View.VISIBLE);
            symbolsToggleButton.setVisibility(View.VISIBLE);
            symbolPageButton.setText(state.layer() == LatinKeyboardState.Layer.SYMBOLS_PRIMARY
                    ? R.string.ime_key_symbols_next_page
                    : R.string.ime_key_symbols_previous_page);
            symbolPageButton.setContentDescription(context.getString(
                    state.layer() == LatinKeyboardState.Layer.SYMBOLS_PRIMARY
                            ? R.string.ime_cd_symbols_next_page
                            : R.string.ime_cd_symbols_previous_page));
            symbolsToggleButton.setText(R.string.ime_key_letters);
            symbolsToggleButton.setContentDescription(
                    context.getString(R.string.ime_cd_return_to_letters));
            configureProfileShortcuts(new String[0]);
            spaceButton.setVisibility(View.VISIBLE);
            commaButton.setVisibility(View.VISIBLE);
            periodButton.setVisibility(View.VISIBLE);
        }
        root.setContentDescription(context.getString(profileDescription(fieldProfile)));
        setInputEnabled(inputEnabled);
    }

    private void configureProfileShortcuts(String[] shortcuts) {
        for (int index = 0; index < profileShortcutButtons.size(); index++) {
            Button button = profileShortcutButtons.get(index);
            if (index >= shortcuts.length) {
                button.setText("");
                button.setContentDescription("");
                button.setVisibility(View.GONE);
                continue;
            }
            button.setText(shortcuts[index]);
            button.setContentDescription(shortcuts[index]);
            button.setVisibility(View.VISIBLE);
        }
    }

    private static String[] shortcutsFor(KeyboardFieldProfile profile) {
        return switch (profile) {
            case EMAIL -> new String[] {"@"};
            case URI -> new String[] {"/", ":"};
            default -> new String[0];
        };
    }

    private static int profileDescription(KeyboardFieldProfile profile) {
        return switch (profile) {
            case GENERAL -> R.string.ime_cd_keyboard_profile_general;
            case EMAIL -> R.string.ime_cd_keyboard_profile_email;
            case URI -> R.string.ime_cd_keyboard_profile_uri;
            case PHONE -> R.string.ime_cd_keyboard_profile_phone;
            case NUMBER -> R.string.ime_cd_keyboard_profile_number;
            case DATE -> R.string.ime_cd_keyboard_profile_date;
            case PASSWORD -> R.string.ime_cd_keyboard_profile_password;
        };
    }

    private void pressShift() {
        state.pressShift(SystemClock.uptimeMillis());
        refreshLabels();
    }

    private void refreshLabels() {
        for (Map.Entry<Character, Button> entry : letters.entrySet()) {
            String label = state.displayLetter(entry.getKey());
            Button button = entry.getValue();
            String alternate = longPressSymbolFor(entry.getKey());
            setLetterDisplay(button, label, alternate);
            button.setContentDescription(context.getString(
                    R.string.ime_cd_letter_with_long_press,
                    label,
                    alternate));
        }
        boolean caps = state.shiftMode() == LatinKeyboardState.ShiftMode.CAPS_LOCKED;
        shiftButton.setText(context.getString(
                caps ? R.string.ime_key_caps_lock : R.string.ime_key_shift));
        shiftButton.setSelected(state.uppercase());
        shiftButton.setContentDescription(context.getString(
                caps ? R.string.ime_cd_caps_lock : R.string.ime_cd_shift));
    }

    private void setLetterDisplay(Button button, String letter, String alternate) {
        SpannableString display = new SpannableString(alternate + "\n" + letter);
        display.setSpan(
                new RelativeSizeSpan(0.46f),
                0,
                alternate.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        display.setSpan(
                new ForegroundColorSpan(context.getColor(R.color.ime_on_surface_variant)),
                0,
                alternate.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        button.setText(display);
    }

    private static String longPressSymbolFor(char letter) {
        for (int row = 0; row < LETTER_ROWS.length; row++) {
            int index = LETTER_ROWS[row].indexOf(letter);
            if (index >= 0) return LONG_PRESS_ROWS[row].substring(index, index + 1);
        }
        throw new IllegalArgumentException("unknown letter");
    }

    private Button createKey(
            String label,
            String contentDescription,
            float weight,
            Runnable action,
            boolean letter) {
        Button button = Objects.requireNonNull(
                keyFactory.create(label, contentDescription, weight, action),
                "key factory returned null");
        button.setOnClickListener(ignored -> {
            feedback.onPress(button);
            action.run();
        });
        button.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE);
        button.setIncludeFontPadding(false);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        if (letter) {
            button.setMinWidth(0);
            button.setMinimumWidth(0);
            button.setPadding(0, 0, 0, 0);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, LETTER_KEY_TEXT_SIZE_SP);
        } else {
            // Android Button carries large theme padding by default. Compact bottom-row labels
            // such as "中/英" must use the full key cap while the 50dp touch target stays intact.
            button.setMinWidth(0);
            button.setMinimumWidth(0);
            button.setPadding(0, 0, 0, 0);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, FUNCTION_KEY_TEXT_SIZE_SP);
        }
        return button;
    }

    private void deleteOnceUnlessSuppressed() {
        if (!suppressDeleteClick) listener.deleteBackward();
    }

    @SuppressLint("ClickableViewAccessibility") // ACTION_UP delegates to performClick.
    private void configureDeleteRepeat() {
        deleteButton.setOnClickListener(ignored -> {
            if (suppressDeleteClick) return;
            feedback.onPress(deleteButton);
            listener.deleteBackward();
        });
        deleteButton.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    if (!inputEnabled) return true;
                    view.setPressed(true);
                    feedback.onPress(view);
                    deleteRepeater.press(listener::deleteBackward);
                    return true;
                }
                case MotionEvent.ACTION_UP -> {
                    deleteRepeater.stop();
                    view.setPressed(false);
                    suppressDeleteClick = true;
                    try {
                        view.performClick();
                    } finally {
                        suppressDeleteClick = false;
                    }
                    return true;
                }
                case MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> {
                    deleteRepeater.stop();
                    view.setPressed(false);
                    return true;
                }
                default -> {
                    return true;
                }
            }
        });
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private void addWeighted(LinearLayout row, View child, float weight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                dp(KEY_HEIGHT_DP),
                weight);
        params.setMarginStart(dp(KEY_SIDE_MARGIN_DP));
        params.setMarginEnd(dp(KEY_SIDE_MARGIN_DP));
        params.topMargin = dp(KEY_VERTICAL_MARGIN_DP);
        params.bottomMargin = dp(KEY_VERTICAL_MARGIN_DP);
        row.addView(child, params);
    }

    private void addSpacer(LinearLayout row, float weight) {
        View spacer = new View(context);
        spacer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        // A plain View with WRAP_CONTENT height can consume the IME's entire AT_MOST height
        // during LinearLayout's weighted-width measurement pass. Keep the indent spacer
        // heightless so the row height is determined exclusively by its 50dp keys.
        row.addView(spacer, new LinearLayout.LayoutParams(0, 0, weight));
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
