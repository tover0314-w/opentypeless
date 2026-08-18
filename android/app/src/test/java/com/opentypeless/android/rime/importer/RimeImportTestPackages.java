package com.opentypeless.android.rime.importer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class RimeImportTestPackages {
    static final String SAFE_SCHEMA = """
            # SYNTHETIC_TEST_ONLY
            schema:
              schema_id: local
              name: Local test
              version: "1"
            engine:
              translators:
                - table_translator
            translator:
              dictionary: local
            """;
    static final byte[] LICENSE = "Synthetic local test license\n".getBytes(StandardCharsets.UTF_8);
    static final byte[] NOTICE = "Synthetic local test notice\n".getBytes(StandardCharsets.UTF_8);

    static byte[] validArchive(String packageVersion) throws Exception {
        LinkedHashMap<String, byte[]> files = defaultFiles(SAFE_SCHEMA);
        return archive(manifest(packageVersion, files), files);
    }

    static LinkedHashMap<String, byte[]> defaultFiles(String schema) {
        LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
        files.put("local.schema.yaml", schema.getBytes(StandardCharsets.UTF_8));
        files.put("LICENSE.txt", LICENSE);
        files.put("NOTICE.txt", NOTICE);
        return files;
    }

    static byte[] manifest(String packageVersion, LinkedHashMap<String, byte[]> files)
            throws Exception {
        StringBuilder fileEntries = new StringBuilder();
        for (Map.Entry<String, byte[]> item : files.entrySet()) {
            if (!fileEntries.isEmpty()) fileEntries.append(',');
            String role = item.getKey().endsWith(".schema.yaml")
                    ? "SCHEMA_YAML"
                    : item.getKey().endsWith(".dict.yaml")
                    ? "DICTIONARY_YAML"
                    : item.getKey().endsWith(".yaml")
                    ? "CONFIG_YAML"
                    : item.getKey().equals("LICENSE.txt")
                    ? "LICENSE_TEXT"
                    : "NOTICE_TEXT";
            fileEntries.append("{\"path\":\"")
                    .append(item.getKey())
                    .append("\",\"size\":")
                    .append(item.getValue().length)
                    .append(",\"sha256\":\"")
                    .append(sha256(item.getValue()))
                    .append("\",\"role\":\"")
                    .append(role)
                    .append("\"}");
        }
        String json = """
                {
                  "format":"opentypeless.rime-resource-manifest",
                  "version":1,
                  "entrypoint":"ANDROID_SAF_OPEN_DOCUMENT",
                  "networkAccess":false,
                  "autoUpdate":false,
                  "fileSetPolicy":"EXACT_MANIFEST_ONLY",
                  "packageId":"local.synthetic",
                  "packageVersion":"%s",
                  "displayName":"Synthetic local package",
                  "sourceUrl":null,
                  "sourceRevision":"local-revision",
                  "author":"Local user",
                  "rightsholder":"Local user",
                  "licenseExpression":"LicenseRef-User-Provided",
                  "licenseTextPath":"LICENSE.txt",
                  "noticePaths":["NOTICE.txt"],
                  "usageBasis":"USER_PROVIDED_UNVERIFIED",
                  "trustState":"USER_PROVIDED_UNVERIFIED",
                  "distributionScope":"LOCAL_ONLY",
                  "compatibleLibrime":{"minimumVersion":"1.17.0","maximumVersionExclusive":"1.18.0"},
                  "selectedSchemas":["local"],
                  "files":[%s],
                  "dependencies":[]
                }
                """.formatted(packageVersion, fileEntries);
        return json.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] archive(byte[] manifest, LinkedHashMap<String, byte[]> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            write(zip, RimeResourceManifest.ARCHIVE_MANIFEST, manifest);
            for (Map.Entry<String, byte[]> item : files.entrySet()) {
                write(zip, item.getKey(), item.getValue());
            }
        }
        return bytes.toByteArray();
    }

    static byte[] withSymlinkCentralEntry(byte[] archive, String entryName) {
        return withUnixMode(archive, entryName, 0xa1ff);
    }

    static byte[] withExecutableCentralEntry(byte[] archive, String entryName) {
        return withUnixMode(archive, entryName, 0x81ed);
    }

    private static byte[] withUnixMode(byte[] archive, String entryName, int unixMode) {
        byte[] result = archive.clone();
        int offset = 0;
        while (offset + 46 <= result.length) {
            if (u32(result, offset) != 0x02014b50L) {
                offset++;
                continue;
            }
            int nameLength = u16(result, offset + 28);
            int extraLength = u16(result, offset + 30);
            int commentLength = u16(result, offset + 32);
            String name = new String(result, offset + 46, nameLength, StandardCharsets.UTF_8);
            if (name.equals(entryName)) {
                put16(result, offset + 4, (3 << 8) | 20);
                put32(result, offset + 38, Integer.toUnsignedLong(unixMode << 16));
                return result;
            }
            offset += 46 + nameLength + extraLength + commentLength;
        }
        throw new IllegalArgumentException("entry missing");
    }

    private static void write(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest) result.append(String.format(Locale.ROOT, "%02x", value));
        return result.toString();
    }

    private static int u16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private static long u32(byte[] bytes, int offset) {
        return Integer.toUnsignedLong((bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16)
                | ((bytes[offset + 3] & 0xff) << 24));
    }

    private static void put16(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
    }

    private static void put32(byte[] bytes, int offset, long value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
        bytes[offset + 3] = (byte) (value >>> 24);
    }

    private RimeImportTestPackages() {}
}
