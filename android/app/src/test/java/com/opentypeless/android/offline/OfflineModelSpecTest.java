package com.opentypeless.android.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.net.URI;

import org.junit.Test;

public final class OfflineModelSpecTest {
    @Test
    public void qualityModelIsRevisionAndHashPinned() {
        OfflineModelSpec spec = OfflineModelSpec.QUALITY;
        assertEquals(239_549_735L, spec.downloadBytes());
        assertEquals(OfflineModelSpec.SENSEVOICE_REVISION, spec.revision());
        assertEquals("https", spec.model().uri().getScheme());
        assertEquals(64, spec.model().sha256().length());
    }

    @Test
    public void artifactRejectsMutableOrUnsafeInputs() {
        assertThrows(IllegalArgumentException.class, () -> new OfflineModelSpec.Artifact(
                "../model", URI.create("https://example.com/model"), 1, "a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new OfflineModelSpec.Artifact(
                "model", URI.create("http://example.com/model"), 1, "a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new OfflineModelSpec.Artifact(
                "model", URI.create("https://example.com/model"), 0, "a".repeat(64)));
    }
}
