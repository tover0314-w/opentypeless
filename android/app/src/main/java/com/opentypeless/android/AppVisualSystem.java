package com.opentypeless.android;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.opentypeless.android.diagnostics.RecognitionRoute;
import com.opentypeless.android.diagnostics.RecognitionDiagnostics;
import com.opentypeless.android.settings.ProcessingMode;
import com.opentypeless.android.settings.RecognitionBackend;

/** Shared, deliberately small visual system for every companion-app surface. */
public final class AppVisualSystem {
    private static final int COMPACT_WIDTH_DP = 360;
    private static final float COMPACT_FONT_SCALE = 1.3f;

    private AppVisualSystem() {}

    public enum Destination {
        HOME,
        HISTORY,
        DICTIONARY,
        SETTINGS
    }

    public static void stylePage(Activity activity, View page) {
        page.setBackgroundColor(activity.getColor(R.color.ime_surface));
    }

    static LinearLayout card(Activity activity) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(activity, 18), dp(activity, 16), dp(activity, 18), dp(activity, 16));
        card.setBackgroundResource(R.drawable.app_card_background);
        return card;
    }

    static LinearLayout heroCard(Activity activity) {
        LinearLayout card = card(activity);
        card.setPadding(dp(activity, 20), dp(activity, 18), dp(activity, 20), dp(activity, 18));
        card.setBackgroundResource(R.drawable.app_hero_background);
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

    public static Button accentButton(
            Activity activity,
            CharSequence label,
            View.OnClickListener listener) {
        Button button = secondaryButton(activity, label, listener);
        button.setBackgroundResource(R.drawable.app_accent_action_background);
        button.setTextColor(activity.getColor(R.color.ime_primary));
        return button;
    }

    public static Button accentButton(
            Activity activity,
            int labelResource,
            View.OnClickListener listener) {
        return accentButton(activity, activity.getString(labelResource), listener);
    }

    public static Button secondaryButton(
            Activity activity,
            int labelResource,
            View.OnClickListener listener) {
        return secondaryButton(activity, activity.getString(labelResource), listener);
    }

    static TextView title(Activity activity, CharSequence value) {
        TextView view = text(activity, value, 32, true);
        view.setTextColor(activity.getColor(R.color.ime_on_surface));
        heading(view);
        return view;
    }

    static LinearLayout backHeader(Activity activity, CharSequence value) {
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = new Button(activity);
        back.setText("‹");
        back.setTextSize(32);
        back.setTextColor(activity.getColor(R.color.ime_on_surface));
        back.setContentDescription(activity.getString(R.string.nav_up));
        back.setBackgroundResource(R.drawable.app_row_background);
        back.setMinWidth(dp(activity, 48));
        back.setMinHeight(dp(activity, 48));
        back.setPadding(0, 0, 0, dp(activity, 3));
        back.setElevation(0f);
        back.setStateListAnimator(null);
        back.setOnClickListener(ignored -> activity.finish());
        header.addView(back, new LinearLayout.LayoutParams(
                dp(activity, 48),
                dp(activity, 48)));
        TextView title = text(activity, value, 28, true);
        title.setPadding(dp(activity, 10), 0, 0, 0);
        heading(title);
        header.addView(title, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f));
        return header;
    }

    static TextView section(Activity activity, CharSequence value) {
        TextView view = text(activity, value, 17, true);
        view.setTextColor(activity.getColor(R.color.ime_on_surface));
        view.setPadding(0, dp(activity, 6), 0, dp(activity, 6));
        heading(view);
        return view;
    }

    static TextView eyebrow(Activity activity, CharSequence value) {
        TextView view = text(activity, value, 12, true);
        view.setTextColor(activity.getColor(R.color.ime_on_surface_variant));
        view.setLetterSpacing(.04f);
        return view;
    }

    static TextView heroValue(Activity activity, CharSequence value) {
        TextView view = text(activity, value, 23, true);
        view.setTextColor(activity.getColor(R.color.ime_on_surface));
        view.setPadding(0, dp(activity, 8), 0, dp(activity, 4));
        return view;
    }

    static TextView success(Activity activity, CharSequence value) {
        TextView view = text(activity, value, 14, true);
        view.setTextColor(activity.getColor(R.color.ime_success));
        view.setPadding(0, dp(activity, 4), 0, dp(activity, 4));
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

    static LinearLayout navigationRow(
            Activity activity,
            CharSequence label,
            CharSequence summary,
            View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(activity, 14), dp(activity, 10), dp(activity, 10), dp(activity, 10));
        row.setMinimumHeight(dp(activity, 68));
        row.setBackgroundResource(R.drawable.app_row_background);
        row.setClickable(listener != null);
        row.setFocusable(listener != null);
        row.setOnClickListener(listener);

        LinearLayout copy = new LinearLayout(activity);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(activity, label, 16, true);
        TextView description = text(activity, summary, 13, false);
        description.setTextColor(activity.getColor(R.color.ime_on_surface_variant));
        description.setPadding(0, dp(activity, 3), 0, 0);
        copy.addView(title, matchWrap());
        copy.addView(description, matchWrap());
        row.addView(copy, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f));

        TextView chevron = text(activity, "›", 28, false);
        chevron.setTextColor(activity.getColor(R.color.ime_on_surface_variant));
        chevron.setGravity(Gravity.CENTER);
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        chevron.setVisibility(listener == null ? View.INVISIBLE : View.VISIBLE);
        row.addView(chevron, new LinearLayout.LayoutParams(
                dp(activity, 36),
                dp(activity, 48)));
        row.setContentDescription(label + ". " + summary);
        return row;
    }

    static View divider(Activity activity) {
        View divider = new View(activity);
        divider.setBackgroundColor(activity.getColor(R.color.ime_outline));
        LinearLayout.LayoutParams parameters = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(activity, 1));
        parameters.leftMargin = dp(activity, 14);
        parameters.rightMargin = dp(activity, 14);
        divider.setLayoutParams(parameters);
        divider.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return divider;
    }

    static LinearLayout bottomNavigation(Activity activity, Destination selected) {
        LinearLayout navigation = new LinearLayout(activity);
        navigation.setOrientation(LinearLayout.HORIZONTAL);
        navigation.setGravity(Gravity.CENTER);
        navigation.setPadding(dp(activity, 8), dp(activity, 6), dp(activity, 8), dp(activity, 8));
        navigation.setBackgroundColor(activity.getColor(R.color.ime_surface_container));
        addDestination(activity, navigation, Destination.HOME, selected, R.string.nav_home);
        addDestination(activity, navigation, Destination.HISTORY, selected, R.string.nav_history);
        addDestination(activity, navigation, Destination.DICTIONARY, selected, R.string.nav_dictionary);
        addDestination(activity, navigation, Destination.SETTINGS, selected, R.string.nav_settings);
        return navigation;
    }

    private static void addDestination(
            Activity activity,
            LinearLayout navigation,
            Destination destination,
            Destination selected,
            int labelResource) {
        Button button = new Button(activity);
        String label = activity.getString(labelResource);
        button.setText(label);
        button.setTextSize(12);
        button.setSingleLine(true);
        button.setAutoSizeTextTypeUniformWithConfiguration(
                8,
                12,
                1,
                TypedValue.COMPLEX_UNIT_SP);
        button.setAllCaps(false);
        button.setMinHeight(dp(activity, 54));
        button.setMinimumWidth(0);
        button.setPadding(dp(activity, 4), 0, dp(activity, 4), 0);
        button.setBackgroundResource(R.drawable.app_nav_button_background);
        button.setTextColor(activity.getColorStateList(R.color.ime_key_text));
        button.setElevation(0f);
        button.setStateListAnimator(null);
        button.setSelected(destination == selected);
        button.setContentDescription(label + (destination == selected
                ? ", " + activity.getString(R.string.nav_selected)
                : ""));
        button.setOnClickListener(ignored -> navigate(activity, destination));
        navigation.addView(button, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f));
    }

    private static void navigate(Activity activity, Destination destination) {
        Class<? extends Activity> target = switch (destination) {
            case HOME -> HomeActivity.class;
            case HISTORY -> HistoryActivity.class;
            case DICTIONARY -> DictionaryActivity.class;
            case SETTINGS -> SettingsHomeActivity.class;
        };
        if (activity.getClass() == target) return;
        Intent intent = new Intent(activity, target);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        activity.startActivity(intent);
        activity.overridePendingTransition(0, 0);
    }

    static String backendLabel(Activity activity, RecognitionBackend backend) {
        return activity.getString(switch (backend) {
            case OPENAI_COMPATIBLE -> R.string.backend_openai;
            case LOCAL_OFFLINE -> R.string.backend_local_offline;
            case DASHSCOPE_STREAMING -> R.string.backend_dashscope_streaming;
            case SYSTEM_ON_DEVICE -> R.string.backend_on_device;
            case SYSTEM_DEFAULT -> R.string.backend_system_default;
        });
    }

    static String modeLabel(Activity activity, ProcessingMode mode) {
        return activity.getString(switch (mode) {
            case AUTO -> R.string.mode_auto;
            case VERBATIM -> R.string.mode_verbatim;
            case SMART -> R.string.mode_smart;
            case TRANSLATE -> R.string.mode_translate;
        });
    }

    static String privacyLabel(
            Activity activity,
            RecognitionRoute.PrivacyBoundary boundary) {
        return activity.getString(switch (boundary) {
            case ON_DEVICE -> R.string.settings_privacy_on_device;
            case PROVIDER_DEPENDENT -> R.string.settings_privacy_provider_dependent;
            case NETWORK -> R.string.settings_privacy_network;
        });
    }

    static RecognitionRoute routeForSummary(
            RecognitionBackend configured,
            RecognitionDiagnostics.Snapshot latest) {
        if (latest != null
                && latest.status() == RecognitionDiagnostics.Status.SUCCEEDED
                && latest.finalCodePointCount() > 0
                && latest.route().selectedBackend() == configured) {
            return latest.route();
        }
        return RecognitionRoute.direct(configured);
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
