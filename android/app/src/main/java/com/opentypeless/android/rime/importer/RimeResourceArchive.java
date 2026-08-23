package com.opentypeless.android.rime.importer;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Validates and extracts a bounded ZIP resource package into private staging. */
final class RimeResourceArchive {
    static final long MAXIMUM_ARCHIVE_BYTES = 67_108_864L;
    private static final int MAXIMUM_MEMBERS = RimeResourceManifest.MAXIMUM_FILES + 1;
    private static final long MAXIMUM_COMPRESSION_RATIO = 200L;
    private static final int MAXIMUM_MANIFEST_BYTES = 1_048_576;
    private static final Pattern YAML_REFERENCE = Pattern.compile(
            "(^|\\s)[&*][A-Za-z0-9_-]+(?:\\s|$)");
    record Extracted(RimeResourceManifest manifest, File stagingRoot) {}

    private record CentralEntry(
            String name,
            long compressedSize,
            long expandedSize,
            long crc) {}

    static Extracted extract(File archive, File stagingRoot) throws RimeImportException {
        if (!archive.isFile() || archive.length() <= 0 || archive.length() > MAXIMUM_ARCHIVE_BYTES) {
            throw new RimeImportException(RimeImportException.Code.ARCHIVE_LIMIT);
        }
        Map<String, CentralEntry> central = readCentralDirectory(archive);
        CentralEntry manifestEntry = central.get(RimeResourceManifest.ARCHIVE_MANIFEST);
        if (manifestEntry == null || manifestEntry.expandedSize() <= 0
                || manifestEntry.expandedSize() > MAXIMUM_MANIFEST_BYTES) {
            throw new RimeImportException(RimeImportException.Code.MANIFEST_INVALID);
        }

        try (ZipFile zip = new ZipFile(archive)) {
            requireZipMatchesCentral(zip, central);
            byte[] manifestBytes = readBounded(
                    zip.getInputStream(zip.getEntry(RimeResourceManifest.ARCHIVE_MANIFEST)),
                    manifestEntry.expandedSize(),
                    MAXIMUM_MANIFEST_BYTES);
            RimeResourceManifest manifest = RimeResourceManifest.parse(manifestBytes);
            Map<String, RimeResourceManifest.FileEntry> expected = manifest.allFilesByPath();
            Set<String> actual = new HashSet<>(central.keySet());
            actual.remove(RimeResourceManifest.ARCHIVE_MANIFEST);
            if (!actual.equals(expected.keySet())) {
                throw new RimeImportException(RimeImportException.Code.FILE_SET_MISMATCH);
            }
            if (stagingRoot.getUsableSpace() < manifest.totalBytes() + 16_777_216L) {
                throw new RimeImportException(RimeImportException.Code.STORAGE_FAILED);
            }
            File shared = child(stagingRoot, "shared");
            File user = child(stagingRoot, "user");
            requireDirectory(shared);
            requireDirectory(user);
            for (Map.Entry<String, RimeResourceManifest.FileEntry> item : expected.entrySet()) {
                CentralEntry compressed = central.get(item.getKey());
                RimeResourceManifest.FileEntry declared = item.getValue();
                if (compressed == null || compressed.expandedSize() != declared.size()) {
                    throw new RimeImportException(RimeImportException.Code.FILE_SET_MISMATCH);
                }
                File output = child(shared, declared.path());
                File parent = output.getParentFile();
                if (parent == null) throw new RimeImportException(RimeImportException.Code.PATH_INVALID);
                requireDirectory(parent);
                ZipEntry zipEntry = zip.getEntry(declared.path());
                if (zipEntry == null) {
                    throw new RimeImportException(RimeImportException.Code.FILE_SET_MISMATCH);
                }
                copyAndVerify(zip.getInputStream(zipEntry), output, declared);
                validateResource(output, declared.role());
            }
            File internalManifest = child(stagingRoot, "import-manifest.json");
            writeSynced(internalManifest, manifestBytes);
            return new Extracted(manifest, stagingRoot);
        } catch (RimeImportException error) {
            throw error;
        } catch (IOException | SecurityException error) {
            throw new RimeImportException(RimeImportException.Code.ARCHIVE_INVALID, error);
        }
    }

