package com.opentypeless.android.rime.runtime;

import static org.junit.Assert.assertEquals;
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
import com.opentypeless.android.rime.userdata.RimeUserDataStore;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/** First-process half of the RIM-007 persistent UserDB matrix. */
@RunWith(AndroidJUnit4.class)
public final class RimeUserDataSeedInstrumentedTest {
    private static final String PRELOADED_PACKAGE = "rim007-device-import-v1.zip";

    @Test
    public void selectingSecondCandidateCreatesBoundedRecoveryPoint() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String expectedSha256 = arguments.getString("rimeUserDataImportSha256");
        Assume.assumeTrue(expectedSha256 != null && expectedSha256.matches("[0-9a-f]{64}"));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File selected = new File(context.getNoBackupFilesDir(), PRELOADED_PACKAGE);
        Assume.assumeTrue(selected.isFile());
        assertEquals(expectedSha256, sha256(selected));

        RimeResourceStore resources = new RimeResourceStore(context);
        RimeRuntimePreferences preferences = new RimeRuntimePreferences(context);
        RimeUserDataStore userData = new RimeUserDataStore(context);
        resources.clear();
        preferences.clear();
        userData.clear();
        try (FileInputStream input = new FileInputStream(selected);
             RimeResourceStore.StagedImport staged = resources.stage(input)) {
            resources.commit(staged);
        }
        RimeResourceStore.RuntimePackage runtime = resources.runtimePackage();
        RimeRuntimeConfig configuration = RimeRuntimeConfig.defaults("local");
        preferences.save(configuration, runtime.selectedSchemas());

        for (int round = 0; round < 3; round++) {
            NativeRimeInputEngine engine = new NativeRimeInputEngine(
                    runtime.root(), configuration, userData);
            try {
                CandidatePage page = processNi(engine, 40L + round, 70L + round);
                int index = candidateIndex(page, "乙");
                RimeInputEngine.ProcessResult selectedResult = engine.selectCandidate(
                        new RimeInputEngine.CandidateSelectionRequest(
                                40L + round, page.selection(index)));
                assertTrue("actual=" + selectedResult,
                        selectedResult instanceof RimeInputEngine.CommitReady);
                assertEquals("乙", ((RimeInputEngine.CommitReady) selectedResult).commit().text());
            } finally {
                engine.close();
            }
        }

        RimeUserDataStore.Status status = userData.status();
        assertTrue(status.hasUserData());
        assertTrue(status.hasCheckpoint());
        assertTrue(status.fileCount() > 0);
        assertTrue(status.totalBytes() > 0L);
        assertTrue(selected.delete() || !selected.exists());
    }

    static CandidatePage processNi(NativeRimeInputEngine engine, long editor, long coordination) {
        assertTrue(engine.activate(new RimeInputEngine.Activation(
                editor, coordination, RimeInputEngine.LearningMode.ENABLED))
                instanceof RimeInputEngine.LifecycleApplied);
        state(engine.process(new RimeInputEngine.ProcessRequest(
                editor, coordination, RimeInputEngine.Key.printable('n'))));
        return state(engine.process(new RimeInputEngine.ProcessRequest(
                editor, coordination, RimeInputEngine.Key.printable('i'))))
                .snapshot().candidatePage().orElseThrow();
    }

    static int candidateIndex(CandidatePage page, String expected) {
        for (int index = 0; index < page.items().size(); index++) {
            if (expected.equals(page.items().get(index).text())) return index;
        }
        throw new AssertionError("expected synthetic candidate was not present");
    }

    static RimeInputEngine.StateReady state(RimeInputEngine.ProcessResult result) {
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
