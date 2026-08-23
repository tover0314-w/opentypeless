package com.opentypeless.android.keyboard.rime;

import com.opentypeless.android.keyboard.candidate.CandidatePage;
import com.opentypeless.android.rime.userdata.RimeUserDataException;
import com.opentypeless.android.rime.userdata.RimeUserDataStore;
import com.opentypeless.ksp004.RimeAdapter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Bounded, editor-capability-free RIM-004 adapter over the pinned native runtime. */
public final class NativeRimeInputEngine implements RimeInputEngine {
    private static final int MAXIMUM_ASCII_INPUT = 128;
    private static final int CANDIDATES_PER_PAGE = 5;
    private static final String DEPLOYMENT_MARKER =
            ".opentypeless-resource-deployment-v1";

    interface Session extends AutoCloseable {
        void setOption(String optionName, boolean enabled);
        NativeSnapshot processAscii(String input);
        default String takePendingCommit() { return null; }
        NativeSnapshot resetComposition();
        String selectCandidate(int index);
        void synchronizeUserData();
        @Override void close();
    }

    record NativeSnapshot(String preedit, List<String> candidates) {
        NativeSnapshot {
            preedit = RimeEngineSnapshot.requireBoundedText(preedit, true, "preedit");
            candidates = List.copyOf(candidates);
            if (candidates.size() > CandidatePage.MAXIMUM_CANDIDATES) {
                throw new IllegalArgumentException("candidate count exceeded the bound");
            }
            for (String candidate : candidates) {
                RimeEngineSnapshot.requireBoundedText(candidate, false, "candidate");
            }
        }
    }

    @FunctionalInterface
    interface SessionFactory {
        Session open(File sharedDirectory, File userDirectory, String schemaId) throws Exception;
    }

    interface UserDataLease extends AutoCloseable {
        File directory();
        void checkpoint() throws Exception;
        boolean restoreLatestCheckpoint() throws Exception;
        @Override void close();
    }

    @FunctionalInterface
    interface UserDataLeaseFactory {
        UserDataLease open() throws Exception;
    }

    private final File runtimeRoot;
    private final RimeRuntimeConfig runtimeConfig;
    private final UserDataLeaseFactory userDataLeaseFactory;
    private final SessionFactory sessionFactory;
    private final SessionFactory preparedSessionFactory;
    private final String deploymentId;
    private final StringBuilder asciiInput = new StringBuilder();
    private List<String> activeCandidates = List.of();
    private int activePageIndex;

    private RimeEngineSnapshot state = RimeEngineSnapshot.inactive();
    private Session session;
    private UserDataLease userDataLease;
    private boolean closed;

    public NativeRimeInputEngine(File runtimeRoot, String schemaId) {
        this(runtimeRoot, RimeRuntimeConfig.defaults(schemaId));
    }

    public NativeRimeInputEngine(File runtimeRoot, RimeRuntimeConfig runtimeConfig) {
        this(runtimeRoot, runtimeConfig, legacyUserData(runtimeRoot),
                NativeRimeInputEngine::openNativeSession);
    }

    /** Product constructor with no-backup UserDB isolated from user-imported Schema resources. */
    public NativeRimeInputEngine(
            File runtimeRoot,
            RimeRuntimeConfig runtimeConfig,
            RimeUserDataStore userDataStore) {
        this(runtimeRoot, runtimeConfig, persistentUserData(userDataStore),
                NativeRimeInputEngine::openNativeSession);
    }

    /** Product constructor that reuses only an exact previously deployed resource cache. */
    public NativeRimeInputEngine(
            File runtimeRoot,
            RimeRuntimeConfig runtimeConfig,
            RimeUserDataStore userDataStore,
            String deploymentId) {
        this(runtimeRoot, runtimeConfig, persistentUserData(userDataStore),
                NativeRimeInputEngine::openNativeSession,
                NativeRimeInputEngine::openPreparedNativeSession,
                requireDeploymentId(deploymentId));
    }

    NativeRimeInputEngine(
            File runtimeRoot,
            RimeRuntimeConfig runtimeConfig,
            SessionFactory sessionFactory) {
        this(runtimeRoot, runtimeConfig, legacyUserData(runtimeRoot), sessionFactory);
    }

