// SPDX-License-Identifier: MIT
// Copyright (c) 2025 OpenTypeless Contributors

package com.opentypeless.ksp004;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Editor-capability-free JNI adapter for the pinned librime 1.17.0 runtime. */
public final class RimeAdapter implements AutoCloseable {
    public static final String EXPECTED_VERSION = "1.17.0";
    private static final int MAXIMUM_ASCII_INPUT = 128;
    private static final int MAXIMUM_PATH_BYTES = 4096;
    private static final int MAXIMUM_SCHEMA_ID_CODE_POINTS = 128;
    private static final int MAXIMUM_RIME_TEXT_CODE_POINTS = 256;
    private static final int MAXIMUM_RIME_TEXT_UTF16_UNITS = 512;

    private final String schemaId;
    private long session;
    private boolean closed;
    private boolean simplifiedOutput;
    private boolean asciiPunctuation;
    private boolean fullShape;

    static {
        System.loadLibrary("rime");
        System.loadLibrary("opentypeless_rime");
    }

    private RimeAdapter(String schemaId, long session) {
        this.schemaId = schemaId;
        this.session = session;
    }

    /**
     * Loads and initializes the exact native pair without deploying a Schema or creating UserDB.
     * The global native runtime is always finalized before this call returns.
     */
    public static synchronized RuntimeInfo probe(File rootDirectory) throws IOException {
        RuntimePaths paths = prepare(rootDirectory);

        boolean initialized = false;
        try {
            if (!nativeInitialize(paths.shared().getPath(), paths.user().getPath())) {
                throw new IllegalStateException("Rime runtime initialization failed");
            }
            initialized = true;
            String version = Objects.requireNonNull(nativeVersion(), "Rime runtime version");
            if (!EXPECTED_VERSION.equals(version)) {
                throw new IllegalStateException("Rime runtime version mismatch");
            }
            return new RuntimeInfo(version);
        } finally {
            if (initialized) {
                nativeFinalizeEngine();
            }
        }
    }

    /**
     * Initializes the pinned engine against an already validated private staging tree and waits
     * for a complete maintenance deployment. The global native runtime is finalized before return.
     */
    public static synchronized RuntimeInfo dryDeploy(File rootDirectory) throws IOException {
        RuntimePaths paths = prepare(rootDirectory);
        boolean initialized = false;
        try {
            if (!nativeInitialize(paths.shared().getPath(), paths.user().getPath())) {
                throw new IllegalStateException("Rime runtime initialization failed");
            }
            initialized = true;
            String version = Objects.requireNonNull(nativeVersion(), "Rime runtime version");
            if (!EXPECTED_VERSION.equals(version)) {
                throw new IllegalStateException("Rime runtime version mismatch");
            }
            if (!nativeDeploy()) {
                throw new IllegalStateException("Rime staging deployment failed");
            }
            return new RuntimeInfo(version);
        } finally {
            if (initialized) {
                nativeFinalizeEngine();
            }
        }
    }

    /**
     * Opens one process-local session against an already validated and deployed private package.
     * The returned adapter owns the global native runtime until {@link #close()}.
     */
    public static synchronized RimeAdapter open(File rootDirectory, String schemaId)
            throws IOException {
        RuntimePaths paths = prepare(rootDirectory);
        return open(paths, schemaId, false);
    }

    /**
     * Opens a session with validated resources and persistent UserDB in physically separate
     * private directories. Maintenance deploys generated cache into the UserDB domain first.
     */
    public static synchronized RimeAdapter open(
            File sharedDirectory, File userDirectory, String schemaId) throws IOException {
        RuntimePaths paths = prepareDirectories(sharedDirectory, userDirectory);
        return open(paths, schemaId, true);
    }

    /** Opens a session from an exact deployment previously completed for the same resources. */
    public static synchronized RimeAdapter openDeployed(
            File sharedDirectory, File userDirectory, String schemaId) throws IOException {
        RuntimePaths paths = prepareDirectories(sharedDirectory, userDirectory);
        return open(paths, schemaId, false);
    }

