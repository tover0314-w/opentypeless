package com.opentypeless.android.rime.importer;

import android.content.Context;

import com.opentypeless.ksp004.RimeAdapter;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** App-private, no-backup staging and atomic activation for user-provided Rime resources. */
public final class RimeResourceStore {
    private static final String ROOT_NAME = "rime_resources";
    private static final String CURRENT_NAME = "current";
    private static final String ROLLBACK_NAME = ".rollback";
    private static final String STAGING_PREFIX = ".staging-";
    private static final String INCOMING_NAME = "incoming.zip";
    private static final ReentrantLock OPERATION_LOCK = new ReentrantLock();

    @FunctionalInterface
    interface Deployer {
        void deploy(File stagingRoot) throws Exception;
    }

    public record Installed(
            String packageId,
            String packageVersion,
            String displayName,
            String trustState,
            String distributionScope,
            int schemaCount,
            int fileCount,
            long totalBytes) {}

    /** Canonical, locally validated runtime root and its closed selected-schema set. */
    public record RuntimePackage(
            File root, List<String> selectedSchemas, String deploymentId) {
        public RuntimePackage {
            root = Objects.requireNonNull(root, "root");
            selectedSchemas = List.copyOf(selectedSchemas);
            if (selectedSchemas.isEmpty()) {
                throw new IllegalArgumentException("selectedSchemas must not be empty");
            }
            deploymentId = Objects.requireNonNull(deploymentId, "deploymentId");
            if (!deploymentId.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("deploymentId must be SHA-256");
            }
        }

        @Override
        public String toString() {
            return "RuntimePackage{schemaCount=" + selectedSchemas.size() + ", root=<redacted>}";
        }
    }

    public static final class StagedImport implements AutoCloseable {
        private final File root;
        private final RimeResourceManifest manifest;
        private boolean consumed;

        private StagedImport(File root, RimeResourceManifest manifest) {
            this.root = root;
            this.manifest = manifest;
        }

        public RimeResourceManifest.Preview preview() {
            return manifest.preview();
        }

        @Override
        public void close() {
            if (!consumed) deleteTree(root);
            consumed = true;
        }
    }

    private final File root;
    private final Deployer deployer;

    public RimeResourceStore(Context context) {
        this(
                new File(Objects.requireNonNull(context, "context").getNoBackupFilesDir(), ROOT_NAME),
                staging -> RimeAdapter.dryDeploy(staging));
    }

    RimeResourceStore(File root, Deployer deployer) {
        this.root = Objects.requireNonNull(root, "root");
        this.deployer = Objects.requireNonNull(deployer, "deployer");
    }

    public StagedImport stage(InputStream selectedDocument) throws RimeImportException {
        Objects.requireNonNull(selectedDocument, "selectedDocument");
        if (!OPERATION_LOCK.tryLock()) {
            throw new RimeImportException(RimeImportException.Code.BUSY);
        }
        File staging = null;
        try {
            requireRoot();
            recoverInterruptedCommit();
            staging = child(root, STAGING_PREFIX + UUID.randomUUID());
            requireDirectory(staging);
            File incoming = child(staging, INCOMING_NAME);
            copyIncoming(selectedDocument, incoming);
            RimeResourceArchive.Extracted extracted = RimeResourceArchive.extract(incoming, staging);
            if (!incoming.delete() && incoming.exists()) {
                throw new RimeImportException(RimeImportException.Code.STORAGE_FAILED);
            }
            return new StagedImport(staging, extracted.manifest());
        } catch (RimeImportException error) {
            deleteTree(staging);
            throw error;
        } finally {
            OPERATION_LOCK.unlock();
        }
    }