    NativeRimeInputEngine(
            File runtimeRoot,
            RimeRuntimeConfig runtimeConfig,
            UserDataLeaseFactory userDataLeaseFactory,
            SessionFactory sessionFactory) {
        this(runtimeRoot, runtimeConfig, userDataLeaseFactory, sessionFactory, null, null);
    }

    NativeRimeInputEngine(
            File runtimeRoot,
            RimeRuntimeConfig runtimeConfig,
            UserDataLeaseFactory userDataLeaseFactory,
            SessionFactory sessionFactory,
            SessionFactory preparedSessionFactory,
            String deploymentId) {
        this.runtimeRoot = Objects.requireNonNull(runtimeRoot, "runtimeRoot");
        this.runtimeConfig = Objects.requireNonNull(runtimeConfig, "runtimeConfig");
        this.userDataLeaseFactory = Objects.requireNonNull(
                userDataLeaseFactory, "userDataLeaseFactory");
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
        this.preparedSessionFactory = preparedSessionFactory;
        this.deploymentId = deploymentId;
        if ((preparedSessionFactory == null) != (deploymentId == null)) {
            throw new IllegalArgumentException(
                    "prepared session factory and deployment id must be paired");
        }
    }

    @Override
    public synchronized LifecycleResult activate(Activation request) {
        Objects.requireNonNull(request, "request");
        if (closed) return rejected(FailureKind.CLOSED);
        if (state.phase() == RimeEngineSnapshot.Phase.ACTIVE) {
            return rejected(FailureKind.ALREADY_ACTIVE);
        }
        if (request.learningMode() != LearningMode.ENABLED) {
            return rejected(FailureKind.POLICY_DENIED);
        }
        try {
            userDataLease = userDataLeaseFactory.open();
            try {
                session = openSession(userDataLease);
            } catch (Exception | LinkageError firstFailure) {
                if (!userDataLease.restoreLatestCheckpoint()) throw firstFailure;
                session = openSession(userDataLease);
            }
            for (String option : RimeRuntimeConfig.supportedOptions()) {
                session.setOption(option, runtimeConfig.optionValue(option));
            }
            asciiInput.setLength(0);
            activeCandidates = List.of();
            activePageIndex = 0;
            state = RimeEngineSnapshot.active(
                    request.editorGeneration(), request.coordinationGeneration(),
                    request.initialRevision(), "", null);
            return new LifecycleApplied(state);
        } catch (Exception | LinkageError failure) {
            closeSession();
            return rejected(FailureKind.ENGINE_UNAVAILABLE);
        }
    }

    @Override
    public synchronized LifecycleResult deactivate(Deactivation request) {
        Objects.requireNonNull(request, "request");
        if (closed) return rejected(FailureKind.CLOSED);
        FailureKind mismatch = mismatch(request.editorGeneration(), request.coordinationGeneration());
        if (mismatch != null) return rejected(mismatch);
        closeSession();
        state = RimeEngineSnapshot.inactive();
        return new LifecycleApplied(state);
    }

    @Override
    public synchronized ProcessResult process(ProcessRequest request) {
        Objects.requireNonNull(request, "request");
        if (closed) return rejected(FailureKind.CLOSED);
        FailureKind mismatch = mismatch(request.editorGeneration(), request.coordinationGeneration());
        if (mismatch != null) return rejected(mismatch);
        try {
            NativeSnapshot nativeState;
            switch (request.key().kind()) {
                case PRINTABLE -> {
                    int codePoint = request.key().codePoint();
                    if (codePoint < 'a' || codePoint > 'z'
                            || asciiInput.length() >= MAXIMUM_ASCII_INPUT) {
                        return rejected(FailureKind.POLICY_DENIED);
                    }
                    asciiInput.append((char) codePoint);
                    nativeState = session.processAscii(asciiInput.toString());
                    String committed = session.takePendingCommit();
                    if (committed != null) return completeNativeCommit(committed);
                }
                case BACKSPACE -> {
                    if (asciiInput.length() == 0) return new StateReady(state);
                    asciiInput.deleteCharAt(asciiInput.length() - 1);
                    nativeState = asciiInput.length() == 0
                            ? session.resetComposition()
                            : session.processAscii(asciiInput.toString());
                }
                case ESCAPE -> {
                    asciiInput.setLength(0);
                    nativeState = session.resetComposition();
                }
                case ENTER -> {
                    return rejected(FailureKind.POLICY_DENIED);
                }
                default -> throw new IllegalStateException("unhandled key kind");
            }
            long nextRevision = incrementRevision(state.revision());
            activeCandidates = nativeState.candidates();
            activePageIndex = 0;
            CandidatePage candidates = candidatePage(
                    state.coordinationGeneration(), nextRevision,
                    activeCandidates, activePageIndex);
            state = RimeEngineSnapshot.active(
                    state.editorGeneration(), state.coordinationGeneration(), nextRevision,
                    nativeState.preedit(), candidates);
            return new StateReady(state);
        } catch (Exception | LinkageError failure) {
            closeSession();
            state = RimeEngineSnapshot.inactive();
            return rejected(FailureKind.ENGINE_FAILURE);
        }
    }

