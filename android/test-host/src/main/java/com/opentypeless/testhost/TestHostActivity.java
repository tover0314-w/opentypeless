package com.opentypeless.testhost;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Deterministic host fields for IME instrumentation; never packaged in the production app. */
public final class TestHostActivity extends Activity {
    private LinearLayout dynamicContainer;
    private final CountDownLatch webContentReady = new CountDownLatch(1);
    private WebView webContentEditable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(16);
        fields.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText(R.string.host_title);
        title.setTextSize(24f);
        fields.addView(title, matchWrap());

        fields.addView(field(
                R.id.host_plain_text,
                R.string.host_plain_text,
                InputType.TYPE_CLASS_TEXT,
                EditorInfo.IME_ACTION_NONE,
                false));
        fields.addView(field(
                R.id.host_short_message,
                R.string.host_short_message,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE,
                EditorInfo.IME_ACTION_SEND,
                false));
        fields.addView(field(
                R.id.host_long_text,
                R.string.host_long_text,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                EditorInfo.IME_ACTION_NONE,
                true));
        fields.addView(field(
                R.id.host_person_name,
                R.string.host_person_name,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
                EditorInfo.IME_ACTION_NEXT,
                false));
        fields.addView(field(
                R.id.host_search,
                R.string.host_search,
                InputType.TYPE_CLASS_TEXT,
                EditorInfo.IME_ACTION_SEARCH,
                false));
        fields.addView(field(
                R.id.host_email,
                R.string.host_email,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                EditorInfo.IME_ACTION_NEXT,
                false));
        fields.addView(field(
                R.id.host_uri,
                R.string.host_uri,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                EditorInfo.IME_ACTION_GO,
                false));
        fields.addView(field(
                R.id.host_phone,
                R.string.host_phone,
                InputType.TYPE_CLASS_PHONE,
                EditorInfo.IME_ACTION_NEXT,
                false));
        fields.addView(field(
                R.id.host_number,
                R.string.host_number,
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL,
                EditorInfo.IME_ACTION_NEXT,
                false));
        fields.addView(field(
                R.id.host_date,
                R.string.host_date,
                InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_DATE,
                EditorInfo.IME_ACTION_NEXT,
                false));
        fields.addView(field(
                R.id.host_password,
                R.string.host_password,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                EditorInfo.IME_ACTION_DONE,
                false));
        fields.addView(field(
                R.id.host_visible_password,
                R.string.host_visible_password,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                EditorInfo.IME_ACTION_DONE,
                false));
        fields.addView(field(
                R.id.host_number_password,
                R.string.host_number_password,
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD,
                EditorInfo.IME_ACTION_DONE,
                false));
        fields.addView(field(
                R.id.host_otp,
                R.string.host_otp,
                InputType.TYPE_CLASS_NUMBER,
                EditorInfo.IME_ACTION_DONE,
                false));
        fields.addView(field(
                R.id.host_payment_card,
                R.string.host_payment_card,
                InputType.TYPE_CLASS_NUMBER,
                EditorInfo.IME_ACTION_NEXT,
                false));
        fields.addView(field(
                R.id.host_identity_number,
                R.string.host_identity_number,
                InputType.TYPE_CLASS_TEXT,
                EditorInfo.IME_ACTION_NEXT,
                false));
        fields.addView(field(
                R.id.host_no_learning,
                R.string.host_no_learning,
                InputType.TYPE_CLASS_TEXT,
                EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
                false));
        fields.addView(field(
                R.id.host_single_line_done,
                R.string.host_single_line_done,
                InputType.TYPE_CLASS_TEXT,
                EditorInfo.IME_ACTION_DONE,
                false));
        EditText rtl = field(
                R.id.host_rtl_text,
                R.string.host_rtl_text,
                InputType.TYPE_CLASS_TEXT,
                EditorInfo.IME_ACTION_DONE,
                false);
        rtl.setTextDirection(View.TEXT_DIRECTION_RTL);
        rtl.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        fields.addView(rtl, matchWrap());

        webContentEditable = webContentEditable();
        fields.addView(webContentEditable, matchWrap());

        dynamicContainer = new LinearLayout(this);
        dynamicContainer.setId(R.id.host_dynamic_container);
        dynamicContainer.setOrientation(LinearLayout.VERTICAL);
        fields.addView(dynamicContainer, matchWrap());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(fields, matchWrap());
        setContentView(scroll);
    }

    public EditText addDynamicField() {
        EditText existing = dynamicContainer.findViewById(R.id.host_dynamic_text);
        if (existing != null) return existing;
        EditText created = field(
                R.id.host_dynamic_text,
                R.string.host_dynamic_text,
                InputType.TYPE_CLASS_TEXT,
                EditorInfo.IME_ACTION_DONE,
                false);
        dynamicContainer.addView(created, matchWrap());
        return created;
    }

    public void removeDynamicField() {
        dynamicContainer.removeAllViews();
    }

    public WebView getWebContentEditable() {
        return webContentEditable;
    }

    public boolean awaitWebContentReady(long timeout, TimeUnit unit) throws InterruptedException {
        return webContentReady.await(timeout, unit);
    }

    private WebView webContentEditable() {
        WebView webView = new WebView(this);
        webView.setId(R.id.host_web_contenteditable);
        webView.setContentDescription(getString(R.string.host_web_contenteditable));
        webView.setMinimumHeight(dp(96));
        WebSettings settings = webView.getSettings();
        // Test-host-only JavaScript is required to make selection assertions deterministic. The
        // app has no INTERNET permission, loads only this literal page and blocks file/content and
        // network access, so the fixture cannot navigate or read product/user data.
        settings.setJavaScriptEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setBlockNetworkLoads(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                webContentReady.countDown();
            }
        });
        webView.loadData(
                "<!doctype html><html><body>"
                        + "<div id='editor' contenteditable='true' role='textbox' "
                        + "aria-label='Web contenteditable'>web seed</div>"
                        + "</body></html>",
                "text/html",
                "UTF-8");
        return webView;
    }

    private EditText field(int id, int hint, int inputType, int imeOptions, boolean multiLine) {
        EditText editText = new EditText(this);
        editText.setId(id);
        editText.setHint(hint);
        editText.setInputType(inputType);
        editText.setImeOptions(imeOptions);
        editText.setSingleLine(!multiLine);
        editText.setMinHeight(dp(56));
        editText.setPadding(dp(12), dp(8), dp(12), dp(8));
        return editText;
    }

    private LinearLayout.LayoutParams matchWrap() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(8);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
