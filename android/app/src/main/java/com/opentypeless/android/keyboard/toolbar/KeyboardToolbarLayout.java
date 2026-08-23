package com.opentypeless.android.keyboard.toolbar;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Capability-free KBD-006 toolbar container.
 *
 * <p>The toolbar owns only view placement. It exposes two fixed primary-action slots and one
 * overflow anchor; low-frequency commands are rendered by the host's existing overflow menu.
 * Callbacks stay on the supplied views, so this class never receives editor, network, storage or
 * arbitrary action capabilities.
 */
public final class KeyboardToolbarLayout {
    public enum Placement {
        PRIMARY,
        OVERFLOW
    }

    public static final int MINIMUM_TOUCH_TARGET_DP = 48;
    public static final int MAXIMUM_PRIMARY_ACTIONS = 2;
    public static final String STATUS_SLOT_TAG = "opentypeless-toolbar-status";
    public static final String PRIMARY_SLOT_TAG = "opentypeless-toolbar-primary";
    public static final String OVERFLOW_ANCHOR_TAG = "opentypeless-toolbar-overflow";

    private static final Pattern PLACEMENT_ID =
            Pattern.compile("[a-z][a-z0-9_.-]{0,63}");

    private final Context context;
    private final LinearLayout root;
    private final LinearLayout statusSlot;
    private final LinearLayout primarySlot;
    private final Map<String, Placement> placements = new LinkedHashMap<>();
    private final Map<String, View> actions = new LinkedHashMap<>();
    private boolean statusTextAttached;
    private boolean overflowAttached;

    public KeyboardToolbarLayout(Context context, LinearLayout root) {
        this.context = Objects.requireNonNull(context, "context");
        this.root = Objects.requireNonNull(root, "root");
        if (root.getChildCount() != 0) {
            throw new IllegalArgumentException("toolbar root must be empty");
        }
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setMinimumHeight(dp(MINIMUM_TOUCH_TARGET_DP));

        statusSlot = new LinearLayout(context);
        statusSlot.setTag(STATUS_SLOT_TAG);
        statusSlot.setOrientation(LinearLayout.HORIZONTAL);
        statusSlot.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(statusSlot, new LinearLayout.LayoutParams(
                0,
                dp(MINIMUM_TOUCH_TARGET_DP),
                1f));

        primarySlot = new LinearLayout(context);
        primarySlot.setTag(PRIMARY_SLOT_TAG);
        primarySlot.setOrientation(LinearLayout.HORIZONTAL);
        primarySlot.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(primarySlot, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(MINIMUM_TOUCH_TARGET_DP)));
    }

    public LinearLayout root() {
        return root;
    }

    public LinearLayout statusSlot() {
        return statusSlot;
    }

    public LinearLayout primarySlot() {
        return primarySlot;
    }

    public void attachStatusIndicator(View indicator, int widthDp) {
        Objects.requireNonNull(indicator, "indicator");
        if (widthDp <= 0 || widthDp > 48) {
            throw new IllegalArgumentException("status indicator width must be 1..48dp");
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dp(widthDp), dp(MINIMUM_TOUCH_TARGET_DP));
        params.setMarginStart(dp(2));
        params.setMarginEnd(dp(4));
        statusSlot.addView(indicator, params);
    }

    public void attachStatusText(View status) {
        Objects.requireNonNull(status, "status");
        if (statusTextAttached) throw new IllegalStateException("status text already attached");
        statusTextAttached = true;
        statusSlot.addView(status, new LinearLayout.LayoutParams(
                0, dp(MINIMUM_TOUCH_TARGET_DP), 1f));
    }

    public void attachPrimaryAction(String placementId, View action, int widthDp) {
        if (primarySlot.getChildCount() >= MAXIMUM_PRIMARY_ACTIONS) {
            throw new IllegalStateException("primary toolbar is full; use overflow");
        }
        register(placementId, Placement.PRIMARY, action);
        primarySlot.addView(action, actionParams(widthDp));
    }

    public void attachOverflowAnchor(String placementId, View action) {
        if (overflowAttached) throw new IllegalStateException("overflow anchor already attached");
        register(placementId, Placement.OVERFLOW, action);
        overflowAttached = true;
        action.setTag(OVERFLOW_ANCHOR_TAG);
        root.addView(action, actionParams(MINIMUM_TOUCH_TARGET_DP));
    }

    public Placement placementOf(String placementId) {
        return placements.get(placementId);
    }

    /** Applies a precomputed privacy result without giving the container policy authority. */
    public void setActionVisible(String placementId, boolean visible) {
        View action = actions.get(placementId);
        if (action == null) throw new IllegalArgumentException("unknown placement id");
        action.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    public boolean isActionVisible(String placementId) {
        View action = actions.get(placementId);
        if (action == null) throw new IllegalArgumentException("unknown placement id");
        return action.getVisibility() == View.VISIBLE;
    }

    private void register(String placementId, Placement placement, View action) {
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(action, "action");
        if (placementId == null || !PLACEMENT_ID.matcher(placementId).matches()) {
            throw new IllegalArgumentException("invalid placement id");
        }
        if (placements.containsKey(placementId)) {
            throw new IllegalArgumentException("duplicate placement id");
        }
        CharSequence description = action.getContentDescription();
        if (description == null || description.toString().isBlank()) {
            throw new IllegalArgumentException("toolbar action needs a content description");
        }
        if (!action.isClickable()) {
            throw new IllegalArgumentException("toolbar action must be clickable");
        }
        action.setMinimumWidth(dp(MINIMUM_TOUCH_TARGET_DP));
        action.setMinimumHeight(dp(MINIMUM_TOUCH_TARGET_DP));
        placements.put(placementId, placement);
        actions.put(placementId, action);
    }

    private LinearLayout.LayoutParams actionParams(int widthDp) {
        if (widthDp < MINIMUM_TOUCH_TARGET_DP || widthDp > 96) {
            throw new IllegalArgumentException("toolbar action width must be 48..96dp");
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dp(widthDp), dp(MINIMUM_TOUCH_TARGET_DP));
        params.setMarginStart(dp(2));
        params.setMarginEnd(dp(2));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