    @Override
    public synchronized ProcessResult selectCandidate(CandidateSelectionRequest request) {
        Objects.requireNonNull(request, "request");
        if (closed) return rejected(FailureKind.CLOSED);
        FailureKind mismatch = mismatch(
                request.editorGeneration(), request.selection().generation());
        if (mismatch != null) return rejected(mismatch);
        CandidatePage page = state.candidatePage().orElse(null);
        CandidatePage.Selection selection = request.selection();
        if (page == null
                || selection.pageRevision() != state.revision()
                || selection.pageIndex() != activePageIndex
                || selection.candidateIndex() >= page.items().size()
                || !page.selection(selection.candidateIndex()).equals(selection)) {
            return rejected(FailureKind.STALE_COORDINATION_GENERATION);
        }
        int nativeIndex = activePageIndex * CANDIDATES_PER_PAGE
                + selection.candidateIndex();
        if (nativeIndex < 0 || nativeIndex >= activeCandidates.size()) {
            return rejected(FailureKind.INVALID_OUTPUT);
        }
        try {
            String committed = RimeEngineSnapshot.requireBoundedText(
                    session.selectCandidate(nativeIndex), false, "commit text");
            if (!committed.equals(selection.expectedText())) {
                closeSession();
                state = RimeEngineSnapshot.inactive();
                return rejected(FailureKind.INVALID_OUTPUT);
            }
            return completeNativeCommit(committed);
        } catch (Exception | LinkageError failure) {
            closeSession();
            state = RimeEngineSnapshot.inactive();
            return rejected(FailureKind.ENGINE_FAILURE);
        }
    }

    @Override
    public synchronized ProcessResult requestCandidatePage(CandidatePageRequest request) {
        Objects.requireNonNull(request, "request");
        if (closed) return rejected(FailureKind.CLOSED);
        FailureKind mismatch = mismatch(
                request.editorGeneration(), request.request().generation());
        if (mismatch != null) return rejected(mismatch);
        CandidatePage current = state.candidatePage().orElse(null);
        CandidatePage.PageRequest pageRequest = request.request();
        if (current == null
                || pageRequest.pageRevision() != state.revision()
                || pageRequest.pageIndex() != activePageIndex) {
            return rejected(FailureKind.STALE_COORDINATION_GENERATION);
        }
        CandidatePage.PageRequest expected;
        try {
            expected = current.pageRequest(pageRequest.direction());
        } catch (IllegalStateException unavailable) {
            return rejected(FailureKind.POLICY_DENIED);
        }
        if (!expected.equals(pageRequest)) {
            return rejected(FailureKind.STALE_COORDINATION_GENERATION);
        }
        int nextPage = pageRequest.direction() == CandidatePage.Direction.NEXT
                ? activePageIndex + 1 : activePageIndex - 1;
        long nextRevision = incrementRevision(state.revision());
        CandidatePage next = candidatePage(
                state.coordinationGeneration(), nextRevision,
                activeCandidates, nextPage);
        if (next == null) return rejected(FailureKind.INVALID_OUTPUT);
        activePageIndex = nextPage;
        state = RimeEngineSnapshot.active(
                state.editorGeneration(), state.coordinationGeneration(),
                nextRevision, state.preedit(), next);
        return new StateReady(state);
    }

    @Override
    public synchronized SnapshotResult snapshot() {
        return closed ? rejected(FailureKind.CLOSED) : new SnapshotReady(state);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        closeSession();
        state = RimeEngineSnapshot.inactive();
    }

