package com.opentypeless.android.rime.importer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.opentypeless.android.RimeResourceActivity;
import com.opentypeless.android.keyboard.rime.RimeRuntimeConfig;
import com.opentypeless.ksp004.RimeAdapter;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;

/** Device evidence for the pinned deploy symbol and an explicitly preloaded local test package. */
@RunWith(AndroidJUnit4.class)
public final class RimeSchemaImportInstrumentedTest {
    private static final String PRELOADED_PACKAGE = "rim003-device-import.zip";

    @Test
    public void nativeDryDeployRunsInsideNoBackupStorage() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root = new File(context.getNoBackupFilesDir(), "rim003-native-dry-deploy");
        deleteTree(root);

        RimeAdapter.RuntimeInfo runtime = RimeAdapter.dryDeploy(root);

        assertEquals("1.17.0", runtime.version());
        assertTrue(new File(root, "shared").isDirectory());
        assertTrue(new File(root, "user").isDirectory());
        deleteTree(root);
    }

    @Test
    public void importActivityIsPrivateAndAddsNoPermission() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ActivityInfo info = context.getPackageManager().getActivityInfo(
                new android.content.ComponentName(context, RimeResourceActivity.class),
                0);

        assertFalse(info.exported);
        assertNull(info.permission);
    }

    @Test
    public void explicitlyPreloadedPackageStagesDeploysAndClearsAtomically() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String expectedSha256 = arguments.getString("rimeImportSha256");
        Assume.assumeTrue(expectedSha256 != null && expectedSha256.matches("[0-9a-f]{64}"));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File selected = new File(context.getNoBackupFilesDir(), PRELOADED_PACKAGE);
        Assume.assumeTrue(selected.isFile());
        assertEquals(expectedSha256, sha256(selected));
        RimeResourceStore store = new RimeResourceStore(context);
        store.clear();

        try (FileInputStream input = new FileInputStream(selected);
             RimeResourceStore.StagedImport staged = store.stage(input)) {
            assertEquals("USER_PROVIDED_UNVERIFIED", staged.preview().trustState());
            assertEquals("LOCAL_ONLY", staged.preview().distributionScope());
            RimeResourceStore.Installed installed = store.commit(staged);
            assertEquals("local.synthetic", installed.packageId());
            assertNotNull(store.status());
        } finally {
            store.clear();
            assertNull(store.status());
            assertTrue(selected.delete() || !selected.exists());
        }
    }

    @Test
    public void selectedSchemaAndOptionsSurviveNewPreferencesInstanceAndRepairRemoval() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        RimeRuntimePreferences first = new RimeRuntimePreferences(context);
        first.clear();
        first.save(new RimeRuntimeConfig("second", false, false, true),
                java.util.List.of("first", "second"));

        RimeRuntimeConfig restored = new RimeRuntimePreferences(context)
                .load(java.util.List.of("first", "second"));
        assertEquals("second", restored.schemaId());
        assertFalse(restored.simplifiedOutput());
        assertFalse(restored.asciiPunctuation());
        assertTrue(restored.fullShape());

        RimeRuntimeConfig repaired = new RimeRuntimePreferences(context)
                .load(java.util.List.of("first"));
        assertEquals("first", repaired.schemaId());
        assertEquals(repaired, new RimeRuntimePreferences(context)
                .load(java.util.List.of("first")));
        first.clear();
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[16_384];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) {
            result.append(String.format(java.util.Locale.ROOT, "%02x", value));
        }
        return result.toString();
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteTree(child);
            }
        }
        assertTrue(file.delete());
    }
}
