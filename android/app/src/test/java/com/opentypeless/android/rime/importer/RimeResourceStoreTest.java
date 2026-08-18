package com.opentypeless.android.rime.importer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class RimeResourceStoreTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void validPackageDeploysOnceAndClearRemovesIt() throws Exception {
        AtomicInteger deployments = new AtomicInteger();
        RimeResourceStore store = new RimeResourceStore(
                temporary.newFolder("store"),
                root -> {
                    assertTrue(new java.io.File(root, "shared/local.schema.yaml").isFile());
                    assertTrue(new java.io.File(root, "user").isDirectory());
                    deployments.incrementAndGet();
                });

        try (RimeResourceStore.StagedImport staged = store.stage(
                new ByteArrayInputStream(RimeImportTestPackages.validArchive("1")))) {
            assertEquals("Synthetic local package", staged.preview().displayName());
            assertEquals("1", staged.preview().packageVersion());
            RimeResourceStore.Installed installed = store.commit(staged);
            assertEquals("1", installed.packageVersion());
        }

        assertEquals(1, deployments.get());
        assertEquals("1", store.status().packageVersion());
        RimeResourceStore.RuntimePackage runtime = store.runtimePackage();
        assertEquals(java.util.List.of("local"), runtime.selectedSchemas());
        assertTrue(runtime.deploymentId().matches("[0-9a-f]{64}"));
        assertTrue(new java.io.File(runtime.root(), "shared/local.schema.yaml").isFile());
        assertFalse(runtime.toString().contains(runtime.root().getPath()));
        store.clear();
        assertNull(store.status());
        assertNull(store.runtimePackage());
    }

    @Test
    public void stagingASecondLocalPackageDoesNotReplaceTheActivePackage() throws Exception {
        AtomicInteger deployments = new AtomicInteger();
        RimeResourceStore store = new RimeResourceStore(
                temporary.newFolder("explicit-local"),
                root -> deployments.incrementAndGet());
        try (RimeResourceStore.StagedImport first = store.stage(
                new ByteArrayInputStream(RimeImportTestPackages.validArchive("1")))) {
            store.commit(first);
        }
        try (RimeResourceStore.StagedImport ignored = store.stage(
                new ByteArrayInputStream(RimeImportTestPackages.validArchive("2")))) {
            assertEquals("1", store.status().packageVersion());
        }

        assertEquals(1, deployments.get());
        assertEquals("1", store.status().packageVersion());
    }

    @Test
    public void failedDryDeployKeepsPreviouslyInstalledPackage() throws Exception {
        AtomicBoolean fail = new AtomicBoolean();
        RimeResourceStore store = new RimeResourceStore(
                temporary.newFolder("rollback"),
                root -> {
                    if (fail.get()) throw new IllegalStateException("content must not escape");
                });
        RimeResourceStore.StagedImport first = store.stage(
                new ByteArrayInputStream(RimeImportTestPackages.validArchive("1")));
        store.commit(first);
        fail.set(true);
        RimeResourceStore.StagedImport second = store.stage(
                new ByteArrayInputStream(RimeImportTestPackages.validArchive("2")));

        RimeImportException error = assertThrows(
                RimeImportException.class,
                () -> store.commit(second));
        assertEquals(RimeImportException.Code.DEPLOY_FAILED, error.code());
        assertEquals("1", store.status().packageVersion());
    }

    @Test
    public void extraMissingAndTamperedMembersFailBeforeDeploy() throws Exception {
        AtomicInteger deployments = new AtomicInteger();
        RimeResourceStore store = new RimeResourceStore(
                temporary.newFolder("closed-set"),
                root -> deployments.incrementAndGet());
        LinkedHashMap<String, byte[]> files =
                RimeImportTestPackages.defaultFiles(RimeImportTestPackages.SAFE_SCHEMA);
        byte[] manifest = RimeImportTestPackages.manifest("1", files);

        LinkedHashMap<String, byte[]> extra = new LinkedHashMap<>(files);
        extra.put("extra.txt", "extra".getBytes(StandardCharsets.UTF_8));
        assertStageCode(
                store,
                RimeImportException.Code.FILE_SET_MISMATCH,
                RimeImportTestPackages.archive(manifest, extra));

        LinkedHashMap<String, byte[]> missing = new LinkedHashMap<>(files);
        missing.remove("NOTICE.txt");
        assertStageCode(
                store,
                RimeImportException.Code.FILE_SET_MISMATCH,
                RimeImportTestPackages.archive(manifest, missing));

        LinkedHashMap<String, byte[]> tampered = new LinkedHashMap<>(files);
        tampered.put("NOTICE.txt", "changed".getBytes(StandardCharsets.UTF_8));
        assertStageCode(
                store,
                RimeImportException.Code.FILE_SET_MISMATCH,
                RimeImportTestPackages.archive(manifest, tampered));
        assertEquals(0, deployments.get());
    }

    @Test
    public void networkAliasAndSymlinkPayloadsFailClosed() throws Exception {
        RimeResourceStore store = new RimeResourceStore(
                temporary.newFolder("unsafe"),
                root -> {});
        assertUnsafeSchema(store, "schema:\n  schema_id: local\n  source: https://invalid.local\n");
        assertUnsafeSchema(store, "schema: &shared\n  schema_id: local\ncopy: *shared\n");

        byte[] archive = RimeImportTestPackages.validArchive("1");
        byte[] symlink = RimeImportTestPackages.withSymlinkCentralEntry(
                archive,
                "local.schema.yaml");
        assertStageCode(store, RimeImportException.Code.RESOURCE_UNSAFE, symlink);
        byte[] executable = RimeImportTestPackages.withExecutableCentralEntry(
                archive,
                "local.schema.yaml");
        assertStageCode(store, RimeImportException.Code.RESOURCE_UNSAFE, executable);
    }

    @Test
    public void highCompressionRatioFailsBeforeExtraction() throws Exception {
        RimeResourceStore store = new RimeResourceStore(
                temporary.newFolder("bomb"),
                root -> {});
        String repetitive = "a".repeat(300_000);
        LinkedHashMap<String, byte[]> files = RimeImportTestPackages.defaultFiles(repetitive);
        byte[] archive = RimeImportTestPackages.archive(
                RimeImportTestPackages.manifest("1", files),
                files);
        assertStageCode(store, RimeImportException.Code.ARCHIVE_LIMIT, archive);
    }

    @Test
    public void boundedRimeDictionaryRowsAllowUnicodeAndRejectMalformedTables() throws Exception {
        AtomicInteger deployments = new AtomicInteger();
        RimeResourceStore store = new RimeResourceStore(
                temporary.newFolder("dictionary"), root -> deployments.incrementAndGet());
        LinkedHashMap<String, byte[]> valid =
                RimeImportTestPackages.defaultFiles(RimeImportTestPackages.SAFE_SCHEMA);
        valid.put("local.dict.yaml", ("---\nname: local\nversion: \"1\"\n...\n\n"
                + "甲\tni\t1000\n乙\tni\t999\n").getBytes(StandardCharsets.UTF_8));
        try (RimeResourceStore.StagedImport staged = store.stage(
                new ByteArrayInputStream(RimeImportTestPackages.archive(
                        RimeImportTestPackages.manifest("1", valid), valid)))) {
            store.commit(staged);
        }
        assertEquals(1, deployments.get());

        store.clear();
        LinkedHashMap<String, byte[]> malformed = new LinkedHashMap<>(valid);
        malformed.put("local.dict.yaml", ("---\nname: local\n...\n"
                + "甲\tni\tnot-a-weight\n").getBytes(StandardCharsets.UTF_8));
        assertStageCode(store, RimeImportException.Code.RESOURCE_UNSAFE,
                RimeImportTestPackages.archive(
                        RimeImportTestPackages.manifest("2", malformed), malformed));
    }

    @Test
    public void abandonedPreviewDeletesPrivateStaging() throws Exception {
        java.io.File root = temporary.newFolder("cancel");
        RimeResourceStore store = new RimeResourceStore(root, ignored -> {});
        RimeResourceStore.StagedImport staged = store.stage(
                new ByteArrayInputStream(RimeImportTestPackages.validArchive("1")));
        assertTrue(hasStaging(root));
        staged.close();
        assertFalse(hasStaging(root));
        assertNull(store.status());
    }

    private static void assertUnsafeSchema(RimeResourceStore store, String schema) throws Exception {
        LinkedHashMap<String, byte[]> files = RimeImportTestPackages.defaultFiles(schema);
        assertStageCode(
                store,
                RimeImportException.Code.RESOURCE_UNSAFE,
                RimeImportTestPackages.archive(
                        RimeImportTestPackages.manifest("1", files),
                        files));
    }

    private static void assertStageCode(
            RimeResourceStore store,
            RimeImportException.Code code,
            byte[] archive) {
        RimeImportException error = assertThrows(
                RimeImportException.class,
                () -> store.stage(new ByteArrayInputStream(archive)));
        assertEquals(code, error.code());
    }

    private static boolean hasStaging(java.io.File root) {
        java.io.File[] files = root.listFiles(file -> file.getName().startsWith(".staging-"));
        return files != null && files.length > 0;
    }
}