    private FailureKind mismatch(long editorGeneration, long coordinationGeneration) {
        if (state.phase() != RimeEngineSnapshot.Phase.ACTIVE || session == null) {
            return FailureKind.INACTIVE;
        }
        if (state.editorGeneration() != editorGeneration) {
            return FailureKind.STALE_EDITOR_GENERATION;
        }
        if (state.coordinationGeneration() != coordinationGeneration) {
            return FailureKind.STALE_COORDINATION_GENERATION;
        }
        return null;
    }

    private void closeSession() {
        Session owned = session;
        UserDataLease ownedUserData = userDataLease;
        session = null;
        userDataLease = null;
        asciiInput.setLength(0);
        activeCandidates = List.of();
        activePageIndex = 0;
        if (owned != null) {
            try {
                owned.synchronizeUserData();
                if (ownedUserData != null) ownedUserData.checkpoint();
            } catch (Exception | LinkageError ignored) {
                // The engine is already fail-closed and no editor capability is held here.
            } finally {
                try {
                    owned.close();
                } catch (RuntimeException ignored) {
                    // Continue releasing the process-local UserDB lease.
                }
            }
        }
        if (ownedUserData != null) ownedUserData.close();
    }

    private static CandidatePage candidatePage(
            long generation,
            long revision,
            List<String> candidateTexts,
            int pageIndex) {
        if (candidateTexts.isEmpty()) return null;
        int pageCount = (candidateTexts.size() + CANDIDATES_PER_PAGE - 1)
                / CANDIDATES_PER_PAGE;
        if (pageIndex < 0 || pageIndex >= pageCount) return null;
        int start = pageIndex * CANDIDATES_PER_PAGE;
        int end = Math.min(start + CANDIDATES_PER_PAGE, candidateTexts.size());
        ArrayList<CandidatePage.Item> items = new ArrayList<>(end - start);
        for (int index = start; index < end; index++) {
            items.add(new CandidatePage.Item("c" + index, candidateTexts.get(index)));
        }
        return new CandidatePage(
                PRODUCER_ID, generation, revision, pageIndex, pageCount, items);
    }

    private static long incrementRevision(long current) {
        if (current == Long.MAX_VALUE) throw new IllegalStateException("revision exhausted");
        return current + 1L;
    }

    private static Rejected rejected(FailureKind failure) {
        return new Rejected(failure);
    }

    private ProcessResult completeNativeCommit(String text) throws Exception {
        String committed = RimeEngineSnapshot.requireBoundedText(text, false, "commit text");
        // Any native commit ends this exact composition, whether it came from an explicit
        // candidate selection or from schema-driven fixed-length auto selection. Destroy the
        // native session before copying the local recovery point. Full sync_user_data remains an
        // explicit maintenance operation and never blocks every word on the keyboard hot path.
        Session committedSession = Objects.requireNonNull(session, "active Rime session");
        committedSession.close();
        session = null;
        UserDataLease committedUserData = Objects.requireNonNull(
                userDataLease, "active Rime UserDB lease");
        committedUserData.checkpoint();
        userDataLease = null;
        committedUserData.close();
        long nextRevision = incrementRevision(state.revision());
        asciiInput.setLength(0);
        activeCandidates = List.of();
        activePageIndex = 0;
        state = RimeEngineSnapshot.active(
                state.editorGeneration(), state.coordinationGeneration(),
                nextRevision, "", null);
        return new CommitReady(new Commit(
                state.editorGeneration(), state.coordinationGeneration(),
                nextRevision, committed), state);
    }

    private Session openSession(UserDataLease lease) throws Exception {
        File shared = new File(runtimeRoot, "shared");
        File user = lease.directory();
        if (deploymentId == null) {
            return sessionFactory.open(shared, user, runtimeConfig.schemaId());
        }
        File marker = deploymentMarker(user);
        if (deploymentMatches(marker, deploymentId)) {
            try {
                return preparedSessionFactory.open(shared, user, runtimeConfig.schemaId());
            } catch (Exception | LinkageError staleCache) {
                Files.deleteIfExists(marker.toPath());
            }
        }
        Session deployed = sessionFactory.open(shared, user, runtimeConfig.schemaId());
        try {
            writeDeploymentMarker(marker, deploymentId);
            return deployed;
        } catch (Exception failure) {
            deployed.close();
            throw failure;
        }
    }

    private static Session openNativeSession(
            File sharedDirectory, File userDirectory, String schemaId) throws Exception {
        RimeAdapter adapter = RimeAdapter.open(sharedDirectory, userDirectory, schemaId);
        return adapterSession(adapter);
    }

