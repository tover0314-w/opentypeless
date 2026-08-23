package com.opentypeless.android.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class OfflinePunctuationModelSpecTest {
    @Test
    public void punctuationArtifactIsRevisionAndDigestPinned() {
        OfflinePunctuationModelSpec spec = OfflinePunctuationModelSpec.ZH_EN;

        assertEquals(40, spec.revision().length());
        assertEquals(spec.revision(), OfflinePunctuationModelSpec.MIRROR_REVISION);
        assertTrue(spec.model().uri().toString().contains("/resolve/" + spec.revision() + "/"));
        assertEquals(75_519_198L, spec.model().bytes());
        assertEquals(
                "65a3fb9f5ad7bfb96bf69e0dc4481df97f6ee60513c1d94ce981ba6effd524b1",
                spec.model().sha256());
        assertTrue(OfflineModelDownloader.trustedDownloadUri(spec.model().uri()));
    }
}
