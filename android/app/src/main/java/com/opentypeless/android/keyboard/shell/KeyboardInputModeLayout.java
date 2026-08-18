package com.opentypeless.android.keyboard.shell;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import java.util.Objects;

/** Capability-free two-page switcher for the voice-first and QWERTY input surfaces. */
public final class KeyboardInputModeLayout {
    public enum Mode { VOICE, QWERTY }

    @FunctionalInterface
    public interface Listener {
        void onModeChanged(Mode mode);
    }

    public static final String ROOT_TAG = "opentypeless-input-mode-root";
    public static final String TABS_TAG = "opentypeless-input-mode-tabs";
    public static final String VOICE_PAGE_TAG = "opentypeless-input-mode-voice-page";
    public static final String QWERTY_PAGE_TAG = "opentypeless-input-mode-qwerty-page";

    private final LinearLayout root;
    private final Button voiceTab;
    private final Button qwertyTab;
    private final View voicePage;
    private final View qwertyPage;
    private final Listener listener;
    private Mode mode;
    private boolean voiceAvailable = true;
    private boolean switchingEnabled = true;

    public KeyboardInputModeLayout(
            Context context,
            Button voiceTab,
            Button qwertyTab,
            View voicePage,
            View qwertyPage,
            Mode initialMode) {
        this(context, voiceTab, qwertyTab, voicePage, qwertyPage, initialMode, ignored -> {});
    }

    public KeyboardInputModeLayout(
            Context context,
            Button voiceTab,
            Button qwertyTab,
            View voicePage,
            View qwertyPage,
            Mode initialMode,
            Listener listener) {
        Objects.requireNonNull(context, "context");
        this.voiceTab = Objects.requireNonNull(voiceTab, "voiceTab");
        this.qwertyTab = Objects.requireNonNull(qwertyTab, "qwertyTab");
        this.voicePage = Objects.requireNonNull(voicePage, "voicePage");
        this.qwertyPage = Objects.requireNonNull(qwertyPage, "qwertyPage");
        this.listener = Objects.requireNonNull(listener, "listener");
        if (voicePage == qwertyPage || voiceTab == qwertyTab) {
            throw new IllegalArgumentException("tabs and pages must be distinct");
        }
        voicePage.setTag(VOICE_PAGE_TAG);
        qwertyPage.setTag(QWERTY_PAGE_TAG);

        root = new LinearLayout(context);
        root.setTag(ROOT_TAG);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout tabs = new LinearLayout(context);
        tabs.setTag(TABS_TAG);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER);
        tabs.setPadding(dp(context, 36), 0, dp(context, 36), dp(context, 4));
        tabs.addView(voiceTab, tabParams(context));
        tabs.addView(qwertyTab, tabParams(context));
        root.addView(tabs, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout pages = new FrameLayout(context);
        pages.addView(voicePage, pageParams());
        pages.addView(qwertyPage, pageParams());
        root.addView(pages, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        voiceTab.setOnClickListener(ignored -> select(Mode.VOICE));
        qwertyTab.setOnClickListener(ignored -> select(Mode.QWERTY));
        select(Objects.requireNonNull(initialMode, "initialMode"));
    }

    public LinearLayout root() {
        return root;
    }

    public Mode mode() {
        return mode;
    }

    public Button voiceTab() {
        return voiceTab;
    }

    public Button qwertyTab() {
        return qwertyTab;
    }

    public void select(Mode requested) {
        Mode selected = Objects.requireNonNull(requested, "requested");
        if (selected == Mode.VOICE && !voiceAvailable) selected = Mode.QWERTY;
        if (mode == selected) return;
        mode = selected;
        boolean voice = selected == Mode.VOICE;
        voicePage.setVisibility(voice ? View.VISIBLE : View.GONE);
        qwertyPage.setVisibility(voice ? View.GONE : View.VISIBLE);
        voiceTab.setSelected(voice);
        qwertyTab.setSelected(!voice);
        voiceTab.setActivated(voice);
        qwertyTab.setActivated(!voice);
        listener.onModeChanged(selected);
    }

    public void setVoiceAvailable(boolean available) {
        voiceAvailable = available;
        voiceTab.setVisibility(available ? View.VISIBLE : View.GONE);
        voiceTab.setEnabled(available && switchingEnabled);
        if (!available && mode == Mode.VOICE) select(Mode.QWERTY);
    }

    public void setSwitchingEnabled(boolean enabled) {
        switchingEnabled = enabled;
        voiceTab.setEnabled(enabled && voiceAvailable);
        qwertyTab.setEnabled(enabled);
    }

    public boolean voiceAvailable() {
        return voiceAvailable;
    }

    private static LinearLayout.LayoutParams tabParams(Context context) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, dp(context, 48), 1f);
        params.setMarginStart(dp(context, 3));
        params.setMarginEnd(dp(context, 3));
        return params;
    }

    private static FrameLayout.LayoutParams pageParams() {
        return new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
