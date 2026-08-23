package com.opentypeless.android.offline;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class OfflineStreamingModelSpecTest {
    @Test
    public void realtimeModelIsRevisionSizeAndHashPinned() {
        OfflineStreamingModelSpec spec = OfflineStreamingModelSpec.REALTIME;
        assertEquals(237_202_501L, spec.downloadBytes());
        assertEquals(OfflineStreamingModelSpec.PARAFORMER_REVISION, spec.revision());
        assertEquals("https", spec.encoder().uri().getScheme());
        assertEquals(64, spec.encoder().sha256().length());
        assertEquals(64, spec.decoder().sha256().length());
        assertEquals(64, spec.tokens().sha256().length());
    }
}