    private static RimeAdapter open(RuntimePaths paths, String schemaId, boolean deploy)
            throws IOException {
        String safeSchema = requireSchemaId(schemaId);
        boolean initialized = false;
        try {
            if (!nativeInitialize(paths.shared().getPath(), paths.user().getPath())) {
                throw new IllegalStateException("Rime runtime initialization failed");
            }
            initialized = true;
            String version = Objects.requireNonNull(nativeVersion(), "Rime runtime version");
            if (!EXPECTED_VERSION.equals(version)) {
                throw new IllegalStateException("Rime runtime version mismatch");
            }
            if (deploy && !nativeDeploy()) {
                throw new IllegalStateException("Rime persistent runtime deployment failed");
            }
            long session = nativeCreateSession(safeSchema);
            if (session == 0L) {
                throw new IllegalStateException("Rime session creation failed");
            }
            return new RimeAdapter(safeSchema, session);
        } catch (RuntimeException | LinkageError failure) {
            if (initialized) nativeFinalizeEngine();
            throw failure;
        }
    }

    /** Replays one bounded lowercase-ASCII key sequence and returns a bounded native snapshot. */
    public Snapshot processAscii(String input) {
        synchronized (RimeAdapter.class) {
            requireOpen();
            requireAsciiInput(input);
            if (!nativeProcessAscii(session, input)) {
                throw new IllegalStateException("Rime rejected bounded ASCII input");
            }
            return readSnapshot(session);
        }
    }

    /**
     * Consumes one bounded commit emitted by key processing, or returns {@code null} when the
     * current key sequence only changed composition state.
     *
     * <p>Table schemas can commit without an explicit candidate click, for example when a fixed
     * maximum code length selects a unique entry. Reading the snapshot alone would silently lose
     * that native commit because the post-commit composition is already empty.</p>
     */
    public String takePendingCommit() {
        synchronized (RimeAdapter.class) {
            requireOpen();
            String commit = nativeTakeCommit(session);
            return commit == null ? null : requireBoundedText(commit, false, "commit");
        }
    }

    /** Applies one of the three audited boolean options and verifies the native value. */
    public void setOption(String optionName, boolean enabled) {
        synchronized (RimeAdapter.class) {
            requireOpen();
            String safeOption = requireOptionName(optionName);
            if (!nativeSetOption(session, safeOption, enabled)) {
                throw new IllegalStateException("Rime option update failed");
            }
            switch (safeOption) {
                case "simplification" -> simplifiedOutput = enabled;
                case "ascii_punct" -> asciiPunctuation = enabled;
                case "full_shape" -> fullShape = enabled;
                default -> throw new IllegalStateException("unreachable Rime option");
            }
        }
    }

    /** Clears composition by replacing the exact owned native session with a fresh one. */
    public Snapshot resetComposition() {
        synchronized (RimeAdapter.class) {
            requireOpen();
            long previous = session;
            if (!nativeDestroySession(previous)) {
                throw new IllegalStateException("Rime session reset failed");
            }
            session = 0L;
            long replacement = nativeCreateSession(schemaId);
            if (replacement == 0L) {
                closed = true;
                nativeFinalizeEngine();
                throw new IllegalStateException("Rime session recreation failed");
            }
            session = replacement;
            try {
                applyStoredOptions();
            } catch (RuntimeException failure) {
                nativeDestroySession(replacement);
                session = 0L;
                closed = true;
                nativeFinalizeEngine();
                throw failure;
            }
            return new Snapshot("", List.of());
        }
    }

    /** Selects one candidate and returns only the bounded commit text. */
    public String selectCandidate(int index) {
        synchronized (RimeAdapter.class) {
            requireOpen();
            if (index < 0 || index >= 16 || !nativeSelectCandidate(session, index)) {
                throw new IllegalArgumentException("Invalid Rime candidate index");
            }
            return requireBoundedText(nativeTakeCommit(session), false, "commit");
        }
    }

