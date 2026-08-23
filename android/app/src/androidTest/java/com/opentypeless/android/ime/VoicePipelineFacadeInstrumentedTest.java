package com.opentypeless.android.ime;

import static org.junit.Assert.assertEquals;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class VoicePipelineFacadeInstrumentedTest {
    @Test
    public void facadeConstructsIdleRuntimeAndDelegatesAttribution() {
        VoicePipeline pipeline = new VoicePipeline(ApplicationProvider.getApplicationContext());
        try {
            assertEquals(VoicePipeline.State.IDLE, pipeline.state());
            pipeline.setRecordingContext(ApplicationProvider.getApplicationContext());
            assertEquals(VoicePipeline.State.IDLE, pipeline.state());
        } finally {
            pipeline.shutdown();
        }
    }
}
