package com.opentypeless.android.keyboard.switching;

import java.util.Objects;

/** Stable host-facing result for user-initiated Android input-method switching. */
public final class KeyboardSystemImeSwitcher {
    public enum Outcome {
        NEXT_INPUT_METHOD_REQUESTED,
        PICKER_SHOWN_NO_NEXT,
        PICKER_SHOWN_AFTER_PLATFORM_FAILURE,
        FAILED
    }

    public interface Platform {
        boolean switchToNextInputMethod();

        boolean showInputMethodPicker();
    }

    private KeyboardSystemImeSwitcher() {}

    public static Outcome requestNext(Platform platform) {
        Objects.requireNonNull(platform, "platform");
        boolean platformFailure = false;
        try {
            if (platform.switchToNextInputMethod()) {
                return Outcome.NEXT_INPUT_METHOD_REQUESTED;
            }
        } catch (RuntimeException unavailable) {
            platformFailure = true;
        }
        try {
            if (platform.showInputMethodPicker()) {
                return platformFailure
                        ? Outcome.PICKER_SHOWN_AFTER_PLATFORM_FAILURE
                        : Outcome.PICKER_SHOWN_NO_NEXT;
            }
        } catch (RuntimeException unavailable) {
            return Outcome.FAILED;
        }
        return Outcome.FAILED;
    }

    public static Outcome requestPicker(Platform platform) {
        Objects.requireNonNull(platform, "platform");
        try {
            return platform.showInputMethodPicker()
                    ? Outcome.PICKER_SHOWN_NO_NEXT
                    : Outcome.FAILED;
        } catch (RuntimeException unavailable) {
            return Outcome.FAILED;
        }
    }
}
