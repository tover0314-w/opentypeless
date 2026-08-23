package com.opentypeless.android.keyboard.feedback;

import android.view.View;

/** Bounded keyboard feedback surface. It never receives editor text or editor capabilities. */
public interface KeyboardFeedback {
    KeyboardFeedback NONE = new KeyboardFeedback() {
        @Override
        public void onPress(View key) {}

        @Override
        public void onLongPress(View key) {}
    };

    void onPress(View key);

    void onLongPress(View key);
}
