package com.opentypeless.android.offline;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;

import org.junit.Test;

public final class OfflineModelDownloaderTest {
    @Test
    public void acceptsOnlyPinnedProviderHttpsHosts() {
        assertTrue(OfflineModelDownloader.trustedDownloadUri(
                URI.create("https://huggingface.co/org/repo/file")));
        assertTrue(OfflineModelDownloader.trustedDownloadUri(
                URI.create("https://us.aws.cdn.hf.co/xet/object")));
        assertTrue(OfflineModelDownloader.trustedDownloadUri(
                URI.create("https://cas-server.xethub.hf.co/object")));
        assertFalse(OfflineModelDownloader.trustedDownloadUri(
                URI.create("http://huggingface.co/model")));
        assertFalse(OfflineModelDownloader.trustedDownloadUri(
                URI.create("https://huggingface.co.evil.example/model")));
        assertFalse(OfflineModelDownloader.trustedDownloadUri(
                URI.create("https://user@huggingface.co/model")));
        assertFalse(OfflineModelDownloader.trustedDownloadUri(
                URI.create("https://huggingface.co:444/model")));
    }

    @Test
    public void resumesOnlyAnExactPinnedRange() throws Exception {
        OfflineModelDownloader.ResumePlan plan = OfflineModelDownloader.resumePlan(
                400,
                HttpURLConnection.HTTP_PARTIAL,
                600,
                "bytes 400-999/1000",
                1000);
        assertEquals(400, plan.writeOffset());
        assertTrue(plan.append());

        assertThrows(IOException.class, () -> OfflineModelDownloader.resumePlan(
                400,
                HttpURLConnection.HTTP_PARTIAL,
                600,
                "bytes 0-599/1000",
                1000));
        assertThrows(IOException.class, () -> OfflineModelDownloader.resumePlan(
                400,
                HttpURLConnection.HTTP_PARTIAL,
                599,
                "bytes 400-999/1000",
                1000));
    }

    @Test
    public void restartsSafelyWhenServerIgnoresRange() throws Exception {
        OfflineModelDownloader.ResumePlan plan = OfflineModelDownloader.resumePlan(
                400,
                HttpURLConnection.HTTP_OK,
                1000,
                null,
                1000);
        assertEquals(0, plan.writeOffset());
        assertFalse(plan.append());
    }
}