    public Installed commit(StagedImport staged) throws RimeImportException {
        Objects.requireNonNull(staged, "staged");
        if (!OPERATION_LOCK.tryLock()) {
            throw new RimeImportException(RimeImportException.Code.BUSY);
        }
        try {
            if (staged.consumed || !isDirectStagingChild(staged.root)) {
                throw new RimeImportException(RimeImportException.Code.STORAGE_FAILED);
            }
            try {
                deployer.deploy(staged.root);
            } catch (Exception error) {
                staged.close();
                throw new RimeImportException(RimeImportException.Code.DEPLOY_FAILED, error);
            }
            File current = child(root, CURRENT_NAME);
            File rollback = child(root, ROLLBACK_NAME);
            if (rollback.exists()) deleteTreeRequired(rollback);
            if (current.exists() && !current.renameTo(rollback)) {
                staged.close();
                throw new RimeImportException(RimeImportException.Code.STORAGE_FAILED);
            }
            if (!staged.root.renameTo(current)) {
                if (rollback.exists() && !rollback.renameTo(current)) {
                    throw new RimeImportException(RimeImportException.Code.STORAGE_FAILED);
                }
                staged.close();
                throw new RimeImportException(RimeImportException.Code.STORAGE_FAILED);
            }
            staged.consumed = true;
            if (rollback.exists()) deleteTreeRequired(rollback);
            return installed(staged.manifest);
        } finally {
            OPERATION_LOCK.unlock();
        }
    }

    public Installed status() throws RimeImportException {
        if (!OPERATION_LOCK.tryLock()) {
            throw new RimeImportException(RimeImportException.Code.BUSY);
        }
        try {
            requireRoot();
            recoverInterruptedCommit();
            File current = child(root, CURRENT_NAME);
            if (!current.isDirectory()) return null;
            byte[] manifestBytes = readLimited(
                    child(current, "import-manifest.json"),
                    1_048_576);
            return installed(RimeResourceManifest.parse(manifestBytes));
        } finally {
            OPERATION_LOCK.unlock();
        }
    }

    /**
     * Returns the active package only after re-reading its closed manifest under the store lock.
     * Callers must invoke this method on a worker because it performs bounded private-file I/O.
     */
    public RuntimePackage runtimePackage() throws RimeImportException {
        if (!OPERATION_LOCK.tryLock()) {
            throw new RimeImportException(RimeImportException.Code.BUSY);
        }
        try {
            requireRoot();
            recoverInterruptedCommit();
            File current = child(root, CURRENT_NAME);
            if (!current.isDirectory()) return null;
            byte[] manifestBytes = readLimited(
                    child(current, "import-manifest.json"),
                    1_048_576);
            RimeResourceManifest manifest = RimeResourceManifest.parse(manifestBytes);
            for (String schema : manifest.selectedSchemas()) {
                File schemaFile = child(current, "shared/" + schema + ".schema.yaml");
                if (!schemaFile.isFile()) {
                    throw new RimeImportException(RimeImportException.Code.STORAGE_FAILED);
                }
            }
            try {
                return new RuntimePackage(
                        current.getCanonicalFile(),
                        manifest.selectedSchemas(),
                        sha256(manifestBytes));
            } catch (IOException error) {
                throw new RimeImportException(RimeImportException.Code.STORAGE_FAILED, error);
            }
        } finally {
            OPERATION_LOCK.unlock();
        }
    }