    /**
     * Flushes Rime-managed UserDB state and terminates this native session at an explicit
     * application consistency point.
     *
     * <p>librime's sync operation cleans up every session before scheduling maintenance. The
     * adapter therefore becomes closed and finalizes the runtime so callers can checkpoint the
     * resulting files only after the maintenance thread has joined.</p>
     */
    public void synchronizeUserData() {
        synchronized (RimeAdapter.class) {
            requireOpen();
            try {
                if (!nativeSyncUserData()) {
                    throw new IllegalStateException("Rime UserDB synchronization failed");
                }
            } finally {
                session = 0L;
                closed = true;
                nativeFinalizeEngine();
            }
        }
    }

    @Override
    public void close() {
        synchronized (RimeAdapter.class) {
            if (closed) return;
            closed = true;
            long ownedSession = session;
            session = 0L;
            try {
                if (ownedSession != 0L && !nativeDestroySession(ownedSession)) {
                    throw new IllegalStateException("Rime session close failed");
                }
            } finally {
                nativeFinalizeEngine();
            }
        }
    }

    private static RuntimePaths prepare(File rootDirectory) throws IOException {
        Objects.requireNonNull(rootDirectory, "rootDirectory");
        File root = rootDirectory.getCanonicalFile();
        File shared = new File(root, "shared").getCanonicalFile();
        File user = new File(root, "user").getCanonicalFile();
        requireChild(root, shared);
        requireChild(root, user);
        requireDirectory(shared);
        requireDirectory(user);
        requireBoundedPath(shared);
        requireBoundedPath(user);
        return new RuntimePaths(shared, user);
    }

    private static RuntimePaths prepareDirectories(File sharedDirectory, File userDirectory)
            throws IOException {
        Objects.requireNonNull(sharedDirectory, "sharedDirectory");
        Objects.requireNonNull(userDirectory, "userDirectory");
        File shared = sharedDirectory.getCanonicalFile();
        File user = userDirectory.getCanonicalFile();
        requireDirectory(shared);
        requireDirectory(user);
        requireBoundedPath(shared);
        requireBoundedPath(user);
        if (shared.equals(user)) throw new IOException("Rime shared and UserDB directories overlap");
        return new RuntimePaths(shared, user);
    }

    private static void requireChild(File root, File child) throws IOException {
        String prefix = root.getPath() + File.separator;
        if (!child.getPath().startsWith(prefix)) {
            throw new IOException("Rime runtime directory escaped its root");
        }
    }

    private static void requireDirectory(File directory) throws IOException {
        if ((!directory.isDirectory() && !directory.mkdirs()) || !directory.isDirectory()) {
            throw new IOException("Unable to create Rime runtime directory");
        }
    }

    private static void requireBoundedPath(File directory) throws IOException {
        int bytes = directory.getPath().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (bytes == 0 || bytes > MAXIMUM_PATH_BYTES) {
            throw new IOException("Rime runtime path exceeded the fixed bound");
        }
    }

    private static Snapshot readSnapshot(long session) {
        String[] raw = nativeSnapshot(session);
        if (raw == null || raw.length == 0 || raw.length > 17) {
            throw new IllegalStateException("Rime returned an invalid snapshot");
        }
        String preedit = requireBoundedText(raw[0], true, "preedit");
        ArrayList<String> candidates = new ArrayList<>(raw.length - 1);
        for (int index = 1; index < raw.length; index++) {
            candidates.add(requireBoundedText(raw[index], false, "candidate"));
        }
        return new Snapshot(preedit, candidates);
    }

    private void requireOpen() {
        if (closed || session == 0L) throw new IllegalStateException("Rime adapter is closed");
    }

