package com.opentypeless.android.keyboard.clipboard;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import java.util.Objects;

/** Explicit, one-shot reader for the current primary plain-text clipboard item. */
public final class SystemClipboardReader {
    private SystemClipboardReader() {}

    public static ClipboardPanelSnapshot readCurrentText(Context context) {
        Objects.requireNonNull(context, "context");
        try {
            ClipboardManager manager = context.getSystemService(ClipboardManager.class);
            if (manager == null) return ClipboardPanelSnapshot.unavailable();
            return snapshotOf(manager.getPrimaryClip());
        } catch (RuntimeException unavailable) {
            return ClipboardPanelSnapshot.unavailable();
        }
    }

    static ClipboardPanelSnapshot snapshotOf(ClipData clip) {
        if (clip == null || clip.getItemCount() == 0) return ClipboardPanelSnapshot.empty();
        try {
            // URI/Intent items must not trigger provider or resolver access from the IME.
            // KBD-011 accepts only an already-materialized text item.
            return ClipboardPanelSnapshot.fromPrimaryText(clip.getItemAt(0).getText());
        } catch (RuntimeException unavailable) {
            return ClipboardPanelSnapshot.unavailable();
        }
    }
}
