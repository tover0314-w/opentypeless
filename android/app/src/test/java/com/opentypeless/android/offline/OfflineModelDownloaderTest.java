package com.opentypeless.android.offline;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
}
