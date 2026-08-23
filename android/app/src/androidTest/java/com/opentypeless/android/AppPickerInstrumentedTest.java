package com.opentypeless.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.view.View;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.opentypeless.android.config.AppPickerModel;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
public final class AppPickerInstrumentedTest {
    @Test
    public void catalogListsTheCurrentAppWithAnIconWithoutBroadVisibilityPermission()
            throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        InstalledAppCatalog.Snapshot snapshot = InstalledAppCatalog.load(context);
        AppPickerModel.Entry own = snapshot.model().entries().stream()
                .filter(entry -> entry.packageName().equals(context.getPackageName()))
                .findFirst()
                .orElseThrow();

        assertNotNull(snapshot.iconFor(context, own));
        PackageInfo packageInfo = context.getPackageManager().getPackageInfo(
                context.getPackageName(), PackageManager.GET_PERMISSIONS);
        assertFalse(packageInfo.requestedPermissions != null
                && Arrays.asList(packageInfo.requestedPermissions)
                .contains(Manifest.permission.QUERY_ALL_PACKAGES));
        assertFalse(snapshot.toString().contains(context.getPackageName()));
    }

    @Test
    public void pickerSearchSelectsAnInstalledAppAndAdvancedEntrySurvivesRotation() {
        Context context = ApplicationProvider.getApplicationContext();
        Intent intent = new Intent(context, AppProfileActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try (ActivityScenario<AppProfileActivity> scenario =
                     ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> {
                EditText packageName = field(activity, "packageName", EditText.class);
                assertEquals(View.GONE, packageName.getVisibility());

                field(activity, "chooseInstalledApp", Button.class).performClick();
                AlertDialog dialog = field(activity, "appPickerDialog", AlertDialog.class);
                assertTrue(dialog.isShowing());
                EditText search = dialog.findViewById(R.id.app_picker_search);
                search.setText(context.getPackageName());
                ListView list = dialog.findViewById(R.id.app_picker_list);
                BaseAdapter adapter = (BaseAdapter) list.getAdapter();
                assertEquals(1, adapter.getCount());
                View row = adapter.getView(0, null, list);
                list.performItemClick(row, 0, adapter.getItemId(0));

                assertEquals(context.getPackageName(), packageName.getText().toString());
                assertEquals(View.GONE, packageName.getVisibility());
                assertTrue(field(activity, "selectedApp", TextView.class)
                        .getText().toString().contains(context.getPackageName()));

                field(activity, "advancedPackageEntry", Button.class).performClick();
                assertEquals(View.VISIBLE, packageName.getVisibility());
                packageName.setText("com.example.manual");
            });

            scenario.recreate();

            scenario.onActivity(activity -> {
                EditText packageName = field(activity, "packageName", EditText.class);
                assertEquals(View.VISIBLE, packageName.getVisibility());
                assertEquals("com.example.manual", packageName.getText().toString());
            });
        }
    }

    private static <T> T field(Object owner, String name, Class<T> type) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(owner));
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }
}
