package com.opentypeless.android;

import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;

/** Keeps programmatic Activity content clear of Android 15 edge-to-edge system bars and cutouts. */
public final class SystemBarInsets {
    private SystemBarInsets() {}

    public static void apply(View view) {
        int baseLeft = view.getPaddingLeft();
        int baseTop = view.getPaddingTop();
        int baseRight = view.getPaddingRight();
        int baseBottom = view.getPaddingBottom();
        if (view instanceof ViewGroup group) group.setClipToPadding(false);
        view.setOnApplyWindowInsetsListener((target, insets) -> {
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            target.setPadding(
                    baseLeft + left,
                    baseTop + top,
                    baseRight + right,
                    baseBottom + bottom);
            return insets;
        });
        view.requestApplyInsets();
    }
}
