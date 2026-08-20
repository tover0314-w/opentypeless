package com.opentypeless.android.keyboard.shell;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import com.opentypeless.android.R;
import com.opentypeless.android.keyboard.ui.CenteredIconButton;
import java.util.Objects;

/** Capability-free two-page switcher for the voice-first and QWERTY input surfaces. */
public final class KeyboardInputModeLayout {
    public enum Mode { VOICE, QWERTY }

    @FunctionalInterface
    public interface Listener {
        void onModeChanged(Mode mode);
    }

    public static final String ROOT_TAG = "opentypeless-input-mode-root";
    public static final String VOICE_PAGE_TAG = "opentypeless-input-mode-voice-page";
    public static final String QWERTY_PAGE_TAG = "opentypeless-input-mode-qwerty-page";

    private final Context context;
    private final LinearLayout root;
    private final CenteredIconButton toggleButton;
    private final View voicePage;
    private final View qwertyPage;
    private final Listener listener;
    private Mode mode;
    private boolean voiceAvailable = true;
    private boolean switchingEnabled = true;

    public KeyboardInputModeLayout(
            Context context,
            CenteredIconButton toggleButton,
            View voicePage,
            View qwertyPage,
            Mode initialMode) {
        this(context, toggleButton, voicePage, qwertyPage, initialMode, ignored -> {});
    }

    public KeyboardInputModeLayout(
            Context context,
            CenteredIconButton toggleButton,
            View voicePage,
            View qwertyPage,
            Mode initialMode,
            Listener listener) {
        this.context = Objects.requireNonNull(context, "context");
        this.toggleButton = Objects.requireNonNull(toggleButton, "toggleButton");
        this.voicePage = Objects.requireNonNull(voicePage, "voicePage");
        this.qwertyPage = Objects.requireNonNull(qwertyPage, "qwertyPage");
        this.listener = Objects.requireNonNull(listener, "listener");
        if (voicePage == qwertyPage) {
            throw new IllegalArgumentException("pages must be distinct");
        }
        voicePage.setTag(VOICE_PAGE_TAG);
        qwertyPage.setTag(QWERTY_PAGE_TAG);

        root = new LinearLayout(context);
        root.setTag(ROOT_TAG);
        root.setOrientation(LinearLayout.VERTICAL);

        FrameLayout pages = new FrameLayout(context);
        pages.addView(voicePage, pageParams());
        pages.addView(qwertyPage, pageParams());
        root.addView(pages, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        toggleButton.setOnClickListener(ignored -> select(
                mode == Mode.VOICE ? Mode.QWERTY : Mode.VOICE));
        select(Objects.requireNonNull(initialMode, "initialMode"));
    }

    public LinearLayout root() {
        return root;
    }

    public Mode mode() {
        return mode;
    }

    public Button toggleButton() {
        return toggleButton;
    }

    public void select(Mode requested) {
        Mode selected = Objects.requireNonNull(requested, "requested");
        if (selected == Mode.VOICE && !voiceAvailable) selected = Mode.QWERTY;
        if (mode == selected) {
            refreshToggle();
            return;
        }
        mode = selected;
        boolean voice = selected == Mode.VOICE;
        voicePage.setVisibility(voice ? View.VISIBLE : View.GONE);
        qwertyPage.setVisibility(voice ? View.GONE : View.VISIBLE);
        refreshToggle();
        listener.onModeChanged(selected);
    }

    public void setVoiceAvailable(boolean available) {
        voiceAvailable = available;
        if (!available && mode == Mode.VOICE) select(Mode.QWERTY);
        refreshToggle();
    }

    public void setSwitchingEnabled(boolean enabled) {
        switchingEnabled = enabled;
        refreshToggle();
    }

    public boolean voiceAvailable() {
        return voiceAvailable;
    }

    private void refreshToggle() {
        boolean voice = mode == Mode.VOICE;
        toggleButton.setVisibility(voiceAvailable ? View.VISIBLE : View.GONE);
        toggleButton.setEnabled(voiceAvailable && switchingEnabled);
        toggleButton.setSelected(false);
        toggleButton.setActivated(false);
        toggleButton.setCenteredIconResource(
                voice ? R.drawable.ime_ic_keyboard_mode : R.drawable.ime_ic_microphone_toolbar);
        toggleButton.setGravity(Gravity.CENTER);
        toggleButton.setPadding(0, 0, 0, 0);
        toggleButton.setContentDescription(context.getString(voice
                ? R.string.ime_cd_open_keyboard_tab
                : R.string.ime_cd_open_voice_tab));
    }

    private static FrameLayout.LayoutParams pageParams() {
        return new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
    }

}