    private static String requireSchemaId(String value) {
        String safe = Objects.requireNonNull(value, "schemaId");
        if (safe.isEmpty()
                || safe.length() > MAXIMUM_SCHEMA_ID_CODE_POINTS * 2
                || safe.codePointCount(0, safe.length()) > MAXIMUM_SCHEMA_ID_CODE_POINTS) {
            throw new IllegalArgumentException("Rime schema id exceeded its bound");
        }
        for (int offset = 0; offset < safe.length(); offset++) {
            char character = safe.charAt(offset);
            if (!((character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '.' || character == '_' || character == '-')) {
                throw new IllegalArgumentException("Rime schema id is invalid");
            }
        }
        return safe;
    }

    private static String requireOptionName(String value) {
        String safe = Objects.requireNonNull(value, "optionName");
        return switch (safe) {
            case "simplification", "ascii_punct", "full_shape" -> safe;
            default -> throw new IllegalArgumentException("unsupported Rime option");
        };
    }

    private void applyStoredOptions() {
        if (!nativeSetOption(session, "simplification", simplifiedOutput)
                || !nativeSetOption(session, "ascii_punct", asciiPunctuation)
                || !nativeSetOption(session, "full_shape", fullShape)) {
            throw new IllegalStateException("Rime option restore failed");
        }
    }

    private static void requireAsciiInput(String input) {
        Objects.requireNonNull(input, "input");
        if (input.isEmpty() || input.length() > MAXIMUM_ASCII_INPUT) {
            throw new IllegalArgumentException("Rime input length exceeded its bound");
        }
        for (int index = 0; index < input.length(); index++) {
            char character = input.charAt(index);
            if (character < 'a' || character > 'z') {
                throw new IllegalArgumentException("Rime input must be lowercase ASCII");
            }
        }
    }

    private static String requireBoundedText(String value, boolean allowEmpty, String name) {
        String safe = Objects.requireNonNull(value, name);
        if ((!allowEmpty && safe.isEmpty())
                || safe.length() > MAXIMUM_RIME_TEXT_UTF16_UNITS
                || safe.codePointCount(0, safe.length()) > MAXIMUM_RIME_TEXT_CODE_POINTS) {
            throw new IllegalStateException("Rime " + name + " exceeded its bound");
        }
        for (int offset = 0; offset < safe.length(); ) {
            int codePoint = safe.codePointAt(offset);
            if (Character.isISOControl(codePoint)
                    || codePoint == 0x061c
                    || (codePoint >= 0x200e && codePoint <= 0x200f)
                    || (codePoint >= 0x202a && codePoint <= 0x202e)
                    || (codePoint >= 0x2066 && codePoint <= 0x2069)) {
                throw new IllegalStateException("Rime " + name + " contains unsafe text");
            }
            offset += Character.charCount(codePoint);
        }
        return safe;
    }

    public record RuntimeInfo(String version) {
        public RuntimeInfo {
            if (!EXPECTED_VERSION.equals(version)) {
                throw new IllegalArgumentException("Unexpected Rime runtime version");
            }
        }

        @Override
        public String toString() {
            return "RuntimeInfo{version=" + version + '}';
        }
    }

    public static final class Snapshot {
        private final String preedit;
        private final List<String> candidates;

        private Snapshot(String preedit, List<String> candidates) {
            this.preedit = Objects.requireNonNull(preedit, "preedit");
            this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
        }

        public String preedit() {
            return preedit;
        }

        public List<String> candidates() {
            return candidates;
        }

        @Override
        public String toString() {
            return "Snapshot{preedit=<redacted>, candidateCount=" + candidates.size() + '}';
        }
    }

    private record RuntimePaths(File shared, File user) {}

    private static native boolean nativeInitialize(String sharedDirectory, String userDirectory);

    private static native boolean nativeDeploy();

    private static native long nativeCreateSession(String schemaId);

    private static native boolean nativeDestroySession(long session);

    private static native boolean nativeProcessAscii(long session, String input);

    private static native boolean nativeSetOption(
            long session, String optionName, boolean enabled);

    private static native String[] nativeSnapshot(long session);

    private static native boolean nativeSelectCandidate(long session, int index);

    private static native String nativeTakeCommit(long session);

    private static native boolean nativeSyncUserData();

    private static native String nativeVersion();

    private static native void nativeFinalizeEngine();
}
