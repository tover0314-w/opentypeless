package com.opentypeless.android.offline;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

/** Explicit device-only bridge that commits an externally preloaded, production-pinned model. */
@RunWith(AndroidJUnit4.class)
public final class OfflineModelPinnedImportInstrumentedTest {
    static final String STAGING_NAME = ".staging-pinned-quality-import";

    @Test
    public void productionVerifierAtomicallyCommitsPreloadedPinnedQualityModel() {
        Bundle arguments = InstrumentationRegistry.getArguments();
        Assume.assumeTrue("true".equals(arguments.getString("pinnedQualityImport")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File staging = new File(
                new File(context.getNoBackupFilesDir(), "offline_models"),
                STAGING_NAME);
        Assume.assumeTrue(staging.isDirectory());

        OfflineModelStore.commitVerifiedStaging(context, staging);

        assertEquals(OfflineModelStore.Status.INSTALLED, OfflineModelStore.status(context));
        OfflineModelStore.InstalledModel installed = OfflineModelStore.requireVerified(context);
        assertEquals(OfflineModelSpec.QUALITY.model().bytes(), installed.model().length());
        assertEquals(OfflineModelSpec.QUALITY.tokens().bytes(), installed.tokens().length());
    }
}
