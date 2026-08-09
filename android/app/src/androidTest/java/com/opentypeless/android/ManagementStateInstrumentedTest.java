package com.opentypeless.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class ManagementStateInstrumentedTest {
    @Test
    public void dictionaryDraftSurvivesActivityRecreation() {
        try (ActivityScenario<DictionaryActivity> scenario =
                     ActivityScenario.launch(DictionaryActivity.class)) {
            scenario.onActivity(activity -> {
                editWithDescription(activity, activity.getString(R.string.term_canonical_label))
                        .setText("邓雪昭");
                editWithDescription(activity, activity.getString(R.string.term_pronunciation_label))
                        .setText("deng xue zhao");
                editWithDescription(activity, activity.getString(R.string.wrong_phrase_label))
                        .setText("等学校");
            });

            scenario.recreate();

            scenario.onActivity(activity -> {
                assertEquals(
                        "邓雪昭",
                        editWithDescription(
                                activity,
                                activity.getString(R.string.term_canonical_label)).getText().toString());
                assertEquals(
                        "deng xue zhao",
                        editWithDescription(
                                activity,
                                activity.getString(R.string.term_pronunciation_label)).getText().toString());
                assertEquals(
                        "等学校",
                        editWithDescription(
                                activity,
                                activity.getString(R.string.wrong_phrase_label)).getText().toString());
            });
        }
    }

    @Test
    public void teachCorrectionDraftSurvivesActivityRecreation() {
        Context context = ApplicationProvider.getApplicationContext();
        Intent intent = new Intent(context, HistoryActivity.class)
                .putExtra("raw_text", "等学校")
                .putExtra("final_text", "邓雪昭")
                .putExtra("app_scope", "com.example.editor");
        try (ActivityScenario<HistoryActivity> scenario = ActivityScenario.launch(intent)) {
            assertTrue(awaitDialogFields(scenario));
            scenario.onActivity(activity -> activity.correctionDialogFieldsForTest()
                    .get(1).setText("邓雪昭教授"));

            scenario.recreate();

            assertTrue(awaitDialogFields(scenario));
            scenario.onActivity(activity -> {
                List<EditText> restored = activity.correctionDialogFieldsForTest();
                assertEquals("等学校", restored.get(0).getText().toString());
                assertEquals("邓雪昭教授", restored.get(1).getText().toString());
                assertEquals("com.example.editor", restored.get(2).getText().toString());
            });
        }
    }

    private static EditText editWithDescription(Activity activity, String description) {
        for (EditText field : editFields(activity.getWindow().getDecorView())) {
            if (description.contentEquals(field.getContentDescription())) return field;
        }
        throw new AssertionError("Missing edit field: " + description);
    }

    private static boolean awaitDialogFields(ActivityScenario<HistoryActivity> scenario) {
        AtomicReference<List<EditText>> result = new AtomicReference<>(List.of());
        for (int attempt = 0; attempt < 40; attempt++) {
            scenario.onActivity(activity -> result.set(activity.correctionDialogFieldsForTest()));
            if (result.get().size() >= 3) return true;
            SystemClock.sleep(50);
        }
        return false;
    }

    private static List<EditText> editFields(View root) {
        List<EditText> result = new ArrayList<>();
        collectEditFields(root, result);
        return result;
    }

    private static void collectEditFields(View view, List<EditText> result) {
        if (view instanceof EditText field) result.add(field);
        if (!(view instanceof ViewGroup group)) return;
        for (int index = 0; index < group.getChildCount(); index++) {
            collectEditFields(group.getChildAt(index), result);
        }
    }
}
