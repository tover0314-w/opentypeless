package com.opentypeless.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;

import androidx.annotation.RequiresApi;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.opentypeless.android.ime.OpenTypelessImeService;
import com.opentypeless.android.recognition.OpenTypelessRecognitionService;
import com.opentypeless.android.recognition.OpenTypelessRecognizerActivity;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class ManifestContractTest {
    @Test
    public void publishesThreeAndroidVoiceEntryPointsWithBindingPermissions() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        PackageManager manager = context.getPackageManager();

        ServiceInfo ime = getServiceInfo(
                manager,
                new ComponentName(context, OpenTypelessImeService.class));
        assertEquals("android.permission.BIND_INPUT_METHOD", ime.permission);

        ServiceInfo speech = getServiceInfo(
                manager,
                new ComponentName(context, OpenTypelessRecognitionService.class));
        assertTrue(speech.exported);

        ActivityInfo recognizer = getActivityInfo(
                manager,
                new ComponentName(context, OpenTypelessRecognizerActivity.class));
        assertNotNull(recognizer);
    }

    @SuppressWarnings("deprecation")
    private static ServiceInfo getServiceInfo(PackageManager manager, ComponentName component)
            throws PackageManager.NameNotFoundException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return Api33.getServiceInfo(manager, component);
        }
        return manager.getServiceInfo(component, 0);
    }

    @SuppressWarnings("deprecation")
    private static ActivityInfo getActivityInfo(PackageManager manager, ComponentName component)
            throws PackageManager.NameNotFoundException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return Api33.getActivityInfo(manager, component);
        }
        return manager.getActivityInfo(component, 0);
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private static final class Api33 {
        private Api33() {}

        static ServiceInfo getServiceInfo(PackageManager manager, ComponentName component)
                throws PackageManager.NameNotFoundException {
            return manager.getServiceInfo(component, PackageManager.ComponentInfoFlags.of(0));
        }

        static ActivityInfo getActivityInfo(PackageManager manager, ComponentName component)
                throws PackageManager.NameNotFoundException {
            return manager.getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0));
        }
    }
}
