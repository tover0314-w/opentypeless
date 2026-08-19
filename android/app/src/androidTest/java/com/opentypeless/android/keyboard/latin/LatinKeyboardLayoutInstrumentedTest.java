package com.opentypeless.android.keyboard.latin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import com.opentypeless.android.R;
import com.opentypeless.android.keyboard.field.KeyboardFieldProfile;
import com.opentypeless.android.keyboard.feedback.KeyboardFeedback;
import com.opentypeless.android.keyboard.switching.KeyboardEngineSelection;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class LatinKeyboardLayoutInstrumentedTest {
    @Test
    public void realButtonsEmitLowerShiftAndPersistentCapsText() {
        onMain(() -> {
            Harness harness = new Harness();
            LatinKeyboardLayout layout = harness.layout;

            assertEquals(4, layout.root().getChildCount());
            assertEquals("q", layout.letterButton('q').getText().toString());
            assertTrue(layout.letterButton('q').performClick());
            assertEquals(List.of("q"), harness.inserted);

            assertTrue(layout.shiftButton().performClick());
            assertEquals(LatinKeyboardState.ShiftMode.SHIFTED, layout.shiftMode());
            assertEquals("W", layout.letterButton('w').getText().toString());
            assertTrue(layout.letterButton('w').performClick());
            assertEquals(List.of("q", "W"), harness.inserted);
            assertEquals(LatinKeyboardState.ShiftMode.LOWER, layout.shiftMode());

            assertTrue(layout.shiftButton().performClick());
            assertTrue(layout.shiftButton().performClick());
            assertEquals(LatinKeyboardState.ShiftMode.CAPS_LOCKED, layout.shiftMode());
            assertTrue(layout.letterButton('e').performClick());
            assertTrue(layout.letterButton('r').performClick());
            assertEquals(List.of("q", "W", "E", "R"), harness.inserted);
            assertEquals(LatinKeyboardState.ShiftMode.CAPS_LOCKED, layout.shiftMode());
            assertTrue(layout.shiftButton().performClick());
            assertEquals(LatinKeyboardState.ShiftMode.LOWER, layout.shiftMode());
        });
    }

    @Test
    public void spaceDeleteEnterAndSwitchUseOnlyTheirBoundedCallbacks() {
        onMain(() -> {
            Harness harness = new Harness();

            assertTrue(harness.layout.spaceButton().performClick());
            assertTrue(harness.layout.deleteButton().performClick());
            assertTrue(harness.layout.enterButton().performClick());
            assertTrue(harness.layout.switchKeyboardButton().performClick());
            assertTrue(harness.layout.switchKeyboardButton().performLongClick());

            assertEquals(List.of(" "), harness.inserted);
            assertEquals(1, harness.deleted.get());
            assertEquals(1, harness.entered.get());
            assertEquals(1, harness.switched.get());
            assertEquals(1, harness.pickerShown.get());
        });
    }

    @Test
    public void heldDeleteRepeatsAndStopsExactlyOnReleaseOrDisable() {
        onMain(() -> {
            FakeScheduler scheduler = new FakeScheduler();
            Harness harness = new Harness(scheduler);
            Button delete = harness.layout.deleteButton();

            dispatch(delete, MotionEvent.ACTION_DOWN);
            assertEquals(1, harness.deleted.get());
            scheduler.runNext();
            scheduler.runNext();
            assertEquals(3, harness.deleted.get());

            dispatch(delete, MotionEvent.ACTION_UP);
            scheduler.runAll();
            assertEquals(3, harness.deleted.get());

            dispatch(delete, MotionEvent.ACTION_DOWN);
            assertEquals(4, harness.deleted.get());
            harness.layout.setInputEnabled(false);
            scheduler.runAll();
            assertEquals(4, harness.deleted.get());
        });
    }

    @Test
    public void engineControlIsHiddenUntilTwoEnginesAreRegistered() {
        onMain(() -> {
            Harness harness = new Harness();

            assertEquals(View.GONE, harness.layout.engineSwitchButton().getVisibility());
            harness.layout.setEngineSelection(KeyboardEngineSelection.of(
                    KeyboardEngineSelection.Engine.LATIN,
                    java.util.EnumSet.allOf(KeyboardEngineSelection.Engine.class),
                    2L));

            assertEquals(View.VISIBLE, harness.layout.engineSwitchButton().getVisibility());
            assertEquals("EN", harness.layout.engineSwitchButton().getText().toString());
            assertTrue(harness.layout.engineSwitchButton().performClick());
            assertEquals(1, harness.engineSwitched.get());

            harness.layout.setEngineSelection(KeyboardEngineSelection.of(
                    KeyboardEngineSelection.Engine.RIME,
                    java.util.EnumSet.allOf(KeyboardEngineSelection.Engine.class),
                    3L));
            assertEquals("中", harness.layout.engineSwitchButton().getText().toString());
            assertEquals(
                    ApplicationProvider.getApplicationContext().getString(
                            R.string.ime_cd_engine_rime),
                    harness.layout.engineSwitchButton().getContentDescription().toString());
        });
    }

    @Test
    public void disablingInputDisablesEveryInteractiveKey() {
        onMain(() -> {
            Harness harness = new Harness();

            harness.layout.setInputEnabled(false);

            assertFalse(harness.layout.letterButton('a').isEnabled());
            assertFalse(harness.layout.shiftButton().isEnabled());
            assertFalse(harness.layout.spaceButton().isEnabled());
            assertFalse(harness.layout.deleteButton().isEnabled());
            assertFalse(harness.layout.enterButton().isEnabled());
            assertFalse(harness.layout.switchKeyboardButton().isEnabled());
            assertFalse(harness.layout.engineSwitchButton().isEnabled());
            assertFalse(harness.layout.symbolsToggleButton().isEnabled());
            assertFalse(harness.layout.symbolPageButton().isEnabled());
        });
    }

    @Test
    public void symbolsPagesEmitExactBoundedTextAndReturnToLetters() {
        onMain(() -> {
            Harness harness = new Harness();
            LatinKeyboardLayout layout = harness.layout;

            assertTrue(layout.symbolsToggleButton().performClick());
            assertEquals(LatinKeyboardState.Layer.SYMBOLS_PRIMARY, layout.layer());
            assertEquals(View.VISIBLE, layout.symbolPageButton().getVisibility());
            assertTrue(layout.symbolButton("1").performClick());
            assertTrue(layout.symbolButton("@").performClick());
            assertTrue(layout.symbolButton("?").performClick());

            assertTrue(layout.symbolPageButton().performClick());
            assertEquals(LatinKeyboardState.Layer.SYMBOLS_SECONDARY, layout.layer());
            assertTrue(layout.symbolButton("[").performClick());
            assertTrue(layout.symbolButton("…").performClick());
            assertTrue(layout.symbolButton("€").performClick());

            assertTrue(layout.symbolsToggleButton().performClick());
            assertEquals(LatinKeyboardState.Layer.LETTERS, layout.layer());
            assertEquals(View.GONE, layout.symbolPageButton().getVisibility());
            assertEquals("a", layout.letterButton('a').getText().toString());
            assertEquals(List.of("1", "@", "?", "[", "…", "€"), harness.inserted);
        });
    }

    @Test
    public void longPressEmitsAlternateWithoutOrdinaryLetterCallback() {
        onMain(() -> {
            Harness harness = new Harness();

            assertTrue(harness.layout.letterButton('q').performLongClick());
            assertTrue(harness.layout.letterButton('a').performLongClick());
            assertTrue(harness.layout.letterButton('m').performLongClick());

            assertEquals(List.of("1", "@", "?"), harness.inserted);
            assertEquals(0, harness.feedback.presses.get());
            assertEquals(3, harness.feedback.longPresses.get());
            assertTrue(harness.layout.letterButton('q')
                    .getContentDescription().toString().contains("1"));
        });
    }

    @Test
    public void ordinaryClicksEmitOnePressFeedbackAndKeyboardPickerUsesLongFeedback() {
        onMain(() -> {
            Harness harness = new Harness();

            assertTrue(harness.layout.letterButton('a').performClick());
            assertTrue(harness.layout.deleteButton().performClick());
            assertTrue(harness.layout.switchKeyboardButton().performLongClick());

            assertEquals(2, harness.feedback.presses.get());
            assertEquals(1, harness.feedback.longPresses.get());
            assertEquals(List.of("a"), harness.inserted);
            assertEquals(1, harness.deleted.get());
            assertEquals(1, harness.pickerShown.get());
        });
    }

    @Test
    public void primarySymbolLayoutSnapshotIsStable() {
        onMain(() -> {
            Harness harness = new Harness();
            harness.layout.symbolsToggleButton().performClick();

            assertEquals(
                    List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
                    labels((LinearLayout) harness.layout.root().getChildAt(0)));
            assertEquals(
                    List.of("@", "#", "$", "%", "&", "-", "+", "(", ")", "/"),
                    labels((LinearLayout) harness.layout.root().getChildAt(1)));
            assertEquals(
                    List.of("*", "\"", "'", ":", ";", "!", "?", ",", ".", "⌫"),
                    labels((LinearLayout) harness.layout.root().getChildAt(2)));
        });
    }

    @Test
    public void rowsRemainContiguousUnderTallImeMeasureSpec() {
        onMain(() -> {
            Harness harness = new Harness();
            LinearLayout root = harness.layout.root();
            root.measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2200, View.MeasureSpec.AT_MOST));
            root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());

            int totalHeight = 0;
            int firstRowHeight = root.getChildAt(0).getMeasuredHeight();
            assertTrue(firstRowHeight > 0);
            for (int index = 0; index < root.getChildCount(); index++) {
                View row = root.getChildAt(index);
                assertTrue(row.getMeasuredHeight() > 0);
                assertTrue(row.getMeasuredHeight() <= firstRowHeight * 2);
                if (index > 0) {
                    assertEquals(root.getChildAt(index - 1).getBottom(), row.getTop());
                }
                totalHeight += row.getMeasuredHeight();
            }
            assertEquals(totalHeight, root.getMeasuredHeight());
        });
    }

    @Test
    public void qwertyRowsUseCompactXiaoheGeometryAndKeepTouchHeight() {
        onMain(() -> {
            Harness harness = new Harness();
            LinearLayout root = harness.layout.root();
            root.measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2200, View.MeasureSpec.AT_MOST));
            root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());

            LinearLayout firstRow = row(harness.layout, 0);
            LinearLayout secondRow = row(harness.layout, 1);
            LinearLayout thirdRow = row(harness.layout, 2);
            LinearLayout bottomRow = row(harness.layout, 3);
            Button q = harness.layout.letterButton('q');
            Button a = harness.layout.letterButton('a');
            Button z = harness.layout.letterButton('z');

            assertEquals(10, firstRow.getChildCount());
            assertEquals(11, secondRow.getChildCount()); // two indent spacers + 9 letters
            assertEquals(9, thirdRow.getChildCount()); // shift + 7 letters + delete
            assertEquals(dp(root.getContext(), 50), q.getLayoutParams().height);
            assertEquals(q.getHeight(), a.getHeight());
            assertTrue(q.getHeight() >= dp(root.getContext(), 48));
            assertTrue(a.getLeft() > q.getLeft());
            assertTrue(z.getLeft() > q.getLeft());
            assertTrue(harness.layout.spaceButton().getWidth() > q.getWidth() * 3);
            assertTrue(bottomRow.getMeasuredHeight() > 0);
        });
    }

    @Test
    public void emailAndUriProfilesExposeDirectShortcutsWithoutBypassingListener() {
        onMain(() -> {
            Harness harness = new Harness();
            LatinKeyboardLayout layout = harness.layout;

            layout.setFieldProfile(KeyboardFieldProfile.EMAIL);
            assertEquals(KeyboardFieldProfile.EMAIL, layout.fieldProfile());
            assertEquals(
                    ApplicationProvider.getApplicationContext().getString(
                            R.string.ime_cd_keyboard_profile_email),
                    layout.root().getContentDescription().toString());
            assertEquals("@", layout.profileShortcutButton(0).getText().toString());
            assertEquals(".", layout.profileShortcutButton(1).getText().toString());
            assertEquals(View.GONE, layout.profileShortcutButton(2).getVisibility());
            assertTrue(layout.profileShortcutButton(0).performClick());
            assertTrue(layout.profileShortcutButton(1).performClick());

            layout.setFieldProfile(KeyboardFieldProfile.URI);
            assertEquals("/", layout.profileShortcutButton(0).getText().toString());
            assertEquals(".", layout.profileShortcutButton(1).getText().toString());
            assertEquals(":", layout.profileShortcutButton(2).getText().toString());
            assertTrue(layout.profileShortcutButton(0).performClick());
            assertTrue(layout.profileShortcutButton(2).performClick());
            assertEquals(List.of("@", ".", "/", ":"), harness.inserted);
        });
    }

    @Test
    public void phoneNumberAndDateProfilesExposeOnlyTheirNumericPanels() {
        onMain(() -> {
            Harness harness = new Harness();
            LatinKeyboardLayout layout = harness.layout;

            layout.setFieldProfile(KeyboardFieldProfile.PHONE);
            assertEquals(List.of("1", "2", "3"), labels(row(layout, 0)));
            assertEquals(List.of("7", "8", "9", "+", "0", "*", "#", "⌫"),
                    labels(row(layout, 2)));
            assertEquals(View.GONE, layout.spaceButton().getVisibility());
            assertTrue(layout.symbolButton("+").performClick());

            layout.setFieldProfile(KeyboardFieldProfile.NUMBER);
            assertEquals(List.of("7", "8", "9", "-", "0", ".", "⌫"),
                    labels(row(layout, 2)));
            assertTrue(layout.symbolButton(".").performClick());

            layout.setFieldProfile(KeyboardFieldProfile.DATE);
            assertEquals(List.of("7", "8", "9", "/", "0", "-", ".", "⌫"),
                    labels(row(layout, 2)));
            assertTrue(layout.symbolButton("/").performClick());
            assertEquals(List.of("+", ".", "/"), harness.inserted);
        });
    }

    @Test
    public void passwordProfileResetsSymbolsAndKeepsOrdinaryTextCallbacks() {
        onMain(() -> {
            Harness harness = new Harness();
            LatinKeyboardLayout layout = harness.layout;
            layout.symbolsToggleButton().performClick();

            layout.setFieldProfile(KeyboardFieldProfile.PASSWORD);

            assertEquals(KeyboardFieldProfile.PASSWORD, layout.fieldProfile());
            assertEquals(LatinKeyboardState.Layer.LETTERS, layout.layer());
            assertEquals(
                    ApplicationProvider.getApplicationContext().getString(
                            R.string.ime_cd_keyboard_profile_password),
                    layout.root().getContentDescription().toString());
            assertEquals(View.GONE, layout.profileShortcutButton(0).getVisibility());
            assertTrue(layout.letterButton('p').performClick());
            assertEquals(List.of("p"), harness.inserted);
        });
    }

    private static void onMain(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }

    private static void dispatch(Button button, int action) {
        MotionEvent event = MotionEvent.obtain(0L, 0L, action, 1f, 1f, 0);
        try {
            assertTrue(button.dispatchTouchEvent(event));
        } finally {
            event.recycle();
        }
    }

    private static List<String> labels(LinearLayout row) {
        List<String> labels = new ArrayList<>();
        for (int index = 0; index < row.getChildCount(); index++) {
            View child = row.getChildAt(index);
            if (child instanceof Button button) labels.add(button.getText().toString());
        }
        return labels;
    }

    private static LinearLayout row(LatinKeyboardLayout layout, int index) {
        return (LinearLayout) layout.root().getChildAt(index);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static final class Harness implements LatinKeyboardLayout.Listener {
        final List<String> inserted = new ArrayList<>();
        final AtomicInteger deleted = new AtomicInteger();
        final AtomicInteger entered = new AtomicInteger();
        final AtomicInteger switched = new AtomicInteger();
        final AtomicInteger pickerShown = new AtomicInteger();
        final AtomicInteger engineSwitched = new AtomicInteger();
        final RecordingFeedback feedback = new RecordingFeedback();
        final LatinKeyboardLayout layout;

        Harness() {
            this(null);
        }

        Harness(BoundedDeleteRepeater.Scheduler repeatScheduler) {
            Context context = ApplicationProvider.getApplicationContext();
            layout = new LatinKeyboardLayout(
                    context,
                    (label, description, weight, action) -> {
                        Button button = new Button(context);
                        button.setText(label);
                        button.setContentDescription(description);
                        button.setOnClickListener(ignored -> action.run());
                        return button;
                    },
                    this,
                    feedback,
                    repeatScheduler);
        }

        @Override
        public void insertText(String text) {
            inserted.add(text);
        }

        @Override
        public void deleteBackward() {
            deleted.incrementAndGet();
        }

        @Override
        public void performEnter() {
            entered.incrementAndGet();
        }

        @Override
        public void switchKeyboard() {
            switched.incrementAndGet();
        }

        @Override
        public void showKeyboardPicker() {
            pickerShown.incrementAndGet();
        }

        @Override
        public void switchInputEngine() {
            engineSwitched.incrementAndGet();
        }
    }

    private static final class FakeScheduler implements BoundedDeleteRepeater.Scheduler {
        final ArrayDeque<Entry> entries = new ArrayDeque<>();

        @Override
        public BoundedDeleteRepeater.Cancellation schedule(Runnable action, long delayMillis) {
            Entry entry = new Entry(action);
            entries.addLast(entry);
            return () -> entry.cancelled = true;
        }

        void runNext() {
            Entry entry = entries.removeFirst();
            if (!entry.cancelled) entry.action.run();
        }

        void runAll() {
            while (!entries.isEmpty()) runNext();
        }

        static final class Entry {
            final Runnable action;
            boolean cancelled;

            Entry(Runnable action) {
                this.action = action;
            }
        }
    }

    private static final class RecordingFeedback implements KeyboardFeedback {
        final AtomicInteger presses = new AtomicInteger();
        final AtomicInteger longPresses = new AtomicInteger();

        @Override
        public void onPress(View key) {
            presses.incrementAndGet();
        }

        @Override
        public void onLongPress(View key) {
            longPresses.incrementAndGet();
        }
    }
}