    private static String sha256(byte[] bytes) throws RimeImportException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder encoded = new StringBuilder(digest.length * 2);
            for (byte value : digest) encoded.append(String.format("%02x", value & 0xff));
            return encoded.toString();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new RimeImportException(
                    RimeImportException.Code.STORAGE_FAILED, unavailable);
        }
    }

    /** Deletes the local-only imported package and its isolated runtime state. */
    public void clear() throws RimeImportException {
        if (!OPERATION_LOCK.tryLock()) {
            throw new RimeImportException(RimeImportException.Code.BUSY);
        }
        try {
            requireRoot();
            recoverInterruptedCommit();
            deleteTreeRequired(child(root, CURRENT_NAME));
            deleteTreeRequired(child(root, ROLLBACK_NAME));
            File[] children = root.listFiles();
            if (children == null) {
                throw new RimeImportException(RimeImportException.Code.STORAGE_FAILED);
            }
            for (File child : children) {
                if (child.getName().startsWith(STAGING_PREFIX)) deleteTreeRequired(child);
            }
        } finally {
            OPERATION_LOCK.unlock();
        }
    }

    private void copyIncoming(InputStream selectedDocument, File incoming)
            throws RimeImportException {
        try (InputStream input = new BufferedInputStream(selectedDocument);
             FileOutputStream output = new FileOutputStream(incoming)) {
            byte[] buffer = new byte[16_384];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read == 0) continue;
                total += read;
                if (total > RimeResourceArchive.MAXIMUM_ARCHIVE_BYTES) {
                    throw new RimeImportException(RimeImportException.Code.ARCHIVE_LIMIT);
                }
                output.write(buffer, 0, read);
            }
            if (total == 0) {
                throw new RimeImportException(RimeImportException.Code.ARCHIVE_INVALID);
            }
            output.getFD().sync();
        } catch (RimeImportException error) {
            throw error;
        } catch (IOException | SecurityException error) {
            throw new RimeImportException(RimeImportException.Code.SOURCE_UNREADABLE, error);
        }
    }

    private void recoverInterruptedCommit() throws RimeImportException {
        File current = child(root, CURRENT_NAME);
        File rollback = child(root, ROLLBACK_NAME);
        if (current.exists() && rollback.exists()) {
            deleteTreeRequired(rollback);
        } else if (!current.exists() && rollback.exists() && !rollback.renameTo(current)) {
            throw new RimeImportException(RimeImportException.Code.STORAGE_FAILED);
        }
    }

    private boolean isDirectStagingChild(File candidate) throws RimeImportException {
        try {
            File canonicalRoot = root.getCanonicalFile();
            File canonical = candidate.getCanonicalFile();
            return canonical.getParentFile() != null
                    && canonical.getParentFile().equals(canonicalRoot)
                    && canonical.getName().startsWith(STAGING_PREFIX)
                    && canonical.isDirectory();
        } catch (IOException error) {
            throw new RimeImportException(RimeImportException.Code.STORAGE_FAILED, error);
        }
    }

    private static Installed installed(RimeResourceManifest manifest) {
        return new Installed(
                manifest.packageId(),
                manifest.packageVersion(),
                manifest.displayName(),
                RimeResourceManifest.TRUST_STATE,
                RimeResourceManifest.DISTRIBUTION_SCOPE,
                manifest.selectedSchemas().size(),
                manifest.allFilesByPath().size(),
                manifest.totalBytes());
    }

    private void requireRoot() throws RimeImportException {
        requireDirectory(root);
        try {
            if (!root.getCanonicalFile().equals(root.getAbsoluteFile().getCanonicalFile())) {
                throw new RimeImportException(RimeImportException.Code.STORAGE_FAILED);
            }
        } catch (IOException error) {
            throw new RimeImportException(RimeImportException.Code.STORAGE_FAILED, error);
        }
    }

    private static File child(File root, String name) throws RimeImportException {
        try {
            File canonicalRoot = root.getCanonicalFile();
            File result = new File(canonicalRoot, name).getCanonicalFile();
            if (!result.getPath().startsWith(canonicalRoot.getPath() + File.separator)) {
                throw new RimeImportException(RimeImportException.Code.PATH_INVALID);
            }
            return result;
        } catch (IOException error) {
            throw new RimeImportException(RimeImportException.Code.STORAGE_FAILED, error);
        }
    }

    private static void requireDirectory(File directory) throws RimeImportException {
        if ((!directory.isDirectory() && !directory.mkdirs()) || !directory.isDirectory()) {
            throw new RimeImportException(RimeImportException.Code.STORAGE_FAILED);
        }
    }

    private static byte[] readLimited(File file, int maximum) throws RimeImportException {
        if (!file.isFile() || file.length() <= 0 || file.length() > maximum) {
            throw new RimeImportException(RimeImportException.Code.STORAGE_FAILED);
        }
        try (InputStream input = new FileInputStream(file)) {
            int length = (int) file.length();
            byte[] result = new byte[length];
            int offset = 0;
            while (offset < result.length) {
                int read = input.read(result, offset, result.length - offset);
                if (read < 0) break;
                if (read > 0) offset += read;
            }
            if (offset != result.length || input.read() != -1) {
                throw new RimeImportException(RimeImportException.Code.STORAGE_FAILED);
            }
            return result;
        } catch (IOException error) {
            throw new RimeImportException(RimeImportException.Code.STORAGE_FAILED, error);
        }
    }

    private static void deleteTreeRequired(File target) throws RimeImportException {
        if (target == null || !target.exists()) return;
        if (!deleteTree(target)) {
            throw new RimeImportException(RimeImportException.Code.STORAGE_FAILED);
        }
    }

    private static boolean deleteTree(File target) {
        if (target == null || !target.exists()) return true;
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children == null) return false;
            for (File child : children) {
                if (!deleteTree(child)) return false;
            }
        }
        return target.delete();
    }
}
