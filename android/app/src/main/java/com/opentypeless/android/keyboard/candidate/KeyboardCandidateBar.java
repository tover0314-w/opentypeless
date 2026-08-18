package com.opentypeless.android.keyboard.candidate;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import com.opentypeless.android.R;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Capability-free, horizontally scrollable candidate page renderer for KBD-007. */
public final class KeyboardCandidateBar {
    public static final int MINIMUM_TOUCH_TARGET_DP = 48;
    public static final String ROOT_TAG = "opentypeless-candidate-bar";
    public static final String ROW_TAG = "opentypeless-candidate-row";

    public interface Listener {
        void onCandidateSelected(CandidatePage.Selection selection);

        void onPageRequested(CandidatePage.PageRequest request);
    }

    private final Context context;
    private final Listener listener;
    private final LinearLayout root;
    private final HorizontalScrollView scroller;
    private final LinearLayout candidateRow;
    private final Button previousButton;
    private final Button nextButton;
    private final List<Button> candidateButtons = new ArrayList<>();
    private CandidatePage renderedPage;
    private boolean plaintextVisible;
    private boolean interactionEnabled = true;

    public KeyboardCandidateBar(Context context, Listener listener) {
        this.context = Objects.requireNonNull(context, "context");
        this.listener = Objects.requireNonNull(listener, "listener");

        root = new LinearLayout(context);
        root.setTag(ROOT_TAG);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setMinimumHeight(dp(MINIMUM_TOUCH_TARGET_DP));
        root.setVisibility(View.GONE);

        previousButton = navigationButton(
                context.getString(R.string.ime_candidate_previous),
                context.getString(R.string.ime_cd_candidate_previous));
        previousButton.setOnClickListener(ignored -> dispatchPage(CandidatePage.Direction.PREVIOUS));
        root.addView(previousButton, fixedTouchParams());

        candidateRow = new LinearLayout(context);
        candidateRow.setTag(ROW_TAG);
        candidateRow.setOrientation(LinearLayout.HORIZONTAL);
        candidateRow.setGravity(Gravity.CENTER_VERTICAL);

        scroller = new HorizontalScrollView(context);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setFillViewport(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroller.addView(candidateRow, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT,
                HorizontalScrollView.LayoutParams.MATCH_PARENT));
        root.addView(scroller, new LinearLayout.LayoutParams(
                0, dp(MINIMUM_TOUCH_TARGET_DP), 1f));

        nextButton = navigationButton(
                context.getString(R.string.ime_candidate_next),
                context.getString(R.string.ime_cd_candidate_next));
        nextButton.setOnClickListener(ignored -> dispatchPage(CandidatePage.Direction.NEXT));
        root.addView(nextButton, fixedTouchParams());
    }

    public LinearLayout root() {
        return root;
    }

    public HorizontalScrollView scroller() {
        return scroller;
    }

    public LinearLayout candidateRow() {
        return candidateRow;
    }

    public Button candidateButton(int index) {
        if (index < 0 || index >= candidateButtons.size()) {
            throw new IllegalArgumentException("candidate button is not present");
        }
        return candidateButtons.get(index);
    }

    public Button previousButton() {
        return previousButton;
    }

    public Button nextButton() {
        return nextButton;
    }

    public boolean hasPage() {
        return renderedPage != null;
    }

    /**
     * Applies a precomputed privacy decision. Hiding is destructive: plaintext is not retained for
     * later restoration when the editor changes from a sensitive field.
     */
    public void setPlaintextVisible(boolean visible) {
        plaintextVisible = visible;
        if (!visible) clear();
    }

    public void setInteractionEnabled(boolean enabled) {
        interactionEnabled = enabled;
        refreshEnabledState();
    }

    public boolean showPage(CandidatePage page) {
        Objects.requireNonNull(page, "page");
        if (!plaintextVisible) {
            clear();
            return false;
        }
        renderedPage = page;
        candidateRow.removeAllViews();
        candidateButtons.clear();
        for (int index = 0; index < page.items().size(); index++) {
            int candidateIndex = index;
            CandidatePage.Item item = page.items().get(index);
            Button button = candidateButton(index + 1, item.text());
            button.setOnClickListener(ignored -> dispatchSelection(page, candidateIndex));
            candidateButtons.add(button);
            candidateRow.addView(button, candidateParams());
        }
        previousButton.setVisibility(page.hasPreviousPage() ? View.VISIBLE : View.GONE);
        nextButton.setVisibility(page.hasNextPage() ? View.VISIBLE : View.GONE);
        refreshEnabledState();
        scroller.scrollTo(0, 0);
        root.setVisibility(View.VISIBLE);
        return true;
    }

    public void clear() {
        renderedPage = null;
        candidateButtons.clear();
        candidateRow.removeAllViews();
        previousButton.setVisibility(View.GONE);
        nextButton.setVisibility(View.GONE);
        root.setVisibility(View.GONE);
    }

    private void dispatchSelection(CandidatePage page, int candidateIndex) {
        if (!interactionEnabled || !plaintextVisible || renderedPage != page) return;
        listener.onCandidateSelected(page.selection(candidateIndex));
    }

    private void dispatchPage(CandidatePage.Direction direction) {
        CandidatePage page = renderedPage;
        if (!interactionEnabled || !plaintextVisible || page == null) return;
        if (direction == CandidatePage.Direction.PREVIOUS && !page.hasPreviousPage()) return;
        if (direction == CandidatePage.Direction.NEXT && !page.hasNextPage()) return;
        listener.onPageRequested(page.pageRequest(direction));
    }

    private void refreshEnabledState() {
        CandidatePage page = renderedPage;
        for (Button button : candidateButtons) button.setEnabled(interactionEnabled);
        previousButton.setEnabled(
                interactionEnabled && page != null && page.hasPreviousPage());
        nextButton.setEnabled(interactionEnabled && page != null && page.hasNextPage());
    }

    private Button candidateButton(int ordinal, String text) {
        Button button = baseButton();
        button.setText(context.getString(R.string.ime_candidate_numbered, ordinal, text));
        button.setContentDescription(
                context.getString(R.string.ime_cd_candidate_numbered, ordinal, text));
        button.setMinWidth(dp(MINIMUM_TOUCH_TARGET_DP));
        return button;
    }

    private Button navigationButton(String label, String contentDescription) {
        Button button = baseButton();
        button.setText(label);
        button.setContentDescription(contentDescription);
        button.setMinWidth(dp(MINIMUM_TOUCH_TARGET_DP));
        return button;
    }

    private Button baseButton() {
        Button button = new Button(context);
        button.setAllCaps(false);
        button.setSingleLine(true);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(18);
        button.setAutoSizeTextTypeUniformWithConfiguration(
                12, 18, 1, TypedValue.COMPLEX_UNIT_SP);
        button.setMinHeight(dp(MINIMUM_TOUCH_TARGET_DP));
        button.setMinimumHeight(dp(MINIMUM_TOUCH_TARGET_DP));
        button.setBackgroundResource(R.drawable.ime_key_background);
        button.setTextColor(context.getColorStateList(R.color.ime_key_text));
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setElevation(0f);
        button.setStateListAnimator(null);
        return button;
    }

    private LinearLayout.LayoutParams fixedTouchParams() {
        return new LinearLayout.LayoutParams(
                dp(MINIMUM_TOUCH_TARGET_DP), dp(MINIMUM_TOUCH_TARGET_DP));
    }

    private LinearLayout.LayoutParams candidateParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(MINIMUM_TOUCH_TARGET_DP));
        params.setMarginStart(dp(2));
        params.setMarginEnd(dp(2));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
