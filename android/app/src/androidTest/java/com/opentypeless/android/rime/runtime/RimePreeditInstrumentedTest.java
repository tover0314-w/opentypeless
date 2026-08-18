package com.opentypeless.android.rime.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.opentypeless.android.keyboard.rime.NativeRimeInputEngine;
import com.opentypeless.android.keyboard.rime.RimeInputEngine;
import com.opentypeless.android.keyboard.rime.RimeRuntimeConfig;
import com.opentypeless.android.keyboard.candidate.CandidatePage;
import com.opentypeless.android.rime.importer.RimeResourceStore;
import com.opentypeless.android.rime.userdata.RimeUserDataStore;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** RIM-004/005 actual-librime evidence using an explicitly preloaded synthetic package. */
@RunWith(AndroidJUnit4.class)
public final class RimePreeditInstrumentedTest {
    private static final String PRELOADED_PACKAGE = "rim005-device-import-v1.zip";

    @Test
    public void importedSchemaProcessesAsciiUnicodeCandidatesBackspaceAndClose() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String expectedSha256 = arguments.getString("rimeImportSha256");
        boolean retainForSystemIme = Boolean.parseBoolean(
                arguments.getString("retainRimePackage", "false"));
        Assume.assumeTrue(expectedSha256 != null && expectedSha256.matches("[0-9a-f]{64}"));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File selected = new File(context.getNoBackupFilesDir(), PRELOADED_PACKAGE);
        Assume.assumeTrue(selected.isFile());
        assertEquals(expectedSha256, sha256(selected));

        RimeResourceStore store = new RimeResourceStore(context);
        store.clear();
        try (FileInputStream input = new FileInputStream(selected);
             RimeResourceStore.StagedImport staged = store.stage(input)) {
            store.commit(staged);
        }
        RimeResourceStore.RuntimePackage runtime = store.runtimePackage();
        assertEquals(java.util.List.of("local"), runtime.selectedSchemas());