    private static Map<String, CentralEntry> readCentralDirectory(File archive)
            throws RimeImportException {
        try (RandomAccessFile input = new RandomAccessFile(archive, "r")) {
            long length = input.length();
            int tailLength = (int) Math.min(length, 65_557L);
            byte[] tail = new byte[tailLength];
            input.seek(length - tailLength);
            input.readFully(tail);
            int eocd = -1;
            for (int offset = tail.length - 22; offset >= 0; offset--) {
                if (u32(tail, offset) == 0x06054b50L) {
                    int commentLength = u16(tail, offset + 20);
                    if (offset + 22 + commentLength == tail.length) {
                        eocd = offset;
                        break;
                    }
                }
            }
            if (eocd < 0 || u16(tail, eocd + 4) != 0 || u16(tail, eocd + 6) != 0
                    || u16(tail, eocd + 20) != 0) {
                throw invalidArchive();
            }
            int diskEntries = u16(tail, eocd + 8);
            int totalEntries = u16(tail, eocd + 10);
            long centralSize = u32(tail, eocd + 12);
            long centralOffset = u32(tail, eocd + 16);
            if (diskEntries != totalEntries || totalEntries < 2 || totalEntries > MAXIMUM_MEMBERS
                    || diskEntries == 0xffff || centralSize == 0xffffffffL
                    || centralOffset == 0xffffffffL || centralOffset + centralSize > length - 22) {
                throw invalidArchive();
            }

            input.seek(centralOffset);
            LinkedHashMap<String, CentralEntry> result = new LinkedHashMap<>();
            Set<String> normalized = new HashSet<>();
            long expandedTotal = 0;
            long centralConsumed = 0;
            for (int index = 0; index < totalEntries; index++) {
                byte[] header = new byte[46];
                input.readFully(header);
                centralConsumed += header.length;
                if (u32(header, 0) != 0x02014b50L) throw invalidArchive();
                int madeBy = u16(header, 4);
                int flags = u16(header, 8);
                int method = u16(header, 10);
                long crc = u32(header, 16);
                long compressed = u32(header, 20);
                long expanded = u32(header, 24);
                int nameLength = u16(header, 28);
                int extraLength = u16(header, 30);
                int commentLength = u16(header, 32);
                int disk = u16(header, 34);
                long externalAttributes = u32(header, 38);
                long localOffset = u32(header, 42);
                if ((flags & 0x0001) != 0 || (flags & ~(0x0800 | 0x0008)) != 0
                        || (method != ZipEntry.STORED && method != ZipEntry.DEFLATED)
                        || compressed == 0xffffffffL || expanded == 0xffffffffL
                        || localOffset == 0xffffffffL || localOffset >= centralOffset
                        || nameLength < 1 || nameLength > 4096 || commentLength != 0 || disk != 0) {
                    throw invalidArchive();
                }
                byte[] nameBytes = new byte[nameLength];
                input.readFully(nameBytes);
                byte[] extra = new byte[extraLength];
                input.readFully(extra);
                centralConsumed += nameLength + extraLength;
                rejectZip64Extra(extra);
                String name = decodeName(nameBytes, flags);
                RimeResourceManifest.safePath(name);
                String identity = name.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
                if (!normalized.add(identity) || result.containsKey(name)) throw invalidArchive();
                int system = (madeBy >>> 8) & 0xff;
                int unixMode = (int) ((externalAttributes >>> 16) & 0xffff);
                int unixType = (int) ((externalAttributes >>> 16) & 0xf000);
                boolean directory = name.endsWith("/") || (externalAttributes & 0x10) != 0;
                if (directory || (system == 3 && (unixType != 0 && unixType != 0x8000
                        || (unixMode & 0111) != 0))) {
                    throw new RimeImportException(RimeImportException.Code.RESOURCE_UNSAFE);
                }
                if (expanded < 1 || expanded > RimeResourceManifest.MAXIMUM_FILE_BYTES
                        || compressed < 1
                        || expanded > compressed * MAXIMUM_COMPRESSION_RATIO) {
                    throw new RimeImportException(RimeImportException.Code.ARCHIVE_LIMIT);
                }
                if (expanded > RimeResourceManifest.MAXIMUM_TOTAL_BYTES - expandedTotal) {
                    throw new RimeImportException(RimeImportException.Code.ARCHIVE_LIMIT);
                }
                expandedTotal += expanded;
                result.put(name, new CentralEntry(name, compressed, expanded, crc));
            }
            if (centralConsumed != centralSize) throw invalidArchive();
            return result;
        } catch (RimeImportException error) {
            throw error;
        } catch (IOException error) {
            throw new RimeImportException(RimeImportException.Code.ARCHIVE_INVALID, error);
        }
    }

