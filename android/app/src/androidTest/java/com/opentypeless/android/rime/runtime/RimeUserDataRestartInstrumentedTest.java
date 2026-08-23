package com.opentypeless.android.rime.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.opentypeless.android.keyboard.candidate.CandidatePage;
import com.opentypeless.android.keyboard.rime.NativeRimeInputEngine;
import com.opentypeless.android.keyboard.rime.RimeRuntimeConfig;
import com.opentypeless.android.rime.importer.RimeResourceStore;
import com.opentypeless.android.rime.importer.RimeRuntimePreferences;
import com.opentypeless.android.rime.userdata.RimeUserDataStore;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Fresh-process half of the RIM-007 persistent UserDB matrix. */
@RunWith(AndroidJUnit4.class)
public final class RimeUserDataRestartInstrumentedTest {
    @Test
    public void learnedRankingSurvivesRestartAndClearReturnsToStaticOrder() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        RimeResourceStore resources = new RimeResourceStore(context);
        RimeRuntimePreferences preferences = new RimeRuntimePreferences(context);
        RimeUserDataStore userData = new RimeUserDataStore(context);
        RimeResourceStore.RuntimePackage runtime = resources.runtimePackage();
        RimeRuntimeConfig configuration = preferences.load(runtime.selectedSchemas());

        assertEquals("乙", firstCandidate(runtime, configuration, userData, 81L, 91L));
        userData.restoreLatestCheckpoint();
        assertEquals("乙", firstCandidate(runtime, configuration, userData, 82L, 92L));

        userData.clear();
        assertFalse(userData.status().hasUserData());
        assertEquals("甲", firstCandidate(runtime, configuration, userData, 83L, 93L));

        userData.clear();
        resources.clear();
        preferences.clear();
    }

    private static String firstCandidate(
            RimeResourceStore.RuntimePackage runtime,
            RimeRuntimeConfig configuration,
            RimeUserDataStore userData,
            long editor,
            long coordination) {
        NativeRimeInputEngine engine = new NativeRimeInputEngine(
                runtime.root(), configuration, userData);
        try {
            CandidatePage page = RimeUserDataSeedInstrumentedTest.processNi(
                    engine, editor, coordination);
            return page.items().get(0).text();
        } finally {
            engine.close();
        }
    }
}
