package com.opentypeless.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;

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

        ServiceInfo ime = manager.getServiceInfo(
                new ComponentName(context, OpenTypelessImeService.class),
                PackageManager.ComponentInfoFlags.of(0));
        assertEquals("android.permission.BIND_INPUT_METHOD", ime.permission);

        ServiceInfo speech = manager.getServiceInfo(
                new ComponentName(context, OpenTypelessRecognitionService.class),
                PackageManager.ComponentInfoFlags.of(0));
        assertTrue(speech.exported);

        ActivityInfo recognizer = manager.getActivityInfo(
                new ComponentName(context, OpenTypelessRecognizerActivity.class),
                PackageManager.ComponentInfoFlags.of(0));
        assertNotNull(recognizer);
    }
}