    private static void requireZipMatchesCentral(ZipFile zip, Map<String, CentralEntry> central)
            throws RimeImportException {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        Set<String> seen = new HashSet<>();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            CentralEntry expected = central.get(entry.getName());
            if (expected == null || !seen.add(entry.getName()) || entry.isDirectory()
                    || entry.getSize() != expected.expandedSize()
                    || entry.getCompressedSize() != expected.compressedSize()
                    || entry.getCrc() != expected.crc()) {
                throw invalidArchive();
            }
        }
        if (!seen.equals(central.keySet())) throw invalidArchive();
    }

    private static void copyAndVerify(
            InputStream source,
            File output,
            RimeResourceManifest.FileEntry expected) throws RimeImportException {
        try (InputStream input = new BufferedInputStream(source);
             FileOutputStream target = new FileOutputStream(output)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[16_384];
            long count = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read == 0) continue;
                if (count > expected.size() - read) {
                    throw new RimeImportException(RimeImportException.Code.FILE_SET_MISMATCH);
                }
                count += read;
                digest.update(buffer, 0, read);
                target.write(buffer, 0, read);
            }
            target.getFD().sync();
            if (count != expected.size() || !hex(digest.digest()).equals(expected.sha256())) {
                throw new RimeImportException(RimeImportException.Code.HASH_MISMATCH);
            }
            if (!output.setReadable(true, true) || !output.setWritable(true, true)
                    || !output.setExecutable(false, false)) {
                throw new RimeImportException(RimeImportException.Code.STORAGE_FAILED);
            }
        } catch (RimeImportException error) {
            throw error;
        } catch (IOException | NoSuchAlgorithmException error) {
            throw new RimeImportException(RimeImportException.Code.STORAGE_FAILED, error);
        }
    }

    private static void validateResource(File file, RimeResourceManifest.Role role)
            throws RimeImportException {
        byte[] prefix = new byte[16];
        int prefixLength;
        try (InputStream input = new FileInputStream(file)) {
            prefixLength = input.read(prefix);
        } catch (IOException error) {
            throw new RimeImportException(RimeImportException.Code.STORAGE_FAILED, error);
        }
        byte[] actualPrefix = prefixLength < 0 ? new byte[0] : Arrays.copyOf(prefix, prefixLength);
        if (hasForbiddenMagic(actualPrefix)) {
            throw new RimeImportException(RimeImportException.Code.RESOURCE_UNSAFE);
        }

        boolean executableData = switch (role) {
            case SCHEMA_YAML, DICTIONARY_YAML, CONFIG_YAML, TEXT_TABLE,
                    OPENCC_CONFIG, OPENCC_DATA -> true;
            default -> false;
        };
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file),
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)))) {
            int lineCount = 0;
            ArrayList<Integer> indentationStack = new ArrayList<>();
            boolean dictionaryBody = false;
            String line;
            while ((line = reader.readLine()) != null) {
                if (++lineCount > 1_000_000 || line.codePointCount(0, line.length()) > 8_192
                        || containsUnsafeControl(line)) {
                    throw new RimeImportException(RimeImportException.Code.RESOURCE_UNSAFE);
                }
                String lower = line.toLowerCase(Locale.ROOT);
                if (executableData && containsForbiddenResourceToken(lower)) {
                    throw new RimeImportException(RimeImportException.Code.RESOURCE_UNSAFE);
                }
                if (role == RimeResourceManifest.Role.SCHEMA_YAML
                        || role == RimeResourceManifest.Role.DICTIONARY_YAML
                        || role == RimeResourceManifest.Role.CONFIG_YAML) {
                    int indentation = leadingSpaces(line);
                    String syntax = stripYamlComment(line);
                    String meaningful = syntax.strip();
                    if (role == RimeResourceManifest.Role.DICTIONARY_YAML
                            && dictionaryBody && !meaningful.isEmpty()) {
                        requireDictionaryRow(line);
                        continue;
                    }
                    if (!meaningful.isEmpty()) {
                        while (!indentationStack.isEmpty()
                                && indentation <= indentationStack.get(indentationStack.size() - 1)) {
                            indentationStack.remove(indentationStack.size() - 1);
                        }
                        indentationStack.add(indentation);
                    }
                    if (line.indexOf('\t') >= 0 || indentation > 64
                            || indentationStack.size() > 32
                            || YAML_REFERENCE.matcher(syntax).find()
                            || syntax.stripLeading().startsWith("<<:")
                            || syntax.contains("!!") || syntax.contains("!<")) {
                        throw new RimeImportException(RimeImportException.Code.RESOURCE_UNSAFE);
                    }
                    if (role == RimeResourceManifest.Role.DICTIONARY_YAML
                            && meaningful.equals("...")) {
                        dictionaryBody = true;
                        indentationStack.clear();
                    }
                }
            }
        } catch (CharacterCodingException error) {
            throw new RimeImportException(RimeImportException.Code.RESOURCE_UNSAFE, error);
        } catch (IOException error) {
            throw new RimeImportException(RimeImportException.Code.STORAGE_FAILED, error);
        }
        if (role == RimeResourceManifest.Role.OPENCC_CONFIG) {
            if (file.length() > 1_048_576L) {
                throw new RimeImportException(RimeImportException.Code.RESOURCE_UNSAFE);
            }
            try {
                StrictBoundedJson.parseObject(readFile(file, 1_048_576));
            } catch (RimeImportException error) {
                throw new RimeImportException(RimeImportException.Code.RESOURCE_UNSAFE, error);
            }
        }
    }

    private static boolean containsForbiddenResourceToken(String lower) {
        return lower.contains("http://") || lower.contains("https://")
                || lower.contains("ftp://") || lower.contains("file://")
                || lower.contains("content://") || lower.contains("ws://")
                || lower.contains("wss://") || lower.contains(".lua")
                || lower.contains("lua_translator") || lower.contains("lua_filter")
                || lower.contains("lua_processor") || lower.contains("rime_lua")
                || lower.contains("octagram") || lower.contains("dexclassloader")
                || lower.contains("inmemorydexclassloader") || lower.contains("loadlibrary")
                || lower.contains("system.load") || lower.contains("javascript:");
    }

    private static void requireDictionaryRow(String line) throws RimeImportException {
        String[] columns = line.split("\\t", -1);
        if (columns.length < 2 || columns.length > 3
                || columns[0].isBlank() || columns[1].isBlank()
                || columns[0].codePointCount(0, columns[0].length()) > 256
                || columns[1].length() > 256
                || (columns.length == 3 && !columns[2].matches("[0-9]{1,12}"))) {
            throw new RimeImportException(RimeImportException.Code.RESOURCE_UNSAFE);
        }
        for (int offset = 0; offset < columns[0].length(); ) {
            int codePoint = columns[0].codePointAt(offset);
            if (Character.isISOControl(codePoint) || codePoint == 0x061c
                    || (codePoint >= 0x200e && codePoint <= 0x200f)
                    || (codePoint >= 0x2028 && codePoint <= 0x202e)
                    || (codePoint >= 0x2066 && codePoint <= 0x2069)) {
                throw new RimeImportException(RimeImportException.Code.RESOURCE_UNSAFE);
            }
            offset += Character.charCount(codePoint);
        }
        if (!columns[1].matches("[A-Za-z0-9_' -]{1,256}")) {
            throw new RimeImportException(RimeImportException.Code.RESOURCE_UNSAFE);
        }
    }

    private static boolean containsUnsafeControl(String line) {
        for (int offset = 0; offset < line.length(); ) {
            int codePoint = line.codePointAt(offset);
            if ((Character.isISOControl(codePoint) && codePoint != '\t')
                    || codePoint == 0x061c || codePoint == 0x200e || codePoint == 0x200f
                    || (codePoint >= 0x2028 && codePoint <= 0x202e)
                    || (codePoint >= 0x2066 && codePoint <= 0x2069)) {
                return true;
            }
            offset += Character.charCount(codePoint);
        }
        return false;
    }

    private static int leadingSpaces(String line) {
        int result = 0;
        while (result < line.length() && line.charAt(result) == ' ') result++;
        return result;
    }

    private static String stripYamlComment(String line) {
        boolean single = false;
        boolean doubled = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '\'' && !doubled) single = !single;
            else if (character == '"' && !single) doubled = !doubled;
            else if (character == '#' && !single && !doubled
                    && (index == 0 || Character.isWhitespace(line.charAt(index - 1)))) {
                return line.substring(0, index);
            }
        }
        return line;
    }

    private static void writeSynced(File output, byte[] bytes) throws RimeImportException {
        try (FileOutputStream stream = new FileOutputStream(output)) {
            stream.write(bytes);
            stream.getFD().sync();
        } catch (IOException error) {
            throw new RimeImportException(RimeImportException.Code.STORAGE_FAILED, error);
        }
    }

    private static byte[] readFile(File file, int maximum) throws RimeImportException {
        try (InputStream input = new FileInputStream(file)) {
            long declaredLength = file.length();
            if (declaredLength < 0 || declaredLength > maximum) {
                throw new RimeImportException(RimeImportException.Code.RESOURCE_UNSAFE);
            }
            byte[] result = new byte[(int) declaredLength];
            int offset = 0;
            while (offset < result.length) {
                int read = input.read(result, offset, result.length - offset);
                if (read < 0) break;
                if (read > 0) offset += read;
            }
            if (offset != result.length || input.read() != -1) {
                throw new RimeImportException(RimeImportException.Code.RESOURCE_UNSAFE);
            }
            return result;
        } catch (IOException error) {
            throw new RimeImportException(RimeImportException.Code.STORAGE_FAILED, error);
        }
    }

    private static byte[] readBounded(InputStream source, long expected, int maximum)
            throws IOException, RimeImportException {
        try (InputStream input = source; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8_192];
            long count = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read == 0) continue;
                count += read;
                if (count > expected || count > maximum) {
                    throw new RimeImportException(RimeImportException.Code.ARCHIVE_LIMIT);
                }
                output.write(buffer, 0, read);
            }
            if (count != expected) throw invalidArchive();
            return output.toByteArray();
        }
    }

    private static File child(File root, String relative) throws RimeImportException {
        try {
            File canonicalRoot = root.getCanonicalFile();
            File result = new File(canonicalRoot, relative).getCanonicalFile();
            String prefix = canonicalRoot.getPath() + File.separator;
            if (!result.getPath().startsWith(prefix)) {
                throw new RimeImportException(RimeImportException.Code.PATH_INVALID);
            }
            return result;
        } catch (IOException error) {
            throw new RimeImportException(RimeImportException.Code.STORAGE_FAILED, error);
        }
    }

    private static void requireDirectory(File directory) throws RimeImportException {
        if ((!directory.isDirectory() && !directory.mkdirs()) || !directory.isDirectory()
                || directory.isFile()) {
            throw new RimeImportException(RimeImportException.Code.STORAGE_FAILED);
        }
    }

    private static String decodeName(byte[] bytes, int flags) throws RimeImportException {
        if ((flags & 0x0800) == 0) {
            for (byte value : bytes) {
                if ((value & 0x80) != 0) throw invalidArchive();
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException error) {
            throw new RimeImportException(RimeImportException.Code.PATH_INVALID, error);
        }
    }

    private static void rejectZip64Extra(byte[] extra) throws RimeImportException {
        int offset = 0;
        while (offset < extra.length) {
            if (offset + 4 > extra.length) throw invalidArchive();
            int id = u16(extra, offset);
            int length = u16(extra, offset + 2);
            offset += 4;
            if (offset + length > extra.length || id == 0x0001) throw invalidArchive();
            offset += length;
        }
    }

    private static boolean hasForbiddenMagic(byte[] value) {
        if (value.length >= 4) {
            int first = value[0] & 0xff;
            int second = value[1] & 0xff;
            int third = value[2] & 0xff;
            int fourth = value[3] & 0xff;
            if ((first == 0x50 && second == 0x4b && third == 0x03 && fourth == 0x04)
                    || (first == 0x7f && second == 0x45 && third == 0x4c && fourth == 0x46)
                    || (first == 0x64 && second == 0x65 && third == 0x78 && fourth == 0x0a)
                    || (first == 0x28 && second == 0xb5 && third == 0x2f && fourth == 0xfd)) {
                return true;
            }
        }
        if (value.length >= 2 && (value[0] & 0xff) == 0x1f && (value[1] & 0xff) == 0x8b) {
            return true;
        }
        if (value.length >= 6) {
            int first = value[0] & 0xff;
            if ((first == 0xfd && (value[1] & 0xff) == 0x37 && (value[2] & 0xff) == 0x7a
                    && (value[3] & 0xff) == 0x58 && (value[4] & 0xff) == 0x5a
                    && value[5] == 0)
                    || (first == 0x37 && (value[1] & 0xff) == 0x7a
                    && (value[2] & 0xff) == 0xbc && (value[3] & 0xff) == 0xaf
                    && (value[4] & 0xff) == 0x27 && (value[5] & 0xff) == 0x1c)) {
                return true;
            }
        }
        String sqlite = "SQLite format 3";
        if (value.length >= sqlite.length() + 1) {
            for (int index = 0; index < sqlite.length(); index++) {
                if ((value[index] & 0xff) != sqlite.charAt(index)) return false;
            }
            return value[sqlite.length()] == 0;
        }
        return false;
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

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value));
        return result.toString();
    }

    private static RimeImportException invalidArchive() {
        return new RimeImportException(RimeImportException.Code.ARCHIVE_INVALID);
    }

    private RimeResourceArchive() {}
}
