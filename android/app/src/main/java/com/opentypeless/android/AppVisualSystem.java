package com.opentypeless.android;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared, deliberately small visual system for every companion-app surface. */
public final class AppVisualSystem {
    private static final int COMPACT_WIDTH_DP = 360;
    private static final float COMPACT_FONT_SCALE = 1.3f;

    private AppVisualSystem() {}

    public static void stylePage(Activity activity, View page) {
        page.setBackgroundColor(activity.getColor(R.color.ime_surface));
    }

    static LinearLayout card(Activity activity) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 16), dp(activity, 14));
        card.setBackgroundResource(R.drawable.app_card_background);
        return card;
    }

    static LinearLayout.LayoutParams cardParams(Activity activity) {
        LinearLayout.LayoutParams parameters = matchWrap();
        parameters.bottomMargin = dp(activity, 12);
        return parameters;
    }

    public static Button secondaryButton(
            Activity activity,
            CharSequence label,
            View.OnClickListener listener) {
        Button button = new Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        button.setMinHeight(dp(activity, 48));
        button.setBackgroundResource(R.drawable.app_action_background);
        button.setTextColor(activity.getColorStateList(R.color.ime_key_text));
        button.setElevation(0f);
        button.setStateListAnimator(null);
        button.setContentDescription(label);
        button.setOnClickListener(listener);
        return button;
    }

    public static Button secondaryButton(
            Activity activity,
            int labelResource,
            View.OnClickListener listener) {
        return secondaryButton(activity, activity.getString(labelResource), listener);
    }

    static TextView title(Activity activity, CharSequence value) {
        TextView view = text(activity, value, 26, true);
        view.setTextColor(activity.getColor(R.color.ime_on_surface));
        heading(view);
        return view;
    }

    static TextView section(Activity activity, CharSequence value) {
        TextView view = text(activity, value, 19, true);
        view.setTextColor(activity.getColor(R.color.ime_primary));
        view.setPadding(0, dp(activity, 4), 0, dp(activity, 4));
        heading(view);
        return view;
    }

    static TextView body(Activity activity, CharSequence value) {
        TextView view = text(activity, value, 14, false);
        view.setTextColor(activity.getColor(R.color.ime_on_surface_variant));
        view.setPadding(0, dp(activity, 4), 0, dp(activity, 8));
        return view;
    }

    static TextView note(Activity activity, CharSequence value) {
        TextView view = text(activity, value, 13, false);
        view.setTextColor(activity.getColor(R.color.ime_on_surface_variant));
        view.setPadding(0, dp(activity, 8), 0, dp(activity, 12));
        return view;
    }

    static TextView text(
            Activity activity,
            CharSequence value,
            int textSizeSp,
            boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(textSizeSp);
        view.setTextColor(activity.getColor(R.color.ime_on_surface));
        if (bold) view.setTypeface(view.getTypeface(), Typeface.BOLD);
        return view;
    }

    public static LinearLayout actionGroup(Activity activity) {
        LinearLayout group = new LinearLayout(activity);
        group.setOrientation(isCompact(activity)
                ? LinearLayout.VERTICAL
                : LinearLayout.HORIZONTAL);
        group.setGravity(Gravity.CENTER_VERTICAL);
        return group;
    }

    public static LinearLayout.LayoutParams actionParams(Activity activity) {
        if (isCompact(activity)) return matchWrap();
        return new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f);
    }

    static boolean isCompact(Activity activity) {
        return compactFor(
                activity.getResources().getConfiguration().screenWidthDp,
                activity.getResources().getConfiguration().fontScale);
    }

    static boolean compactFor(int screenWidthDp, float fontScale) {
        return screenWidthDp < COMPACT_WIDTH_DP || fontScale >= COMPACT_FONT_SCALE;
    }

    static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    static void heading(View view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            view.setAccessibilityHeading(true);
        }
    }
}
