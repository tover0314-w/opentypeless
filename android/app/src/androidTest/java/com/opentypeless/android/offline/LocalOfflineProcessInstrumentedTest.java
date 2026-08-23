package com.opentypeless.android.offline;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Process;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class LocalOfflineProcessInstrumentedTest {
    @Test
    public void privateRecognitionServiceRunsOutsideTheAppProcess() {
        Context context = ApplicationProvider.getApplicationContext();
        try (LocalOfflineRecognitionClient client = new LocalOfflineRecognitionClient(context)) {
            int workerPid = client.servicePidForDiagnostics();
            assertTrue(workerPid > 0);
            assertNotEquals(Process.myPid(), workerPid);
        }
    }
}
