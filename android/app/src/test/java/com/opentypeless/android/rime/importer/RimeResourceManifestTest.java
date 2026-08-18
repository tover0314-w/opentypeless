package com.opentypeless.android.rime.importer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

public final class RimeResourceManifestTest {
    @Test
    public void acceptedManifestStaysUnverifiedAndLocalOnly() throws Exception {
        LinkedHashMap<String, byte[]> files =
                RimeImportTestPackages.defaultFiles(RimeImportTestPackages.SAFE_SCHEMA);
        RimeResourceManifest manifest = RimeResourceManifest.parse(
                RimeImportTestPackages.manifest("1", files));

        assertEquals("local.synthetic", manifest.packageId());
        assertEquals("USER_PROVIDED_UNVERIFIED", manifest.preview().trustState());
        assertEquals("LOCAL_ONLY", manifest.preview().distributionScope());
        assertEquals(3, manifest.preview().fileCount());
        assertEquals("local", manifest.selectedSchemas().get(0));
    }

    @Test
    public void duplicateAndUnknownRootKeysFailClosed() throws Exception {
        String valid = new String(
                RimeImportTestPackages.manifest(
                        "1",
                        RimeImportTestPackages.defaultFiles(RimeImportTestPackages.SAFE_SCHEMA)),
                StandardCharsets.UTF_8);
        String duplicate = valid.replace("\"version\":1,", "\"version\":1,\"version\":1,");
        String unknown = valid.replace("\"dependencies\":[]", "\"dependencies\":[],\"extra\":0");

        assertCode(RimeImportException.Code.MANIFEST_INVALID, duplicate);
        assertCode(RimeImportException.Code.MANIFEST_INVALID, unknown);
    }

    @Test
    public void trustAndNetworkConstantsCannotBeElevated() throws Exception {
        String valid = new String(
                RimeImportTestPackages.manifest(
                        "1",
                        RimeImportTestPackages.defaultFiles(RimeImportTestPackages.SAFE_SCHEMA)),
                StandardCharsets.UTF_8);
        assertCode(
                RimeImportException.Code.MANIFEST_INVALID,
                valid.replace("USER_PROVIDED_UNVERIFIED", "TRUSTED"));
        assertCode(
                RimeImportException.Code.MANIFEST_INVALID,
                valid.replace("\"networkAccess\":false", "\"networkAccess\":true"));
    }

    @Test
    public void incompatibleRuntimeAndUnsafePathFailClosed() throws Exception {
        String valid = new String(
                RimeImportTestPackages.manifest(
                        "1",
                        RimeImportTestPackages.defaultFiles(RimeImportTestPackages.SAFE_SCHEMA)),
                StandardCharsets.UTF_8);
        assertCode(
                RimeImportException.Code.RUNTIME_INCOMPATIBLE,
                valid.replace("\"minimumVersion\":\"1.17.0\"", "\"minimumVersion\":\"1.18.0\""));
        assertCode(
                RimeImportException.Code.PATH_INVALID,
                valid.replace("LICENSE.txt", "../LICENSE.txt"));
    }

    @Test
    public void selectedSchemaMustReferenceExactRootSchemaRole() throws Exception {
        String valid = new String(
                RimeImportTestPackages.manifest(
                        "1",
                        RimeImportTestPackages.defaultFiles(RimeImportTestPackages.SAFE_SCHEMA)),
                StandardCharsets.UTF_8);
        assertCode(
                RimeImportException.Code.MANIFEST_INVALID,
                valid.replace("\"selectedSchemas\":[\"local\"]", "\"selectedSchemas\":[\"missing\"]"));
    }

    private static void assertCode(RimeImportException.Code code, String json) {
        RimeImportException error = assertThrows(
                RimeImportException.class,
                () -> RimeResourceManifest.parse(json.getBytes(StandardCharsets.UTF_8)));
        assertEquals(code, error.code());
    }
}
