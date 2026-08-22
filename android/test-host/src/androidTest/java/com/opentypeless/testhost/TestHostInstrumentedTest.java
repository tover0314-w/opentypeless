package com.opentypeless.testhost;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.app.Activity;
import android.app.Application;
import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.text.InputType;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;
import android.webkit.WebView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.json.JSONTokener;

@RunWith(AndroidJUnit4.class)
public final class TestHostInstrumentedTest {
    private Instrumentation instrumentation;
    private TestHostActivity activity;

    @Before
    public void launchHost() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        Application application = (Application) instrumentation.getTargetContext().getApplicationContext();
        CountDownLatch resumed = new CountDownLatch(1);
        AtomicReference<TestHostActivity> launched = new AtomicReference<>();
        Application.ActivityLifecycleCallbacks callbacks = new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity value, Bundle state) {}
            @Override public void onActivityStarted(Activity value) {}
            @Override public void onActivityResumed(Activity value) {
                if (value instanceof TestHostActivity host) {
                    launched.set(host);
                    resumed.countDown();
                }
            }
            @Override public void onActivityPaused(Activity value) {}
            @Override public void onActivityStopped(Activity value) {}
            @Override public void onActivitySaveInstanceState(Activity value, Bundle state) {}
            @Override public void onActivityDestroyed(Activity value) {}
        };
        application.registerActivityLifecycleCallbacks(callbacks);
        String startOutput;
        ParcelFileDescriptor descriptor = instrumentation.getUiAutomation().executeShellCommand(
                "am start -W -n com.opentypeless.testhost.debug/"
                        + "com.opentypeless.testhost.TestHostActivity");
        try (ParcelFileDescriptor.AutoCloseInputStream input =
                     new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            startOutput = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue("shell could not start TestHostActivity", startOutput.contains("Status: ok"));
        assertTrue("TestHostActivity did not resume", resumed.await(15, TimeUnit.SECONDS));
        activity = launched.get();
        application.unregisterActivityLifecycleCallbacks(callbacks);
    }

    @After
    public void closeHost() {
        if (activity == null) return;
        instrumentation.runOnMainSync(activity::finish);
    }

    @Test
    public void instrumentationSwitchesFieldsAndPreservesIndependentText() {
        EditText plain = activity.findViewById(R.id.host_plain_text);
        EditText longText = activity.findViewById(R.id.host_long_text);

        instrumentation.runOnMainSync(() -> {
            plain.setText("alpha beta");
            plain.requestFocus();
            plain.setSelection(0, 5);
        });
        instrumentation.waitForIdleSync();
        assertTrue(plain.hasFocus());
        assertEquals("alpha", plain.getText().subSequence(
                plain.getSelectionStart(), plain.getSelectionEnd()).toString());

        instrumentation.runOnMainSync(() -> {
            longText.setText("first\nsecond");
            longText.requestFocus();
            longText.setSelection(longText.length());
        });
        instrumentation.waitForIdleSync();
        assertTrue(longText.hasFocus());
        assertEquals("alpha beta", plain.getText().toString());
        assertEquals("first\nsecond", longText.getText().toString());
        assertEquals(longText.length(), longText.getSelectionStart());
        assertEquals("collapsed selection must not be reported as a selected range",
                longText.getSelectionStart(), longText.getSelectionEnd());
    }

    @Test
    public void instrumentationCreatesSelectsAndDestroysDynamicField() {
        final EditText[] dynamic = new EditText[1];
        instrumentation.runOnMainSync(() -> {
            dynamic[0] = activity.addDynamicField();
            dynamic[0].setText("dynamic value");
            dynamic[0].requestFocus();
            dynamic[0].setSelection(8, 13);
        });
        instrumentation.waitForIdleSync();
        assertEquals("value", dynamic[0].getText().subSequence(
                dynamic[0].getSelectionStart(), dynamic[0].getSelectionEnd()).toString());

        instrumentation.runOnMainSync(activity::removeDynamicField);
        instrumentation.waitForIdleSync();
        assertNull(activity.findViewById(R.id.host_dynamic_text));

        final EditText[] recreated = new EditText[1];
        instrumentation.runOnMainSync(() -> recreated[0] = activity.addDynamicField());
        instrumentation.waitForIdleSync();
        assertNotSame("destroyed dynamic editor must not be reused", dynamic[0], recreated[0]);
        assertEquals("new editor must not inherit the previous editor text",
                "", recreated[0].getText().toString());
    }

    @Test
    public void hostExposesRepresentativeInputTypes() {
        EditText message = activity.findViewById(R.id.host_short_message);
        EditText email = activity.findViewById(R.id.host_email);
        EditText uri = activity.findViewById(R.id.host_uri);
        EditText phone = activity.findViewById(R.id.host_phone);
        EditText number = activity.findViewById(R.id.host_number);
        EditText date = activity.findViewById(R.id.host_date);
        EditText password = activity.findViewById(R.id.host_password);
        EditText visiblePassword = activity.findViewById(R.id.host_visible_password);
        EditText numberPassword = activity.findViewById(R.id.host_number_password);
        EditText otp = activity.findViewById(R.id.host_otp);
        EditText payment = activity.findViewById(R.id.host_payment_card);
        EditText identity = activity.findViewById(R.id.host_identity_number);
        EditText noLearning = activity.findViewById(R.id.host_no_learning);
        EditText singleLineDone = activity.findViewById(R.id.host_single_line_done);
        EditText rtl = activity.findViewById(R.id.host_rtl_text);
        assertEquals(
                InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE,
                message.getInputType() & InputType.TYPE_MASK_VARIATION);
        assertEquals(
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                password.getInputType() & InputType.TYPE_MASK_VARIATION);
        assertEquals(
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                visiblePassword.getInputType() & InputType.TYPE_MASK_VARIATION);
        assertEquals(InputType.TYPE_CLASS_NUMBER,
                numberPassword.getInputType() & InputType.TYPE_MASK_CLASS);
        assertEquals(
                InputType.TYPE_NUMBER_VARIATION_PASSWORD,
                numberPassword.getInputType() & InputType.TYPE_MASK_VARIATION);
        assertEquals(
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                email.getInputType() & InputType.TYPE_MASK_VARIATION);
        assertEquals(
                InputType.TYPE_TEXT_VARIATION_URI,
                uri.getInputType() & InputType.TYPE_MASK_VARIATION);
        assertEquals(InputType.TYPE_CLASS_PHONE, phone.getInputType() & InputType.TYPE_MASK_CLASS);
        assertEquals(InputType.TYPE_CLASS_NUMBER, number.getInputType() & InputType.TYPE_MASK_CLASS);
        assertEquals(InputType.TYPE_CLASS_DATETIME, date.getInputType() & InputType.TYPE_MASK_CLASS);
        assertEquals(InputType.TYPE_CLASS_NUMBER, otp.getInputType() & InputType.TYPE_MASK_CLASS);
        assertEquals(
                InputType.TYPE_CLASS_NUMBER,
                payment.getInputType() & InputType.TYPE_MASK_CLASS);
        assertEquals(InputType.TYPE_CLASS_TEXT,
                identity.getInputType() & InputType.TYPE_MASK_CLASS);
        assertTrue((noLearning.getImeOptions()
                & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0);
        assertEquals(EditorInfo.IME_ACTION_DONE,
                singleLineDone.getImeOptions() & EditorInfo.IME_MASK_ACTION);
        assertTrue(singleLineDone.isSingleLine());
        assertEquals(android.view.View.LAYOUT_DIRECTION_RTL, rtl.getLayoutDirection());
        assertEquals(android.view.View.TEXT_DIRECTION_RTL, rtl.getTextDirection());
    }

    @Test
    public void webContentEditableSupportsFocusTextAndSelection() throws Exception {
        assertTrue("contenteditable fixture did not finish loading",
                activity.awaitWebContentReady(10, TimeUnit.SECONDS));
        WebView webView = activity.getWebContentEditable();
        CountDownLatch resultReady = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            webView.requestFocus();
            webView.evaluateJavascript(
                    "(function(){"
                            + "const e=document.getElementById('editor');"
                            + "e.textContent='alpha beta';e.focus();"
                            + "const r=document.createRange();"
                            + "r.setStart(e.firstChild,0);r.setEnd(e.firstChild,5);"
                            + "const s=window.getSelection();s.removeAllRanges();s.addRange(r);"
                            + "return e.textContent+'|'+s.toString()+'|'+document.activeElement.id;"
                            + "})()",
                    value -> {
                        result.set(value);
                        resultReady.countDown();
                    });
        });
        assertTrue("contenteditable JavaScript result timed out",
                resultReady.await(10, TimeUnit.SECONDS));
        assertTrue("WebView did not retain focus", webView.hasFocus());
        assertEquals("alpha beta|alpha|editor",
                String.valueOf(new JSONTokener(result.get()).nextValue()));
        EditorInfo webEditorInfo = new EditorInfo();
        AtomicReference<InputConnection> webConnection = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            assertTrue("focused contenteditable must identify as a text editor",
                    webView.onCheckIsTextEditor());
            webConnection.set(webView.onCreateInputConnection(webEditorInfo));
        });
        assertNotNull("focused contenteditable must expose an InputConnection",
                webConnection.get());
        assertEquals(InputType.TYPE_CLASS_TEXT,
                webEditorInfo.inputType & InputType.TYPE_MASK_CLASS);
    }

    @Test
    public void selectedImeTreatsOtpPaymentAndIdentityAsSensitiveWhenRequested()
            throws Exception {
        String expectedPackage = InstrumentationRegistry.getArguments()
                .getString("imeSensitiveFieldPackage");
        Assume.assumeTrue("candidate-specific SEC-002 check was not requested",
                expectedPackage != null && !expectedPackage.isBlank());
        awaitHostWindowFocus();

        UiAutomation automation = instrumentation.getUiAutomation();
        AccessibilityServiceInfo serviceInfo = automation.getServiceInfo();
        serviceInfo.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        automation.setServiceInfo(serviceInfo);

        assertFieldProfile(automation, expectedPackage, R.id.host_otp,
                "Password keyboard", "密码键盘", false);
        assertFieldProfile(automation, expectedPackage, R.id.host_payment_card,
                "Password keyboard", "密码键盘", false);
        assertFieldProfile(automation, expectedPackage, R.id.host_identity_number,
                "Password keyboard", "密码键盘", false);
        assertFieldProfile(automation, expectedPackage, R.id.host_visible_password,
                "Password keyboard", "密码键盘", true);
        assertFieldProfile(automation, expectedPackage, R.id.host_number_password,
                "Password keyboard", "密码键盘", true);
    }

    @Test
    public void selectedImeAutomaticallySwitchesEverySpecializedFieldLayoutWhenRequested()
            throws Exception {
        String expectedPackage = InstrumentationRegistry.getArguments()
                .getString("imeFieldLayoutPackage");
        Assume.assumeTrue("candidate-specific KBD-004 check was not requested",
                expectedPackage != null && !expectedPackage.isBlank());
        awaitHostWindowFocus();

        UiAutomation automation = instrumentation.getUiAutomation();
        AccessibilityServiceInfo serviceInfo = automation.getServiceInfo();
        serviceInfo.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        automation.setServiceInfo(serviceInfo);

        assertFieldProfile(automation, expectedPackage, R.id.host_email,
                "Email keyboard", "邮箱键盘", false);
        assertFieldProfile(automation, expectedPackage, R.id.host_uri,
                "Web address keyboard", "网址键盘", false);
        assertFieldProfile(automation, expectedPackage, R.id.host_phone,
                "Phone keyboard", "电话键盘", false);
        assertFieldProfile(automation, expectedPackage, R.id.host_number,
                "Number keyboard", "数字键盘", false);
        assertFieldProfile(automation, expectedPackage, R.id.host_date,
                "Date keyboard", "日期键盘", false);
        assertFieldProfile(automation, expectedPackage, R.id.host_password,
                "Password keyboard", "密码键盘", true);
        assertFieldProfile(automation, expectedPackage, R.id.host_visible_password,
                "Password keyboard", "密码键盘", true);
        assertFieldProfile(automation, expectedPackage, R.id.host_number_password,
                "Password keyboard", "密码键盘", true);
    }

    @Test
    public void selectedImeAccessibilityTreeExposesLabeledActionsWhenRequested() throws Exception {
        String expectedPackage = InstrumentationRegistry.getArguments()
                .getString("imeAccessibilityPackage");
        String requiredLabelRegex = InstrumentationRegistry.getArguments()
                .getString("imeRequiredLabelRegex");
        boolean requireDescribedScreenReaderActions = Boolean.parseBoolean(
                InstrumentationRegistry.getArguments()
                        .getString("requireDescribedScreenReaderActions", "false"));
        Assume.assumeTrue("candidate-specific KSP-009 check was not requested",
                expectedPackage != null && !expectedPackage.isBlank());

        awaitHostWindowFocus();
        EditText plain = activity.findViewById(R.id.host_plain_text);
        AtomicReference<int[]> fieldCenter = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            plain.requestFocus();
            int[] location = new int[2];
            plain.getLocationOnScreen(location);
            fieldCenter.set(new int[] {
                    location[0] + plain.getWidth() / 2,
                    location[1] + plain.getHeight() / 2
            });
            InputMethodManager inputMethodManager =
                    (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            inputMethodManager.showSoftInput(plain, InputMethodManager.SHOW_IMPLICIT);
        });

        UiAutomation automation = instrumentation.getUiAutomation();
        AccessibilityServiceInfo serviceInfo = automation.getServiceInfo();
        serviceInfo.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        automation.setServiceInfo(serviceInfo);
        int[] center = fieldCenter.get();
        ParcelFileDescriptor tap = automation.executeShellCommand(
                "input tap " + center[0] + " " + center[1]);
        try (ParcelFileDescriptor.AutoCloseInputStream input =
                     new ParcelFileDescriptor.AutoCloseInputStream(tap)) {
            input.readAllBytes();
        }
        automation.waitForIdle(500, 5_000);
        Pattern requiredLabelPattern = requiredLabelRegex == null || requiredLabelRegex.isBlank()
                ? null
                : Pattern.compile(requiredLabelRegex,
                        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        AccessibilitySummary summary = waitForInputMethodSummary(
                automation, expectedPackage, requiredLabelPattern);
        assertTrue("no input-method accessibility window for " + expectedPackage,
                summary.windowFound);
        assertTrue("input-method nodes do not belong to " + expectedPackage + ": " + summary,
                summary.expectedPackageFound);
        assertTrue("input-method accessibility tree exposes too few labeled visible nodes: "
                        + summary,
                summary.labeledVisibleNodes >= 20);
        if (requireDescribedScreenReaderActions) {
            assertEquals(
                    "every screen-reader-focusable action needs a label or labeled descendant: "
                            + summary,
                    summary.screenReaderFocusableActionableNodes,
                    summary.describedScreenReaderFocusableActionableNodes);
        }
        assertEquals("every leaf action needs text or contentDescription: " + summary,
                summary.actionableLeafNodes, summary.labeledActionableLeafNodes);
        if (requiredLabelPattern != null) {
            assertTrue("input-method tree lacks required label /" + requiredLabelRegex + "/: "
                            + summary,
                    summary.requiredLabelMatches > 0);
        }
        System.out.println("KSP009_IME_A11Y " + summary);
    }

    @Test
    public void selectedImeToolbarKeepsFixedFortyEightDpActionsWhenRequested() throws Exception {
        String expectedPackage = InstrumentationRegistry.getArguments()
                .getString("imeToolbarPackage");
        Assume.assumeTrue("candidate-specific KBD-006 check was not requested",
                expectedPackage != null && !expectedPackage.isBlank());
        awaitHostWindowFocus();

        EditText plain = activity.findViewById(R.id.host_plain_text);
        instrumentation.runOnMainSync(() -> {
            plain.requestFocus();
            InputMethodManager manager =
                    (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            manager.restartInput(plain);
            manager.showSoftInput(plain, InputMethodManager.SHOW_IMPLICIT);
        });
        UiAutomation automation = instrumentation.getUiAutomation();
        AccessibilityServiceInfo serviceInfo = automation.getServiceInfo();
        serviceInfo.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        automation.setServiceInfo(serviceInfo);

        boolean[] found = new boolean[3];
        // Accessibility window coordinates can contract an exact View edge by one physical
        // pixel during OEM window composition. The production View test and source gate retain
        // the exact 48dp requirement; this system-level readback tolerates only that 1px rounding.
        int minimumPx = Math.max(
                1,
                Math.round(48f * activity.getResources().getDisplayMetrics().density) - 1);
        long deadline = SystemClock.uptimeMillis() + 5_000L;
        do {
            scanToolbarActions(automation, expectedPackage, minimumPx, found);
            if (found[0] && found[1] && found[2]) break;
            SystemClock.sleep(100L);
        } while (SystemClock.uptimeMillis() < deadline);

        assertTrue("mode action missing from selected IME toolbar", found[0]);
        assertTrue("long-dictation action missing from selected IME toolbar", found[1]);
        assertTrue("overflow action missing from selected IME toolbar", found[2]);
    }

    @Test
    public void selectedImeExposesVoiceFirstAndReachableQwertyTabsWhenRequested()
            throws Exception {
        String expectedPackage = InstrumentationRegistry.getArguments()
                .getString("imeInputTabsPackage");
        Assume.assumeTrue("candidate-specific KBD-002 tab check was not requested",
                expectedPackage != null && !expectedPackage.isBlank());
        awaitHostWindowFocus();
        focusField(R.id.host_plain_text);

        UiAutomation automation = instrumentation.getUiAutomation();
        AccessibilityServiceInfo serviceInfo = automation.getServiceInfo();
        serviceInfo.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        automation.setServiceInfo(serviceInfo);
        focusField(R.id.host_plain_text);

        awaitImeLabel(automation, expectedPackage, Set.of(
                "Open voice input", "打开语音输入"));
        awaitImeLabel(automation, expectedPackage, Set.of(
                "Start continuous long-text dictation",
                "开始持续长文本听写",
                "Long dictation is temporarily unavailable",
                "长文本听写暂时不可用"));
        activateImeNode(automation, expectedPackage, Set.of(
                "Open the QWERTY keyboard", "打开 QWERTY 键盘"), false);
        awaitImeLabel(automation, expectedPackage, Set.of("q"));
        awaitImeLabel(automation, expectedPackage, Set.of("Delete", "删除"));
    }

    @Test
    public void selectedImeHidesSensitiveToolbarAndRestoresOrdinaryWhenRequested()
            throws Exception {
        String expectedPackage = InstrumentationRegistry.getArguments()
                .getString("imeSensitiveToolbarPackage");
        Assume.assumeTrue("candidate-specific SEC-005 check was not requested",
                expectedPackage != null && !expectedPackage.isBlank());
        awaitHostWindowFocus();

        // Establish the first served editor before changing UiAutomation's accessibility
        // flags. On some framework/OEM builds, refreshing those flags while no IME window is
        // served can make the immediately following implicit show request a no-op.
        focusField(R.id.host_plain_text);

        UiAutomation automation = instrumentation.getUiAutomation();
        AccessibilityServiceInfo serviceInfo = automation.getServiceInfo();
        serviceInfo.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        automation.setServiceInfo(serviceInfo);

        // Refreshing UiAutomation's service info may recreate its accessibility connection and
        // dismiss a just-created input window. Re-serve the same ordinary field after the flags
        // are stable; focusField deliberately separates restartInput and showSoftInput.
        focusField(R.id.host_plain_text);
        assertToolbarPrivacyState(
                automation, expectedPackage, R.id.host_plain_text, true, false);
        focusField(R.id.host_otp);
        assertToolbarPrivacyState(automation, expectedPackage, R.id.host_otp, false, true);
        focusField(R.id.host_no_learning);
        assertToolbarPrivacyState(
                automation, expectedPackage, R.id.host_no_learning, true, false);
        focusField(R.id.host_plain_text);
        assertToolbarPrivacyState(
                automation, expectedPackage, R.id.host_plain_text, true, false);
    }

    @Test
    public void selectedImeClipboardPastesCurrentTextAndHidesInSensitiveFieldWhenRequested()
            throws Exception {
        String expectedPackage = InstrumentationRegistry.getArguments()
                .getString("imeClipboardPackage");
        Assume.assumeTrue("candidate-specific KBD-011 check was not requested",
                expectedPackage != null && !expectedPackage.isBlank());
        awaitHostWindowFocus();

        ClipboardManager clipboard =
                (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        assertNotNull("test host clipboard service is unavailable", clipboard);
        instrumentation.runOnMainSync(() -> clipboard.setPrimaryClip(
                ClipData.newPlainText("KBD-011 fixture", "clipboard fixture")));
        try {
            focusField(R.id.host_plain_text);
            UiAutomation automation = instrumentation.getUiAutomation();
            AccessibilityServiceInfo serviceInfo = automation.getServiceInfo();
            serviceInfo.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            automation.setServiceInfo(serviceInfo);
            focusField(R.id.host_plain_text);

            activateImeNode(automation, expectedPackage, Set.of(
                    "More voice keyboard actions", "更多语音键盘操作"), false);
            awaitPackageLabel(automation, expectedPackage, Set.of("Clipboard", "剪贴板"));
            activatePackageNode(
                    automation, expectedPackage, Set.of("Clipboard", "剪贴板"), true);
            awaitImeLabel(automation, expectedPackage, Set.of("clipboard fixture"));
            activateImeNode(
                    automation, expectedPackage, Set.of("clipboard fixture"), true);
            assertPlainTextEventually("clipboard fixture", automation, expectedPackage);

            focusField(R.id.host_otp);
            activateImeNode(automation, expectedPackage, Set.of(
                    "More voice keyboard actions", "更多语音键盘操作"), false);
            awaitPackageLabel(automation, expectedPackage, Set.of("Settings", "设置"));
            Set<String> sensitiveMenu = packageLabels(automation, expectedPackage);
            assertFalse("sensitive More menu exposed clipboard: " + sensitiveMenu,
                    sensitiveMenu.contains("Clipboard") || sensitiveMenu.contains("剪贴板"));
        } finally {
            instrumentation.runOnMainSync(clipboard::clearPrimaryClip);
        }
    }

    @Test
    public void selectedImeRimePreeditUsesRealKeyboardRouteWhenRequested() throws Exception {
        String expectedPackage = InstrumentationRegistry.getArguments()
                .getString("imeRimePackage");
        Assume.assumeTrue("RIM-004 system IME check was not requested",
                expectedPackage != null && !expectedPackage.isBlank());
        awaitHostWindowFocus();
        focusField(R.id.host_plain_text);

        UiAutomation automation = instrumentation.getUiAutomation();
        AccessibilityServiceInfo serviceInfo = automation.getServiceInfo();
        serviceInfo.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        automation.setServiceInfo(serviceInfo);
        focusField(R.id.host_plain_text);

        clickImeNode(automation, expectedPackage, Set.of("a"), true);
        assertPlainTextEventually("a", automation, expectedPackage);
        EditText plain = activity.findViewById(R.id.host_plain_text);
        instrumentation.runOnMainSync(() -> plain.setText(""));
        focusField(R.id.host_plain_text);
        awaitImeLabel(
                automation,
                expectedPackage,
                Set.of(
                        "Latin input active; switch to Chinese input",
                        "当前为拉丁输入；切换到中文输入"));
        clickImeNode(
                automation,
                expectedPackage,
                Set.of(
                        "Latin input active; switch to Chinese input",
                        "当前为拉丁输入；切换到中文输入"),
                false);
        awaitImeLabel(
                automation,
                expectedPackage,
                Set.of(
                        "Chinese input active; switch to Latin input",
                        "当前为中文输入；切换到拉丁输入"));
        clickImeNode(automation, expectedPackage, Set.of("n"), true);
        assertPlainTextEventually("n", automation, expectedPackage);
        clickImeNode(automation, expectedPackage, Set.of("i"), true);
        assertPlainTextEventually("ni", automation, expectedPackage);
        clickImeNode(automation, expectedPackage, Set.of("Delete", "删除"), false);
        assertPlainTextEventually("n", automation, expectedPackage);
        clickImeNode(
                automation,
                expectedPackage,
                Set.of(
                        "Chinese input active; switch to Latin input",
                        "当前为中文输入；切换到拉丁输入"),
                false);
        assertPlainTextEventually("n", automation, expectedPackage);
    }

    @Test
    public void selectedImeRimeCandidatePagingCommitsExactSelectionWhenRequested()
            throws Exception {
        String expectedPackage = InstrumentationRegistry.getArguments()
                .getString("imeRimeCandidatePackage");
        Assume.assumeTrue("RIM-005 system IME check was not requested",
                expectedPackage != null && !expectedPackage.isBlank());
        awaitHostWindowFocus();
        focusField(R.id.host_plain_text);

        UiAutomation automation = instrumentation.getUiAutomation();
        AccessibilityServiceInfo serviceInfo = automation.getServiceInfo();
        serviceInfo.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        automation.setServiceInfo(serviceInfo);
        EditText plain = activity.findViewById(R.id.host_plain_text);
        instrumentation.runOnMainSync(() -> plain.setText(""));
        focusField(R.id.host_plain_text);

        Set<String> latinActive = Set.of(
                "Latin input active; switch to Chinese input",
                "当前为拉丁输入；切换到中文输入");
        Set<String> rimeActive = Set.of(
                "Chinese input active; switch to Latin input",
                "当前为中文输入；切换到拉丁输入");
        Set<String> eitherEngine = new TreeSet<>();
        eitherEngine.addAll(latinActive);
        eitherEngine.addAll(rimeActive);
        awaitImeLabel(automation, expectedPackage, eitherEngine);
        if (inputMethodLabels(automation, expectedPackage).stream()
                .anyMatch(latinActive::contains)) {
            activateImeNode(automation, expectedPackage, latinActive, false);
        }
        awaitImeLabel(automation, expectedPackage, rimeActive);
        activateImeNode(automation, expectedPackage, Set.of("n"), true);
        assertPlainTextEventually("n", automation, expectedPackage);
        activateImeNode(automation, expectedPackage, Set.of("i"), true);
        assertPlainTextEventually("ni", automation, expectedPackage);
        awaitImeLabel(automation, expectedPackage, Set.of(
                "Candidate 1: 甲", "第 1 个候选：甲"));
        activateImeNode(automation, expectedPackage, Set.of(
                "Next candidate page", "下一页候选"), false);
        awaitImeLabel(automation, expectedPackage, Set.of(
                "Candidate 1: 己", "第 1 个候选：己"));
        activateImeNode(automation, expectedPackage, Set.of(
                "Candidate 2: 庚", "第 2 个候选：庚"), false);
        assertPlainTextEventually("庚", automation, expectedPackage);
    }

    private void assertPlainTextEventually(
            String expected, UiAutomation automation, String expectedPackage) {
        EditText plain = activity.findViewById(R.id.host_plain_text);
        long deadline = SystemClock.uptimeMillis() + 8_000L;
        do {
            AtomicReference<String> value = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> value.set(plain.getText().toString()));
            if (expected.equals(value.get())) return;
            SystemClock.sleep(100L);
        } while (SystemClock.uptimeMillis() < deadline);
        AtomicReference<String> actual = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> actual.set(plain.getText().toString()));
        assertEquals(
                "unexpected IME text; labels="
                        + inputMethodLabels(automation, expectedPackage),
                expected,
                actual.get());
    }

    private void awaitImeLabel(
            UiAutomation automation, String expectedPackage, Set<String> labels) {
        long deadline = SystemClock.uptimeMillis() + 8_000L;
        Set<String> observed = Set.of();
        do {
            observed = inputMethodLabels(automation, expectedPackage);
            if (observed.stream().anyMatch(labels::contains)) return;
            SystemClock.sleep(100L);
        } while (SystemClock.uptimeMillis() < deadline);
        assertTrue("input method label not found: " + labels + "; observed=" + observed, false);
    }

    private void clickImeNode(
            UiAutomation automation,
            String expectedPackage,
            Set<String> labels,
            boolean matchText) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 8_000L;
        do {
            for (AccessibilityWindowInfo window : automation.getWindows()) {
                if (window.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                Deque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
                pending.add(root);
                while (!pending.isEmpty()) {
                    AccessibilityNodeInfo node = pending.removeFirst();
                    String value = String.valueOf(
                            matchText ? node.getText() : node.getContentDescription());
                    if (expectedPackage.equals(String.valueOf(node.getPackageName()))
                            && labels.contains(value)
                            && node.isVisibleToUser()
                            && node.isClickable()) {
                        Rect bounds = new Rect();
                        node.getBoundsInScreen(bounds);
                        assertTrue("IME node has empty bounds: " + value, !bounds.isEmpty());
                        ParcelFileDescriptor tap = automation.executeShellCommand(
                                "input tap " + bounds.centerX() + " " + bounds.centerY());
                        try (ParcelFileDescriptor.AutoCloseInputStream input =
                                     new ParcelFileDescriptor.AutoCloseInputStream(tap)) {
                            input.readAllBytes();
                        }
                        automation.waitForIdle(100L, 2_000L);
                        return;
                    }
                    for (int index = 0; index < node.getChildCount(); index++) {
                        AccessibilityNodeInfo child = node.getChild(index);
                        if (child != null) pending.addLast(child);
                    }
                }
            }
            SystemClock.sleep(100L);
        } while (SystemClock.uptimeMillis() < deadline);
        assertTrue("input method node not found: " + labels, false);
    }

    /** Deterministic contract activation; external ADB touch is recorded separately. */
    private void activateImeNode(
            UiAutomation automation,
            String expectedPackage,
            Set<String> labels,
            boolean matchText) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 8_000L;
        do {
            for (AccessibilityWindowInfo window : automation.getWindows()) {
                if (window.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                Deque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
                pending.add(root);
                while (!pending.isEmpty()) {
                    AccessibilityNodeInfo node = pending.removeFirst();
                    String value = String.valueOf(
                            matchText ? node.getText() : node.getContentDescription());
                    if (expectedPackage.equals(String.valueOf(node.getPackageName()))
                            && labels.contains(value)
                            && node.isVisibleToUser()
                            && node.isEnabled()
                            && node.isClickable()) {
                        assertTrue("IME node rejected ACTION_CLICK: " + value,
                                node.performAction(AccessibilityNodeInfo.ACTION_CLICK));
                        automation.waitForIdle(100L, 2_000L);
                        return;
                    }
                    for (int index = 0; index < node.getChildCount(); index++) {
                        AccessibilityNodeInfo child = node.getChild(index);
                        if (child != null) pending.addLast(child);
                    }
                }
            }
            SystemClock.sleep(100L);
        } while (SystemClock.uptimeMillis() < deadline);
        assertTrue("input method node not found: " + labels, false);
    }

    private void activatePackageNode(
            UiAutomation automation,
            String expectedPackage,
            Set<String> labels,
            boolean matchText) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 8_000L;
        do {
            for (AccessibilityWindowInfo window : automation.getWindows()) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                Deque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
                pending.add(root);
                while (!pending.isEmpty()) {
                    AccessibilityNodeInfo node = pending.removeFirst();
                    String value = String.valueOf(
                            matchText ? node.getText() : node.getContentDescription());
                    if (expectedPackage.equals(String.valueOf(node.getPackageName()))
                            && labels.contains(value)
                            && node.isVisibleToUser()) {
                        Rect bounds = new Rect();
                        node.getBoundsInScreen(bounds);
                        assertTrue("package node has empty bounds: " + value, !bounds.isEmpty());
                        ParcelFileDescriptor tap = automation.executeShellCommand(
                                "input tap " + bounds.centerX() + " " + bounds.centerY());
                        try (ParcelFileDescriptor.AutoCloseInputStream input =
                                     new ParcelFileDescriptor.AutoCloseInputStream(tap)) {
                            input.readAllBytes();
                        }
                        automation.waitForIdle(100L, 2_000L);
                        return;
                    }
                    for (int index = 0; index < node.getChildCount(); index++) {
                        AccessibilityNodeInfo child = node.getChild(index);
                        if (child != null) pending.addLast(child);
                    }
                }
            }
            SystemClock.sleep(100L);
        } while (SystemClock.uptimeMillis() < deadline);
        assertTrue("package node not found: " + labels, false);
    }

    private void awaitPackageLabel(
            UiAutomation automation, String expectedPackage, Set<String> labels) {
        long deadline = SystemClock.uptimeMillis() + 8_000L;
        Set<String> observed = Set.of();
        do {
            observed = packageLabels(automation, expectedPackage);
            if (observed.stream().anyMatch(labels::contains)) return;
            SystemClock.sleep(100L);
        } while (SystemClock.uptimeMillis() < deadline);
        assertTrue("package label not found: " + labels + "; observed=" + observed, false);
    }

    private Set<String> packageLabels(UiAutomation automation, String expectedPackage) {
        Set<String> labels = new TreeSet<>();
        for (AccessibilityWindowInfo window : automation.getWindows()) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) continue;
            Deque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
            pending.add(root);
            while (!pending.isEmpty()) {
                AccessibilityNodeInfo node = pending.removeFirst();
                if (expectedPackage.equals(String.valueOf(node.getPackageName()))) {
                    String text = String.valueOf(node.getText());
                    String description = String.valueOf(node.getContentDescription());
                    if (!"null".equals(text) && !text.isBlank()) labels.add(text);
                    if (!"null".equals(description) && !description.isBlank()) {
                        labels.add(description);
                    }
                }
                for (int index = 0; index < node.getChildCount(); index++) {
                    AccessibilityNodeInfo child = node.getChild(index);
                    if (child != null) pending.addLast(child);
                }
            }
        }
        return labels;
    }

    private void focusField(int fieldId) {
        EditText field = activity.findViewById(fieldId);
        instrumentation.runOnMainSync(() -> {
            field.requestFocus();
            field.setSelection(field.length());
            InputMethodManager manager =
                    (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            manager.restartInput(field);
        });
        // Let the focus/restart transaction establish the served view before requesting the
        // input window. Calling both in one UI turn is racy immediately after selecting or
        // reinstalling an IME and can yield no TYPE_INPUT_METHOD window on otherwise healthy
        // devices.
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> {
            InputMethodManager manager =
                    (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            manager.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
        });
        instrumentation.waitForIdleSync();
    }

    private void assertToolbarPrivacyState(
            UiAutomation automation,
            String expectedPackage,
            int fieldId,
            boolean voiceVisible,
            boolean sensitiveStatusVisible) {
        long deadline = SystemClock.uptimeMillis() + 5_000L;
        long nextShowRetry = 0L;
        Set<String> labels = Set.of();
        do {
            labels = inputMethodLabels(automation, expectedPackage);
            boolean hasMode = labels.stream().anyMatch(value ->
                    value.startsWith("Voice processing mode:")
                            || value.startsWith("当前语音处理模式："));
            boolean hasVoice = labels.contains("Start continuous long-text dictation")
                    || labels.contains("开始持续长文本听写");
            boolean hasMore = labels.contains("More voice keyboard actions")
                    || labels.contains("更多语音键盘操作");
            boolean hasSensitiveStatus = labels.contains(
                    "Voice input disabled in this sensitive field")
                    || labels.contains("当前敏感字段已禁用语音输入");
            if (hasMore
                    && hasMode == voiceVisible
                    && hasVoice == voiceVisible
                    && hasSensitiveStatus == sensitiveStatusVisible) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (labels.isEmpty() && now >= nextShowRetry) {
                // A freshly installed/selected IME can finish binding after the first show
                // request. Reissue only while the input-method window is absent; a visible but
                // incorrect toolbar is never retried away and still fails the assertion.
                focusField(fieldId);
                nextShowRetry = now + 500L;
            }
            SystemClock.sleep(100L);
        } while (SystemClock.uptimeMillis() < deadline);
        assertTrue("selected IME toolbar privacy mismatch: " + labels, false);
    }

    private void scanToolbarActions(
            UiAutomation automation,
            String expectedPackage,
            int minimumPx,
            boolean[] found) {
        for (AccessibilityWindowInfo window : automation.getWindows()) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) continue;
            Deque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
            pending.add(root);
            while (!pending.isEmpty()) {
                AccessibilityNodeInfo node = pending.removeFirst();
                String description = String.valueOf(node.getContentDescription());
                int match = description.startsWith("Voice processing mode:")
                        || description.startsWith("当前语音处理模式：")
                        ? 0
                        : description.equals("Start continuous long-text dictation")
                        || description.equals("开始持续长文本听写")
                        ? 1
                        : description.equals("More voice keyboard actions")
                        || description.equals("更多语音键盘操作")
                        ? 2
                        : -1;
                if (match >= 0
                        && expectedPackage.equals(String.valueOf(node.getPackageName()))
                        && node.isVisibleToUser()) {
                    Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);
                    assertTrue("toolbar action width below 48dp: " + description + " " + bounds,
                            bounds.width() >= minimumPx);
                    assertTrue("toolbar action height below 48dp: " + description + " " + bounds,
                            bounds.height() >= minimumPx);
                    found[match] = true;
                }
                for (int index = 0; index < node.getChildCount(); index++) {
                    AccessibilityNodeInfo child = node.getChild(index);
                    if (child != null) pending.addLast(child);
                }
            }
        }
    }

    private void awaitHostWindowFocus() throws InterruptedException {
        CountDownLatch focused = new CountDownLatch(1);
        instrumentation.runOnMainSync(() -> {
            if (activity.hasWindowFocus()) {
                focused.countDown();
                return;
            }
            ViewTreeObserver observer = activity.getWindow().getDecorView().getViewTreeObserver();
            ViewTreeObserver.OnWindowFocusChangeListener[] listener =
                    new ViewTreeObserver.OnWindowFocusChangeListener[1];
            listener[0] = hasFocus -> {
                if (!hasFocus) return;
                if (observer.isAlive()) observer.removeOnWindowFocusChangeListener(listener[0]);
                focused.countDown();
            };
            observer.addOnWindowFocusChangeListener(listener[0]);
        });
        assertTrue("TestHostActivity did not gain window focus",
                focused.await(5, TimeUnit.SECONDS));
    }

    private void assertFieldProfile(
            UiAutomation automation,
            String expectedPackage,
            int fieldId,
            String englishLabel,
            String chineseLabel,
            boolean allowXiaomiSecurityKeyboard) {
        EditText field = activity.findViewById(fieldId);
        instrumentation.runOnMainSync(() -> {
            field.requestFocus();
            field.setSelection(field.length());
            InputMethodManager inputMethodManager =
                    (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            inputMethodManager.restartInput(field);
            inputMethodManager.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
        });
        instrumentation.waitForIdleSync();
        long deadline = SystemClock.uptimeMillis() + 5_000L;
        do {
            if (inputMethodHasExactLabel(
                    automation, expectedPackage, Set.of(englishLabel, chineseLabel))) {
                return;
            }
            SystemClock.sleep(100L);
        } while (SystemClock.uptimeMillis() < deadline);
        if (allowXiaomiSecurityKeyboard
                && xiaomiSecurityKeyboardIsActive(automation, fieldId)) return;
        assertTrue("IME did not expose expected field profile " + englishLabel
                + "; labels=" + inputMethodLabels(automation, expectedPackage), false);
    }

    private boolean xiaomiSecurityKeyboardIsActive(UiAutomation automation, int fieldId) {
        if (!"Xiaomi".equalsIgnoreCase(Build.MANUFACTURER)) return false;
        ParcelFileDescriptor descriptor = automation.executeShellCommand("dumpsys input_method");
        try (ParcelFileDescriptor.AutoCloseInputStream input =
                     new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            String state = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            String resourceName = activity.getResources().getResourceEntryName(fieldId);
            return state.contains("mCurId=com.miui.securityinputmethod/")
                    && state.contains("mServedView=android.widget.EditText")
                    && state.contains(resourceName);
        } catch (java.io.IOException ignored) {
            return false;
        }
    }

    private boolean inputMethodHasExactLabel(
            UiAutomation automation, String expectedPackage, Set<String> expectedLabels) {
        Set<String> labels = inputMethodLabels(automation, expectedPackage);
        for (String expected : expectedLabels) {
            if (labels.contains(expected)) return true;
        }
        return false;
    }

    private Set<String> inputMethodLabels(UiAutomation automation, String expectedPackage) {
        Set<String> labels = new TreeSet<>();
        for (AccessibilityWindowInfo window : automation.getWindows()) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) continue;
            Deque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
            pending.add(root);
            while (!pending.isEmpty()) {
                AccessibilityNodeInfo node = pending.removeFirst();
                if (expectedPackage.equals(String.valueOf(node.getPackageName()))) {
                    String text = String.valueOf(node.getText());
                    String description = String.valueOf(node.getContentDescription());
                    if (!"null".equals(text) && !text.isBlank()) labels.add(text);
                    if (!"null".equals(description) && !description.isBlank()) {
                        labels.add(description);
                    }
                }
                for (int index = 0; index < node.getChildCount(); index++) {
                    AccessibilityNodeInfo child = node.getChild(index);
                    if (child != null) pending.addLast(child);
                }
            }
        }
        return labels;
    }

    private AccessibilitySummary waitForInputMethodSummary(
            UiAutomation automation,
            String expectedPackage,
            Pattern requiredLabelPattern) {
        long deadline = SystemClock.uptimeMillis() + 5_000L;
        AccessibilitySummary summary;
        do {
            summary = summarizeInputMethodWindow(
                    automation, expectedPackage, requiredLabelPattern);
            if (summary.windowFound && summary.nodes > 0) return summary;
            SystemClock.sleep(100L);
        } while (SystemClock.uptimeMillis() < deadline);
        return summary;
    }

    private AccessibilitySummary summarizeInputMethodWindow(
            UiAutomation automation,
            String expectedPackage,
            Pattern requiredLabelPattern) {
        int nodes = 0;
        int visible = 0;
        int labeledVisible = 0;
        int actionable = 0;
        int labeledActionable = 0;
        int describedActionable = 0;
        int screenReaderFocusableActionable = 0;
        int describedScreenReaderFocusableActionable = 0;
        int requiredLabelMatches = 0;
        int actionableLeaf = 0;
        int labeledActionableLeaf = 0;
        boolean windowFound = false;
        boolean expectedPackageFound = false;
        Set<String> packages = new TreeSet<>();
        for (AccessibilityWindowInfo window : automation.getWindows()) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
            windowFound = true;
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) continue;
            Deque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
            pending.add(root);
            while (!pending.isEmpty()) {
                AccessibilityNodeInfo node = pending.removeFirst();
                nodes++;
                CharSequence packageName = node.getPackageName();
                if (packageName != null && !packageName.toString().isBlank()) {
                    String value = packageName.toString();
                    packages.add(value);
                    if (expectedPackage.contentEquals(value)) expectedPackageFound = true;
                }
                boolean isVisible = node.isVisibleToUser();
                boolean isLabeled = hasLabel(node);
                if (requiredLabelPattern != null
                        && expectedPackage.equals(String.valueOf(packageName))
                        && labelMatches(node, requiredLabelPattern)) {
                    requiredLabelMatches++;
                }
                if (isVisible) {
                    visible++;
                    if (isLabeled) labeledVisible++;
                }
                boolean isActionable = isVisible
                        && (node.isClickable()
                        || (node.getActions() & AccessibilityNodeInfo.ACTION_CLICK) != 0);
                if (isActionable) {
                    actionable++;
                    if (isLabeled) labeledActionable++;
                    boolean isDescribed = hasLabelInSubtree(node);
                    if (isDescribed) describedActionable++;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                            && node.isScreenReaderFocusable()) {
                        screenReaderFocusableActionable++;
                        if (isDescribed) describedScreenReaderFocusableActionable++;
                    }
                    if (node.getChildCount() == 0) {
                        actionableLeaf++;
                        if (isLabeled) labeledActionableLeaf++;
                    }
                }
                for (int index = 0; index < node.getChildCount(); index++) {
                    AccessibilityNodeInfo child = node.getChild(index);
                    if (child != null) pending.addLast(child);
                }
            }
        }
        return new AccessibilitySummary(
                windowFound,
                expectedPackageFound,
                nodes,
                visible,
                labeledVisible,
                actionable,
                labeledActionable,
                describedActionable,
                screenReaderFocusableActionable,
                describedScreenReaderFocusableActionable,
                requiredLabelMatches,
                actionableLeaf,
                labeledActionableLeaf,
                packages.toString());
    }

    private boolean hasLabel(AccessibilityNodeInfo node) {
        CharSequence text = node.getText();
        if (text != null && !text.toString().isBlank()) return true;
        CharSequence description = node.getContentDescription();
        return description != null && !description.toString().isBlank();
    }

    private boolean labelMatches(AccessibilityNodeInfo node, Pattern pattern) {
        CharSequence text = node.getText();
        if (text != null && pattern.matcher(text).find()) return true;
        CharSequence description = node.getContentDescription();
        return description != null && pattern.matcher(description).find();
    }

    private boolean hasLabelInSubtree(AccessibilityNodeInfo root) {
        Deque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            AccessibilityNodeInfo node = pending.removeFirst();
            if (hasLabel(node)) return true;
            for (int index = 0; index < node.getChildCount(); index++) {
                AccessibilityNodeInfo child = node.getChild(index);
                if (child != null) pending.addLast(child);
            }
        }
        return false;
    }

    private record AccessibilitySummary(
            boolean windowFound,
            boolean expectedPackageFound,
            int nodes,
            int visibleNodes,
            int labeledVisibleNodes,
            int actionableNodes,
            int labeledActionableNodes,
            int describedActionableNodes,
            int screenReaderFocusableActionableNodes,
            int describedScreenReaderFocusableActionableNodes,
            int requiredLabelMatches,
            int actionableLeafNodes,
            int labeledActionableLeafNodes,
            String packages) {}
}
