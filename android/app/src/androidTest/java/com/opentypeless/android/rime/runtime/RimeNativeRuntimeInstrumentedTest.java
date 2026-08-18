package com.opentypeless.android.rime.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.opentypeless.ksp004.RimeAdapter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class RimeNativeRuntimeInstrumentedTest {
    @Test
    public void pinnedRuntimeLoadsInitializesReportsVersionAndFinalizes() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        File root = new File(context.getNoBackupFilesDir(), "rim002-runtime-probe");
        deleteTree(root);

        RimeAdapter.RuntimeInfo runtime = RimeAdapter.probe(root);

        assertEquals("1.17.0", runtime.version());
        assertTrue(new File(root, "shared").isDirectory());
        assertTrue(new File(root, "user").isDirectory());
        String[] children = root.list();
        assertTrue(children != null);
        Arrays.sort(children);
        assertEquals(Arrays.asList("shared", "user"), Arrays.asList(children));
        assertFalse(new File(root, "shared/default.yaml").exists());
        assertFalse(new File(root, "user/default.userdb").exists());

        deleteTree(root);
        assertFalse(root.exists());
    }

    private static void deleteTree(File root) throws IOException {
        if (!root.exists()) return;
        if (Files.isSymbolicLink(root.toPath())) {
            throw new IOException("Refusing to follow a symbolic link");
        }
        File[] children = root.listFiles();
        if (children != null) {
            if (children.length > 32) {
                throw new IOException("Rime probe tree exceeded the test bound");
            }
            for (File child : children) deleteTree(child);
        }
        if (!root.delete() && root.exists()) {
            throw new IOException("Unable to delete Rime probe tree");
        }
    }
}