    private static Session openPreparedNativeSession(
            File sharedDirectory, File userDirectory, String schemaId) throws Exception {
        RimeAdapter adapter = RimeAdapter.openDeployed(
                sharedDirectory, userDirectory, schemaId);
        return adapterSession(adapter);
    }

    private static Session adapterSession(RimeAdapter adapter) {
        return new Session() {
            @Override
            public void setOption(String optionName, boolean enabled) {
                adapter.setOption(optionName, enabled);
            }

            @Override
            public NativeSnapshot processAscii(String input) {
                RimeAdapter.Snapshot snapshot = adapter.processAscii(input);
                return new NativeSnapshot(snapshot.preedit(), snapshot.candidates());
            }

            @Override
            public String takePendingCommit() {
                return adapter.takePendingCommit();
            }

            @Override
            public NativeSnapshot resetComposition() {
                RimeAdapter.Snapshot snapshot = adapter.resetComposition();
                return new NativeSnapshot(snapshot.preedit(), snapshot.candidates());
            }

            @Override
            public String selectCandidate(int index) {
                return adapter.selectCandidate(index);
            }

            @Override
            public void synchronizeUserData() {
                adapter.synchronizeUserData();
            }

            @Override
            public void close() {
                adapter.close();
            }
        };
    }

    private static String requireDeploymentId(String value) {
        String safe = Objects.requireNonNull(value, "deploymentId");
        if (safe.length() != 64) {
            throw new IllegalArgumentException("deploymentId must be SHA-256");
        }
        for (int index = 0; index < safe.length(); index++) {
            char character = safe.charAt(index);
            if (!((character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f'))) {
                throw new IllegalArgumentException("deploymentId must be SHA-256");
            }
        }
        return safe;
    }

    private static File deploymentMarker(File userDirectory) throws IOException {
        File root = userDirectory.getCanonicalFile();
        File marker = new File(root, DEPLOYMENT_MARKER).getCanonicalFile();
        if (!root.equals(marker.getParentFile())) {
            throw new IOException("Rime deployment marker escaped UserDB root");
        }
        return marker;
    }

    private static boolean deploymentMatches(File marker, String expected) throws IOException {
        if (!marker.isFile() || marker.length() != 65L) return false;
        byte[] bytes = Files.readAllBytes(marker.toPath());
        return bytes.length == 65
                && bytes[64] == '\n'
                && expected.equals(new String(bytes, 0, 64, StandardCharsets.US_ASCII));
    }

    private static void writeDeploymentMarker(File marker, String deploymentId)
            throws IOException {
        File temporary = new File(marker.getParentFile(), DEPLOYMENT_MARKER + ".new");
        Files.deleteIfExists(temporary.toPath());
        byte[] bytes = (deploymentId + "\n").getBytes(StandardCharsets.US_ASCII);
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(bytes);
            output.getFD().sync();
        }
        try {
            Files.move(
                    temporary.toPath(), marker.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(
                    temporary.toPath(), marker.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }

    private static UserDataLeaseFactory legacyUserData(File runtimeRoot) {
        Objects.requireNonNull(runtimeRoot, "runtimeRoot");
        return () -> new UserDataLease() {
            private boolean closed;
            @Override public File directory() {
                if (closed) throw new IllegalStateException("UserDB lease closed");
                return new File(runtimeRoot, "user");
            }
            @Override public void checkpoint() {}
            @Override public boolean restoreLatestCheckpoint() { return false; }
            @Override public void close() { closed = true; }
        };
    }

    private static UserDataLeaseFactory persistentUserData(RimeUserDataStore store) {
        Objects.requireNonNull(store, "userDataStore");
        return () -> {
            RimeUserDataStore.Session session = store.openSession();
            return new UserDataLease() {
                @Override public File directory() { return session.userDirectory(); }
                @Override public void checkpoint() throws RimeUserDataException {
                    session.checkpoint();
                }
                @Override public boolean restoreLatestCheckpoint() throws RimeUserDataException {
                    try {
                        session.restoreLatestCheckpoint();
                        return true;
                    } catch (RimeUserDataException failure) {
                        if (failure.code() == RimeUserDataException.Code.NO_CHECKPOINT) return false;
                        throw failure;
                    }
                }
                @Override public void close() { session.close(); }
            };
        };
    }
}
