package com.opentypeless.android.keyboard.shell;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import java.util.Objects;

/**
 * Capability-free Route-A/legacy Shell frame.
 *
 * <p>This class owns only Android view hierarchy. It never receives InputConnection, an editor
 * manager, arbitrary key codes, network access, or a fallback writer. The IME root supplies
 * bounded button callbacks which already route through EditorSessionManager.
 */
public final class KeyboardShellFrame {
    public static final String ROUTE_A_ROOT_TAG = "opentypeless-route-a-shell";
    public static final String LEGACY_ROOT_TAG = "opentypeless-legacy-voice-shell";
    public static final String ROUTE_A_TOOLBAR_TAG = "opentypeless-route-a-toolbar-slot";
    public static final String ROUTE_A_COMPOSITION_TAG = "opentypeless-route-a-composition-slot";
    public static final String ROUTE_A_KEYS_TAG = "opentypeless-route-a-key-slot";
    public static final String ROUTE_A_EXTENSIONS_TAG = "opentypeless-route-a-extension-slot";

    private final KeyboardShellRoute route;
    private final LinearLayout root;
    private final LinearLayout toolbar;

    private KeyboardShellFrame(Context context, KeyboardShellRoute route) {
        this.route = Objects.requireNonNull(route, "route");
        root = new LinearLayout(Objects.requireNonNull(context, "context"));
        root.setOrientation(LinearLayout.VERTICAL);
        root.setTag(route == KeyboardShellRoute.ROUTE_A ? ROUTE_A_ROOT_TAG : LEGACY_ROOT_TAG);
        toolbar = new LinearLayout(context);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        if (route == KeyboardShellRoute.ROUTE_A) toolbar.setTag(ROUTE_A_TOOLBAR_TAG);
    }

    public static KeyboardShellFrame routeA(Context context) {
        return new KeyboardShellFrame(context, KeyboardShellRoute.ROUTE_A);
    }

    public static KeyboardShellFrame legacyVoice(Context context) {
        return new KeyboardShellFrame(context, KeyboardShellRoute.LEGACY_VOICE);
    }

    public KeyboardShellRoute route() {
        return route;
    }

    public LinearLayout root() {
        return root;
    }

    public LinearLayout toolbar() {
        return toolbar;
    }

    public void attachToolbar(ViewGroup.LayoutParams params) {
        attach(toolbar, params, null);
    }

    public void attachComposition(View view, ViewGroup.LayoutParams params) {
        attach(view, params, ROUTE_A_COMPOSITION_TAG);
    }

    public void attachKeys(View view, ViewGroup.LayoutParams params) {
        attach(view, params, ROUTE_A_KEYS_TAG);
    }

    public void attachExtensions(View view, ViewGroup.LayoutParams params) {
        attach(view, params, ROUTE_A_EXTENSIONS_TAG);
    }

    private void attach(View view, ViewGroup.LayoutParams params, String routeATag) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(params, "params");
        if (route == KeyboardShellRoute.ROUTE_A && routeATag != null) view.setTag(routeATag);
        root.addView(view, params);
    }
}
