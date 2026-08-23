package com.opentypeless.android.rime.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.opentypeless.android.keyboard.candidate.CandidatePage;
import com.opentypeless.android.keyboard.rime.NativeRimeInputEngine;
import com.opentypeless.android.keyboard.rime.RimeInputEngine;
import com.opentypeless.android.keyboard.rime.RimeRuntimeConfig;
import com.opentypeless.android.rime.importer.RimeResourceStore;
import com.opentypeless.android.rime.importer.RimeRuntimePreferences;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/** RIM-006 actual-librime evidence for persisted Schema and closed option selection. */
@RunWith(AndroidJUnit4.class)
public final class RimeConfigurationInstrumentedTest {
    private static final String PRELOADED_PACKAGE = "rim006-device-import-v1.zip";

    @Test
    public void persistedAlternateSchemaAndOptionsRestoreAcrossSessions() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String expectedSha256 = arguments.getString("rimeConfigImportSha256");
        Assume.assumeTrue(expectedSha256 != null && expectedSha256.matches("[0-9a-f]{64}"));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File selected = new File(context.getNoBackupFilesDir(), PRELOADED_PACKAGE);
        Assume.assumeTrue(selected.isFile());
        assertEquals(expectedSha256, sha256(selected));

        RimeResourceStore store = new RimeResourceStore(context);
        RimeRuntimePreferences preferences = new RimeRuntimePreferences(context);
        store.clear();
        preferences.clear();
        try (FileInputStream input = new FileInputStream(selected);
             RimeResourceStore.StagedImport staged = store.stage(input)) {
            store.commit(staged);
        }
        RimeResourceStore.RuntimePackage runtime = store.runtimePackage();
        assertEquals(java.util.List.of("local", "alternate"), runtime.selectedSchemas());

        RimeRuntimeConfig alternate = new RimeRuntimeConfig(
                "alternate", false, false, true);
        preferences.save(alternate, runtime.selectedSchemas());
        RimeRuntimeConfig restored = new RimeRuntimePreferences(context)
                .load(runtime.selectedSchemas());
        assertEquals(alternate, restored);
        assertFalse(restored.asciiPunctuation());
        assertTrue(restored.fullShape());

        NativeRimeInputEngine alternateEngine = new NativeRimeInputEngine(
                runtime.root(), restored);
        try {
            CandidatePage page = processNi(alternateEngine);
            assertEquals("壹", page.items().get(0).text());
            RimeInputEngine.StateReady reset = state(alternateEngine.process(
                    new RimeInputEngine.ProcessRequest(
                            7L, 11L, RimeInputEngine.Key.escape())));
            assertEquals("", reset.snapshot().preedit());
            assertFalse(reset.snapshot().candidatePage().isPresent());
        } finally {
            alternateEngine.close();
        }

        RimeRuntimeConfig local = RimeRuntimeConfig.defaults("local");
        preferences.save(local, runtime.selectedSchemas());
        NativeRimeInputEngine localEngine = new NativeRimeInputEngine(
                runtime.root(), new RimeRuntimePreferences(context)
                        .load(runtime.selectedSchemas()));
        try {
            assertEquals("甲", processNi(localEngine).items().get(0).text());
        } finally {
            localEngine.close();
            store.clear();
            preferences.clear();
            assertTrue(selected.delete() || !selected.exists());
        }
    }

    private static CandidatePage processNi(NativeRimeInputEngine engine) {
        assertTrue(engine.activate(new RimeInputEngine.Activation(
                7L, 11L, RimeInputEngine.LearningMode.ENABLED))
                instanceof RimeInputEngine.LifecycleApplied);
        state(engine.process(new RimeInputEngine.ProcessRequest(
                7L, 11L, RimeInputEngine.Key.printable('n'))));
        return state(engine.process(new RimeInputEngine.ProcessRequest(
                7L, 11L, RimeInputEngine.Key.printable('i'))))
                .snapshot().candidatePage().orElseThrow();
    }

    private static RimeInputEngine.StateReady state(RimeInputEngine.ProcessResult result) {
        assertTrue(result instanceof RimeInputEngine.StateReady);
        return (RimeInputEngine.StateReady) result;
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
}
