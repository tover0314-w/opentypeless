package com.opentypeless.android.rime.importer;

import com.opentypeless.ksp004.RimeAdapter;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Closed-world v1 resource manifest accepted only for unverified, local-only user imports. */
public record RimeResourceManifest(
        String packageId,
        String packageVersion,
        String displayName,
        String sourceUrl,
        String sourceRevision,
        String author,
        String rightsholder,
        String licenseExpression,
        String licenseTextPath,
        List<String> noticePaths,
        List<String> selectedSchemas,
        List<FileEntry> files,
        List<Dependency> dependencies,
        long totalBytes) {
    public static final String ARCHIVE_MANIFEST = "opentypeless-rime-manifest.json";
    public static final String TRUST_STATE = "USER_PROVIDED_UNVERIFIED";
    public static final String DISTRIBUTION_SCOPE = "LOCAL_ONLY";
    public static final int MAXIMUM_FILES = 512;
    public static final long MAXIMUM_FILE_BYTES = 67_108_864L;
    public static final long MAXIMUM_TOTAL_BYTES = 268_435_456L;

    private static final Pattern PACKAGE_ID =
            Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");
    private static final Pattern SCHEMA_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> ROOT_KEYS = Set.of(
            "format", "version", "entrypoint", "networkAccess", "autoUpdate",
            "fileSetPolicy", "packageId", "packageVersion", "displayName", "sourceUrl",
            "sourceRevision", "author", "rightsholder", "licenseExpression",
            "licenseTextPath", "noticePaths", "usageBasis", "trustState",
            "distributionScope", "compatibleLibrime", "selectedSchemas", "files",
            "dependencies");
    private static final Set<String> FILE_KEYS = Set.of("path", "size", "sha256", "role");
    private static final Set<String> DEPENDENCY_KEYS = Set.of(
            "packageId", "packageVersion", "sourceRevision", "licenseExpression", "files");
    private static final Set<String> COMPATIBILITY_KEYS =
            Set.of("minimumVersion", "maximumVersionExclusive");

    public enum Role {
        SCHEMA_YAML,
        DICTIONARY_YAML,
        CONFIG_YAML,
        TEXT_TABLE,
        OPENCC_CONFIG,
        OPENCC_DATA,
        LICENSE_TEXT,
        NOTICE_TEXT,
        PROVENANCE_TEXT
    }

    public record FileEntry(String path, long size, String sha256, Role role) {}

    public record Dependency(
            String packageId,
            String packageVersion,
            String sourceRevision,
            String licenseExpression,
            List<FileEntry> files) {}

    public record Preview(
            String displayName,
            String packageId,
            String packageVersion,
            String author,
            String rightsholder,
            String licenseExpression,
            String sourceUrl,
            int fileCount,
            long totalBytes,
            List<String> selectedSchemas,
            String trustState,
            String distributionScope) {}

    public RimeResourceManifest {
        noticePaths = List.copyOf(noticePaths);
        selectedSchemas = List.copyOf(selectedSchemas);
        files = List.copyOf(files);
        dependencies = List.copyOf(dependencies);
    }

    public static RimeResourceManifest parse(byte[] manifestBytes) throws RimeImportException {
        Map<String, Object> root = StrictBoundedJson.parseObject(manifestBytes);
        requireKeys(root, ROOT_KEYS);
        requireConstant(root, "format", "opentypeless.rime-resource-manifest");
        requireConstant(root, "version", 1L);
        requireConstant(root, "entrypoint", "ANDROID_SAF_OPEN_DOCUMENT");
        requireConstant(root, "networkAccess", Boolean.FALSE);
        requireConstant(root, "autoUpdate", Boolean.FALSE);
        requireConstant(root, "fileSetPolicy", "EXACT_MANIFEST_ONLY");
        requireConstant(root, "usageBasis", TRUST_STATE);
        requireConstant(root, "trustState", TRUST_STATE);
        requireConstant(root, "distributionScope", DISTRIBUTION_SCOPE);

        String packageId = boundedText(root.get("packageId"), 128);
        if (!PACKAGE_ID.matcher(packageId).matches()) throw invalid();
        String packageVersion = boundedText(root.get("packageVersion"), 64);
        String displayName = boundedText(root.get("displayName"), 128);
        String sourceUrl = sourceUrl(root.get("sourceUrl"));
        String sourceRevision = boundedText(root.get("sourceRevision"), 128);
        String author = boundedText(root.get("author"), 256);
        String rightsholder = boundedText(root.get("rightsholder"), 256);
        String licenseExpression = boundedText(root.get("licenseExpression"), 256);
        String licenseTextPath = safePath(string(root.get("licenseTextPath")));

        Map<String, Object> compatibility = object(root.get("compatibleLibrime"));
        requireKeys(compatibility, COMPATIBILITY_KEYS);
        String minimum = boundedText(compatibility.get("minimumVersion"), 64);
        String maximumExclusive = boundedText(
                compatibility.get("maximumVersionExclusive"), 64);
        if (compareVersions(minimum, RimeAdapter.EXPECTED_VERSION) > 0
                || compareVersions(RimeAdapter.EXPECTED_VERSION, maximumExclusive) >= 0) {
            throw new RimeImportException(RimeImportException.Code.RUNTIME_INCOMPATIBLE);
        }

        LinkedHashMap<String, String> normalizedPaths = new LinkedHashMap<>();
        List<FileEntry> files = parseFiles(root.get("files"), normalizedPaths);
        ArrayList<Dependency> dependencies = new ArrayList<>();
        Set<String> dependencyIds = new LinkedHashSet<>();
        dependencyIds.add(packageId.toLowerCase(Locale.ROOT));
        List<Object> dependencyValues = array(root.get("dependencies"), 0, 64);
        for (Object value : dependencyValues) {
            Map<String, Object> entry = object(value);
            requireKeys(entry, DEPENDENCY_KEYS);
            String dependencyId = boundedText(entry.get("packageId"), 128);
            String identity = dependencyId.toLowerCase(Locale.ROOT);
            if (!PACKAGE_ID.matcher(dependencyId).matches() || !dependencyIds.add(identity)) {
                throw invalid();
            }
            dependencies.add(new Dependency(
                    dependencyId,
                    boundedText(entry.get("packageVersion"), 64),
                    boundedText(entry.get("sourceRevision"), 128),
                    boundedText(entry.get("licenseExpression"), 256),
                    parseFiles(entry.get("files"), normalizedPaths)));
        }
        if (normalizedPaths.size() > MAXIMUM_FILES) throw invalid();

        Map<String, FileEntry> rootByPath = new LinkedHashMap<>();
        for (FileEntry file : files) rootByPath.put(file.path(), file);
        FileEntry license = rootByPath.get(licenseTextPath);
        if (license == null || license.role() != Role.LICENSE_TEXT) throw invalid();
        List<String> notices = parsePaths(root.get("noticePaths"), 0, 16);
        Set<String> noticeIdentities = new LinkedHashSet<>();
        for (String notice : notices) {
            FileEntry file = rootByPath.get(notice);
            if (file == null || file.role() != Role.NOTICE_TEXT
                    || !noticeIdentities.add(pathIdentity(notice))) {
                throw invalid();
            }
        }

        List<String> selectedSchemas = parseSchemaIds(root.get("selectedSchemas"));
        for (String schema : selectedSchemas) {
            FileEntry entry = rootByPath.get(schema + ".schema.yaml");
            if (entry == null || entry.role() != Role.SCHEMA_YAML) throw invalid();
        }
        long totalBytes = 0;
        for (FileEntry file : files) totalBytes = addBounded(totalBytes, file.size());
        for (Dependency dependency : dependencies) {
            for (FileEntry file : dependency.files()) {
                totalBytes = addBounded(totalBytes, file.size());
            }
        }
        return new RimeResourceManifest(
                packageId,
                packageVersion,
                displayName,
                sourceUrl,
                sourceRevision,
                author,
                rightsholder,
                licenseExpression,
                licenseTextPath,
                notices,
                selectedSchemas,
                files,
                dependencies,
                totalBytes);
    }

    public Map<String, FileEntry> allFilesByPath() {
        LinkedHashMap<String, FileEntry> result = new LinkedHashMap<>();
        for (FileEntry file : files) result.put(file.path(), file);
        for (Dependency dependency : dependencies) {
            for (FileEntry file : dependency.files()) result.put(file.path(), file);
        }
        return Collections.unmodifiableMap(result);
    }

    public Preview preview() {
        return new Preview(
                displayName,
                packageId,
                packageVersion,
                author,
                rightsholder,
                licenseExpression,
                sourceUrl,
                allFilesByPath().size(),
                totalBytes,
                selectedSchemas,
                TRUST_STATE,
                DISTRIBUTION_SCOPE);
    }

    private static List<FileEntry> parseFiles(
            Object value,
            Map<String, String> normalizedPaths) throws RimeImportException {
        List<Object> values = array(value, 1, MAXIMUM_FILES);
        ArrayList<FileEntry> result = new ArrayList<>();
        for (Object raw : values) {
            Map<String, Object> entry = object(raw);
            requireKeys(entry, FILE_KEYS);
            String path = safePath(string(entry.get("path")));
            String identity = pathIdentity(path);
            if (normalizedPaths.putIfAbsent(identity, path) != null) throw invalid();
            long size = integer(entry.get("size"));
            if (size < 1 || size > MAXIMUM_FILE_BYTES) throw invalid();
            String sha256 = string(entry.get("sha256"));
            if (!SHA256.matcher(sha256).matches()) throw invalid();
            Role role;
            try {
                role = Role.valueOf(string(entry.get("role")));
            } catch (IllegalArgumentException error) {
                throw new RimeImportException(RimeImportException.Code.MANIFEST_INVALID, error);
            }
            requireRolePath(path, role);
            result.add(new FileEntry(path, size, sha256, role));
        }
        return List.copyOf(result);
    }

    private static void requireRolePath(String path, Role role) throws RimeImportException {
        String lower = path.toLowerCase(Locale.ROOT);
        boolean valid = switch (role) {
            case SCHEMA_YAML -> lower.endsWith(".schema.yaml");
            case DICTIONARY_YAML -> lower.endsWith(".dict.yaml");
            case CONFIG_YAML -> lower.endsWith(".yaml") || lower.endsWith(".yml");
            case TEXT_TABLE -> lower.endsWith(".txt");
            case OPENCC_CONFIG -> lower.endsWith(".json");
            case OPENCC_DATA -> lower.endsWith(".txt") || lower.endsWith(".json");
            case LICENSE_TEXT, NOTICE_TEXT, PROVENANCE_TEXT ->
                    lower.endsWith(".txt") || lower.endsWith(".md");
        };
        if (!valid || lower.endsWith(".lua") || lower.endsWith(".so")
                || lower.endsWith(".dll") || lower.endsWith(".dylib")
                || lower.endsWith(".exe") || lower.endsWith(".dex")
                || lower.endsWith(".jar") || lower.endsWith(".zip")
                || lower.endsWith(".7z") || lower.endsWith(".gz")
                || lower.endsWith(".xz") || lower.endsWith(".zst")
                || lower.endsWith(".db") || lower.endsWith(".userdb")) {
            throw new RimeImportException(RimeImportException.Code.RESOURCE_UNSAFE);
        }
    }

    private static List<String> parsePaths(Object value, int minimum, int maximum)
            throws RimeImportException {
        ArrayList<String> result = new ArrayList<>();
        for (Object item : array(value, minimum, maximum)) result.add(safePath(string(item)));
        return List.copyOf(result);
    }

    private static List<String> parseSchemaIds(Object value) throws RimeImportException {
        List<Object> values = array(value, 1, 32);
        ArrayList<String> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Object item : values) {
            String schema = boundedText(item, 128);
            if (!SCHEMA_ID.matcher(schema).matches()
                    || !seen.add(schema.toLowerCase(Locale.ROOT))) {
                throw invalid();
            }
            result.add(schema);
        }
        return List.copyOf(result);
    }

    static String safePath(String path) throws RimeImportException {
        if (path.isEmpty() || path.startsWith("/") || path.endsWith("/")
                || path.contains("\\") || path.contains("//")
                || path.matches("^[A-Za-z]:.*") || !Normalizer.isNormalized(path, Normalizer.Form.NFC)
                || path.getBytes(StandardCharsets.UTF_8).length > 4096
                || containsUnsafeDisplay(path)) {
            throw new RimeImportException(RimeImportException.Code.PATH_INVALID);
        }
        String[] segments = path.split("/", -1);
        if (segments.length == 0 || segments.length > 32) {
            throw new RimeImportException(RimeImportException.Code.PATH_INVALID);
        }
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new RimeImportException(RimeImportException.Code.PATH_INVALID);
            }
        }
        return path;
    }

    private static String sourceUrl(Object value) throws RimeImportException {
        if (value == null) return null;
        String source = boundedText(value, 2048);
        try {
            URI uri = new URI(source);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw invalid();
            }
        } catch (URISyntaxException error) {
            throw new RimeImportException(RimeImportException.Code.MANIFEST_INVALID, error);
        }
        return source;
    }

    private static int compareVersions(String left, String right) throws RimeImportException {
        int[] leftParts = versionParts(left);
        int[] rightParts = versionParts(right);
        for (int index = 0; index < Math.max(leftParts.length, rightParts.length); index++) {
            int leftPart = index < leftParts.length ? leftParts[index] : 0;
            int rightPart = index < rightParts.length ? rightParts[index] : 0;
            if (leftPart != rightPart) return Integer.compare(leftPart, rightPart);
        }
        return 0;
    }

    private static int[] versionParts(String value) throws RimeImportException {
        String[] raw = value.split("\\.", -1);
        if (raw.length < 1 || raw.length > 4) throw invalid();
        int[] result = new int[raw.length];
        for (int index = 0; index < raw.length; index++) {
            if (!raw[index].matches("0|[1-9][0-9]{0,8}")) throw invalid();
            try {
                result[index] = Integer.parseInt(raw[index]);
            } catch (NumberFormatException error) {
                throw new RimeImportException(RimeImportException.Code.MANIFEST_INVALID, error);
            }
        }
        return result;
    }

    private static long addBounded(long current, long value) throws RimeImportException {
        if (value > MAXIMUM_TOTAL_BYTES - current) {
            throw new RimeImportException(RimeImportException.Code.ARCHIVE_LIMIT);
        }
        return current + value;
    }

    private static String boundedText(Object value, int maximum) throws RimeImportException {
        String text = string(value);
        if (text.isEmpty() || text.codePointCount(0, text.length()) > maximum
                || containsUnsafeDisplay(text)) {
            throw invalid();
        }
        return text;
    }

    private static boolean containsUnsafeDisplay(String value) {
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            if (Character.isISOControl(codePoint)
                    || codePoint == 0x061c || codePoint == 0x200e || codePoint == 0x200f
                    || (codePoint >= 0x2028 && codePoint <= 0x202e)
                    || (codePoint >= 0x2066 && codePoint <= 0x2069)) {
                return true;
            }
            offset += Character.charCount(codePoint);
        }
        return false;
    }

    private static String pathIdentity(String path) {
        return Normalizer.normalize(path, Normalizer.Form.NFC)
                .toUpperCase(Locale.ROOT)
                .toLowerCase(Locale.ROOT);
    }

    private static void requireConstant(Map<String, Object> object, String key, Object expected)
            throws RimeImportException {
        if (!expected.equals(object.get(key))) throw invalid();
    }

    private static void requireKeys(Map<String, Object> value, Set<String> expected)
            throws RimeImportException {
        if (!value.keySet().equals(expected)) throw invalid();
    }

    private static String string(Object value) throws RimeImportException {
        if (!(value instanceof String result)) throw invalid();
        return result;
    }

    private static long integer(Object value) throws RimeImportException {
        if (!(value instanceof Long result)) throw invalid();
        return result;
    }

    private static Map<String, Object> object(Object value) throws RimeImportException {
        if (!(value instanceof Map<?, ?> raw)) throw invalid();
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw invalid();
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static List<Object> array(Object value, int minimum, int maximum)
            throws RimeImportException {
        if (!(value instanceof List<?> raw) || raw.size() < minimum || raw.size() > maximum) {
            throw invalid();
        }
        return new ArrayList<>(raw);
    }

    private static RimeImportException invalid() {
        return new RimeImportException(RimeImportException.Code.MANIFEST_INVALID);
    }
}
