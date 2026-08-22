package com.opentypeless.android.keyboard.clipboard;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.opentypeless.android.R;
import java.util.Objects;

/** Capability-free renderer for one explicit current-clipboard snapshot. */
public final class KeyboardClipboardPanel {
    public interface Listener {
        void onPaste(String text);

        void onRefresh();

        void onClose();
    }

    public static final String ROOT_TAG = "opentypeless-clipboard-panel";
    public static final String CONTENT_TAG = "opentypeless-clipboard-current-text";
    public static final String REFRESH_TAG = "opentypeless-clipboard-refresh";
    public static final String CLOSE_TAG = "opentypeless-clipboard-close";
    public static final int MINIMUM_TOUCH_TARGET_DP = 48;
    private static final int PANEL_MINIMUM_HEIGHT_DP = 216;

    private final Context context;
    private final Listener listener;
    private final LinearLayout root;
    private final TextView message;
    private final Button content;
    private final Button refresh;
    private final Button close;
    private ClipboardPanelSnapshot snapshot = ClipboardPanelSnapshot.unavailable();

    public KeyboardClipboardPanel(Context context, Listener listener) {
        this.context = Objects.requireNonNull(context, "context");
        this.listener = Objects.requireNonNull(listener, "listener");

        root = new LinearLayout(context);
        root.setTag(ROOT_TAG);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setMinimumHeight(dp(PANEL_MINIMUM_HEIGHT_DP));
        root.setPadding(dp(8), dp(4), dp(8), dp(8));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(context);
        title.setText(R.string.ime_clipboard_title);
        title.setTextColor(context.getColor(R.color.ime_on_surface));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        title.setPadding(dp(4), 0, dp(4), 0);
        header.addView(title, new LinearLayout.LayoutParams(
                0, dp(MINIMUM_TOUCH_TARGET_DP), 1f));

        refresh = headerAction(
                R.string.ime_clipboard_refresh,
                R.string.ime_cd_clipboard_refresh,
                REFRESH_TAG,
                ignored -> listener.onRefresh());
        header.addView(refresh, fixedTouchTarget());
        close = headerAction(
                R.string.ime_clipboard_close,
                R.string.ime_cd_clipboard_close,
                CLOSE_TAG,
                ignored -> listener.onClose());
        header.addView(close, fixedTouchTarget());
        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        message = new TextView(context);
        message.setTextColor(context.getColor(R.color.ime_on_surface_variant));
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        message.setGravity(Gravity.CENTER);
        message.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(message, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        content = new Button(context);
        content.setTag(CONTENT_TAG);
        content.setAllCaps(false);
        content.setTextColor(context.getColor(R.color.ime_on_surface));
        content.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        content.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        content.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        content.setPadding(dp(14), dp(10), dp(14), dp(10));
        content.setMaxLines(4);
        content.setEllipsize(TextUtils.TruncateAt.END);
        content.setBackgroundResource(R.drawable.ime_key_background);
        content.setContentDescription(context.getString(R.string.ime_cd_clipboard_paste));
        content.setOnClickListener(ignored -> pasteRenderedSnapshot());
        root.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        render(ClipboardPanelSnapshot.empty());
    }

    public LinearLayout root() {
        return root;
    }

    public Button contentButton() {
        return content;
    }

    public Button refreshButton() {
        return refresh;
    }

    public Button closeButton() {
        return close;
    }

    public void render(ClipboardPanelSnapshot next) {
        snapshot = Objects.requireNonNull(next, "next");
        boolean hasText = snapshot.hasText();
        content.setText(hasText ? snapshot.preview() : "");
        content.setVisibility(hasText ? View.VISIBLE : View.GONE);
        message.setText(messageFor(snapshot.state()));
        message.setVisibility(hasText ? View.GONE : View.VISIBLE);
    }

    /** Removes the only retained clipboard body before the panel leaves the active lifecycle. */
    public void clear() {
        snapshot = ClipboardPanelSnapshot.unavailable();
        content.setText("");
        content.setVisibility(View.GONE);
        message.setText(R.string.ime_clipboard_unavailable);
        message.setVisibility(View.VISIBLE);
    }

    private void pasteRenderedSnapshot() {
        ClipboardPanelSnapshot current = snapshot;
        if (current.hasText()) listener.onPaste(current.text());
    }

    private int messageFor(ClipboardPanelSnapshot.State state) {
        return switch (state) {
            case EMPTY -> R.string.ime_clipboard_empty;
            case UNSUPPORTED -> R.string.ime_clipboard_text_only;
            case TOO_LARGE -> R.string.ime_clipboard_too_large;
            case UNAVAILABLE -> R.string.ime_clipboard_unavailable;
            case TEXT -> R.string.ime_clipboard_empty;
        };
    }

    private Button headerAction(
            int labelResource,
            int descriptionResource,
            String tag,
            View.OnClickListener action) {
        Button button = new Button(context);
        button.setTag(tag);
        button.setText(labelResource);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        button.setAutoSizeTextTypeUniformWithConfiguration(
                9, 13, 1, TypedValue.COMPLEX_UNIT_SP);
        button.setTextColor(context.getColor(R.color.ime_on_surface_variant));
        button.setContentDescription(context.getString(descriptionResource));
        button.setBackgroundResource(R.drawable.ime_key_background);
        button.setPadding(dp(6), 0, dp(6), 0);
        button.setOnClickListener(action);
        return button;
    }

    private LinearLayout.LayoutParams fixedTouchTarget() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dp(64), dp(MINIMUM_TOUCH_TARGET_DP));
        params.setMarginStart(dp(2));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