        NativeRimeInputEngine engine = new NativeRimeInputEngine(runtime.root(), "local");
        try {
            assertTrue(engine.activate(new RimeInputEngine.Activation(
                    7L, 11L, RimeInputEngine.LearningMode.ENABLED))
                    instanceof RimeInputEngine.LifecycleApplied);
            RimeInputEngine.StateReady n = state(engine.process(new RimeInputEngine.ProcessRequest(
                    7L, 11L, RimeInputEngine.Key.printable('n'))));
            assertEquals("n", n.snapshot().preedit());
            RimeInputEngine.StateReady ni = state(engine.process(new RimeInputEngine.ProcessRequest(
                    7L, 11L, RimeInputEngine.Key.printable('i'))));
            assertEquals("ni", ni.snapshot().preedit());
            CandidatePage first = ni.snapshot().candidatePage().orElseThrow();
            assertEquals(0, first.pageIndex());
            assertEquals(3, first.pageCount());
            assertEquals(5, first.items().size());
            assertEquals("甲", first.items().get(0).text());
            RimeInputEngine.StateReady second = state(engine.requestCandidatePage(
                    new RimeInputEngine.CandidatePageRequest(
                            7L, first.pageRequest(CandidatePage.Direction.NEXT))));
            CandidatePage secondPage = second.snapshot().candidatePage().orElseThrow();
            assertEquals("ni", second.snapshot().preedit());
            assertEquals(1, secondPage.pageIndex());
            assertEquals("己", secondPage.items().get(0).text());
            RimeInputEngine.StateReady againFirst = state(engine.requestCandidatePage(
                    new RimeInputEngine.CandidatePageRequest(
                            7L, secondPage.pageRequest(CandidatePage.Direction.PREVIOUS))));
            assertEquals(0, againFirst.snapshot().candidatePage().orElseThrow().pageIndex());
            RimeInputEngine.StateReady back = state(engine.process(
                    new RimeInputEngine.ProcessRequest(
                            7L, 11L, RimeInputEngine.Key.backspace())));
            assertEquals("n", back.snapshot().preedit());
            assertFalse(back.snapshot().candidatePage().isPresent());
            if (!retainForSystemIme) {
                RimeInputEngine.StateReady readyToSelect = state(engine.process(
                        new RimeInputEngine.ProcessRequest(
                                7L, 11L, RimeInputEngine.Key.printable('i'))));
                CandidatePage selectionFirst = readyToSelect.snapshot()
                        .candidatePage().orElseThrow();
                CandidatePage selectionSecond = state(engine.requestCandidatePage(
                        new RimeInputEngine.CandidatePageRequest(
                                7L, selectionFirst.pageRequest(CandidatePage.Direction.NEXT))))
                        .snapshot().candidatePage().orElseThrow();
                CandidatePage.Selection candidateSelection = selectionSecond.selection(1);
                RimeInputEngine.ProcessResult committed = engine.selectCandidate(
                        new RimeInputEngine.CandidateSelectionRequest(7L, candidateSelection));
                assertTrue(committed instanceof RimeInputEngine.CommitReady);
                assertEquals("庚", ((RimeInputEngine.CommitReady) committed).commit().text());
                RimeInputEngine.ProcessResult replayed = engine.selectCandidate(
                        new RimeInputEngine.CandidateSelectionRequest(7L, candidateSelection));
                assertTrue(replayed instanceof RimeInputEngine.Rejected);
                assertEquals(RimeInputEngine.FailureKind.STALE_COORDINATION_GENERATION,
                        ((RimeInputEngine.Rejected) replayed).failure());
            }
        } finally {
            engine.close();
            if (!retainForSystemIme) {
                store.clear();
                assertTrue(selected.delete() || !selected.exists());
            }
        }
    }

    /**
     * Local-only RIM-008 proof over the user's active package. Codes and expected text remain
     * instrumentation arguments so the licensed personal dictionary never enters source or test
     * fixtures.
     */
    @Test
    public void activePersonalPackageAutoCommitsAndExposesSpaceSelection() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String schema = requiredCode(arguments, "rimePersonalSchema", 128);
        String autoCode = requiredCode(arguments, "rimePersonalAutoCode", 8);
        String spaceCode = requiredCode(arguments, "rimePersonalSpaceCode", 8);
        String expectedAutoSha256 = requiredSha256(arguments, "rimePersonalAutoCommitSha256");
        String expectedSpaceSha256 = requiredSha256(
                arguments, "rimePersonalSpaceCommitSha256");
        String expectedDeploymentId = requiredSha256(
                arguments, "rimePersonalDeploymentId");

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        RimeResourceStore.RuntimePackage runtime = new RimeResourceStore(context).runtimePackage();
        Assume.assumeTrue(runtime != null);
        assertEquals(expectedDeploymentId, runtime.deploymentId());
        assertTrue(runtime.selectedSchemas().contains(schema));

        RimeUserDataStore userDataStore = new RimeUserDataStore(context);
        NativeRimeInputEngine autoEngine = new NativeRimeInputEngine(
                runtime.root(), RimeRuntimeConfig.defaults(schema), userDataStore,
                runtime.deploymentId());
        try {
            activate(autoEngine, 31L, 41L);
            RimeInputEngine.ProcessResult autoResult = processCode(
                    autoEngine, 31L, 41L, autoCode);
            assertTrue(autoResult instanceof RimeInputEngine.CommitReady);
            assertEquals(expectedAutoSha256, sha256Text(
                    ((RimeInputEngine.CommitReady) autoResult).commit().text()));
        } finally {
            autoEngine.close();
        }

        NativeRimeInputEngine spaceEngine = new NativeRimeInputEngine(
                runtime.root(), RimeRuntimeConfig.defaults(schema), userDataStore,
                runtime.deploymentId());
        try {
            activate(spaceEngine, 32L, 42L);
            RimeInputEngine.StateReady ready = state(processCode(
                    spaceEngine, 32L, 42L, spaceCode));
            CandidatePage page = ready.snapshot().candidatePage().orElseThrow();
            CandidatePage.Selection first = page.selection(0);
            assertEquals(expectedSpaceSha256, sha256Text(first.expectedText()));
            RimeInputEngine.ProcessResult selected = spaceEngine.selectCandidate(
                    new RimeInputEngine.CandidateSelectionRequest(32L, first));
            assertTrue(selected instanceof RimeInputEngine.CommitReady);
            assertEquals(expectedSpaceSha256, sha256Text(
                    ((RimeInputEngine.CommitReady) selected).commit().text()));
        } finally {
            spaceEngine.close();
        }
    }

    private static void activate(NativeRimeInputEngine engine, long editor, long coordination) {
        assertTrue(engine.activate(new RimeInputEngine.Activation(
                editor, coordination, RimeInputEngine.LearningMode.ENABLED))
                instanceof RimeInputEngine.LifecycleApplied);
    }

    private static RimeInputEngine.ProcessResult processCode(
            NativeRimeInputEngine engine, long editor, long coordination, String code) {
        RimeInputEngine.ProcessResult result = null;
        for (int index = 0; index < code.length(); index++) {
            result = engine.process(new RimeInputEngine.ProcessRequest(
                    editor, coordination, RimeInputEngine.Key.printable(code.charAt(index))));
        }
        return result;
    }

    private static String requiredCode(Bundle arguments, String name, int maximumLength) {
        String value = arguments.getString(name);
        Assume.assumeTrue(value != null
                && value.length() > 0
                && value.length() <= maximumLength
                && value.matches("[a-z]+"));
        return value;
    }

    private static String requiredSha256(Bundle arguments, String name) {
        String value = arguments.getString(name);
        Assume.assumeTrue(value != null && value.matches("[0-9a-f]{64}"));
        return value;
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

    private static String sha256Text(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest) {
            result.append(String.format(java.util.Locale.ROOT, "%02x", value));
        }
        return result.toString();
    }
}
