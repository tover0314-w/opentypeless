package com.opentypeless.android.keyboard.rime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.keyboard.candidate.CandidatePage;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Test;

public final class RimeInputEngineContractTest {
    @Test
    public void interfaceExposesOnlyTheBoundedAdapterLifecycle() {
        Set<String> methods = List.of(RimeInputEngine.class.getDeclaredMethods()).stream()
                .map(Method::getName)
                .collect(Collectors.toSet());
        assertEquals(Set.of(
                "activate",
                "deactivate",
                "process",
                "selectCandidate",
                "requestCandidatePage",
                "snapshot",
                "close"), methods);
        List<String> surfaceTypes = List.of(RimeInputEngine.class.getDeclaredMethods()).stream()
                .flatMap(method -> {
                    ArrayList<Class<?>> types = new ArrayList<>(
                            Arrays.asList(method.getParameterTypes()));
                    types.add(method.getReturnType());
                    return types.stream();
                })
                .map(Class::getName)
                .toList();
        assertFalse(surfaceTypes.stream().anyMatch(name -> name.startsWith("android.")));
        assertFalse(surfaceTypes.stream().anyMatch(name -> name.contains("InputConnection")));
        assertFalse(surfaceTypes.stream().anyMatch(name -> name.contains("RimeAdapter")));
    }

    @Test
    public void deterministicFakeExercisesActivateProcessSnapshotCandidateAndDeactivate() {
        DeterministicEngine engine = new DeterministicEngine();
        RimeInputEngine.LifecycleResult activation = engine.activate(
                new RimeInputEngine.Activation(7L, 11L, RimeInputEngine.LearningMode.DISABLED));
        assertType(RimeInputEngine.LifecycleApplied.class, activation);

        RimeInputEngine.StateReady first = assertType(
                RimeInputEngine.StateReady.class,
                engine.process(new RimeInputEngine.ProcessRequest(
                        7L, 11L, RimeInputEngine.Key.printable('n'))));
        assertEquals("n", first.snapshot().preedit());

        RimeInputEngine.StateReady second = assertType(
                RimeInputEngine.StateReady.class,
                engine.process(new RimeInputEngine.ProcessRequest(
                        7L, 11L, RimeInputEngine.Key.printable('i'))));
        CandidatePage page = second.snapshot().candidatePage().orElseThrow();
        RimeInputEngine.CommitReady committed = assertType(
                RimeInputEngine.CommitReady.class,
                engine.selectCandidate(new RimeInputEngine.CandidateSelectionRequest(
                        7L, page.selection(0))));
        assertEquals("甲", committed.commit().text());
        assertFalse(committed.snapshot().hasComposition());
        assertType(RimeInputEngine.SnapshotReady.class, engine.snapshot());

        assertType(
                RimeInputEngine.LifecycleApplied.class,
                engine.deactivate(new RimeInputEngine.Deactivation(
                        7L, 11L, RimeInputEngine.DeactivationReason.TARGET_FINISHED)));
        assertEquals(
                RimeEngineSnapshot.Phase.INACTIVE,
                assertType(RimeInputEngine.SnapshotReady.class, engine.snapshot())
                        .snapshot().phase());
    }

    @Test
    public void lifecycleAndRequestsRequirePositiveGenerationAndRimeOwnership() {
        assertThrows(IllegalArgumentException.class, () -> new RimeInputEngine.Activation(
                0L, 1L, RimeInputEngine.LearningMode.DISABLED));
        assertThrows(IllegalArgumentException.class, () -> new RimeInputEngine.ProcessRequest(
                1L, -1L, RimeInputEngine.Key.backspace()));
        CandidatePage latin = page("latin", 3L, 4L, "one");
        assertThrows(IllegalArgumentException.class, () ->
                new RimeInputEngine.CandidateSelectionRequest(1L, latin.selection(0)));
        assertThrows(IllegalArgumentException.class, () ->
                new RimeInputEngine.CandidatePageRequest(
                        1L, new CandidatePage("latin", 3L, 4L, 0, 2,
                                List.of(new CandidatePage.Item("c0", "one")))
                                .pageRequest(CandidatePage.Direction.NEXT)));
    }

    @Test
    public void keysAcceptUnicodeScalarsAndRejectControlsSurrogatesAndTextOnCommands() {
        assertEquals(0x1f642, RimeInputEngine.Key.printable(0x1f642).codePoint());
        assertThrows(IllegalArgumentException.class,
                () -> RimeInputEngine.Key.printable('\n'));
        assertThrows(IllegalArgumentException.class,
                () -> RimeInputEngine.Key.printable(0xd800));
        assertThrows(IllegalArgumentException.class,
                () -> RimeInputEngine.Key.printable(0x202e));
        assertThrows(IllegalArgumentException.class,
                () -> new RimeInputEngine.Key(RimeInputEngine.KeyKind.BACKSPACE, 'x'));
    }

    @Test
    public void snapshotRequiresCandidateGenerationRevisionAndProducerIdentity() {
        CandidatePage valid = page("rime", 11L, 13L, "甲");
        assertTrue(RimeEngineSnapshot.active(7L, 11L, 13L, "ni", valid).hasComposition());
        assertThrows(IllegalArgumentException.class,
                () -> RimeEngineSnapshot.active(7L, 12L, 13L, "ni", valid));
        assertThrows(IllegalArgumentException.class,
                () -> RimeEngineSnapshot.active(7L, 11L, 14L, "ni", valid));
        assertThrows(IllegalArgumentException.class,
                () -> RimeEngineSnapshot.active(
                        7L, 11L, 13L, "ni", page("latin", 11L, 13L, "one")));
    }

    @Test
    public void inactiveAndBoundedTextStatesFailClosed() {
        assertEquals(RimeEngineSnapshot.Phase.INACTIVE, RimeEngineSnapshot.inactive().phase());
        assertThrows(IllegalArgumentException.class,
                () -> RimeEngineSnapshot.active(1L, 1L, 1L, "x".repeat(513), null));
        assertThrows(IllegalArgumentException.class,
                () -> RimeEngineSnapshot.active(1L, 1L, 1L, "line\nbreak", null));
        assertThrows(IllegalArgumentException.class,
                () -> new RimeInputEngine.Commit(1L, 1L, 1L, ""));
    }

    @Test
    public void commitReadyRequiresOneMatchingGenerationAndRevision() {
        RimeInputEngine.Commit commit = new RimeInputEngine.Commit(3L, 5L, 8L, "甲");
        RimeEngineSnapshot valid = RimeEngineSnapshot.active(3L, 5L, 8L, "", null);
        assertType(RimeInputEngine.CommitReady.class,
                new RimeInputEngine.CommitReady(commit, valid));
        assertThrows(IllegalArgumentException.class, () -> new RimeInputEngine.CommitReady(
                commit, RimeEngineSnapshot.active(3L, 5L, 9L, "", null)));
    }

    @Test
    public void diagnosticsRedactPreeditKeyCandidateAndCommitText() {
        CandidatePage page = page("rime", 2L, 3L, "private-candidate");
        String diagnostics = RimeEngineSnapshot.active(
                1L, 2L, 3L, "private-preedit", page).toString()
                + RimeInputEngine.Key.printable('q')
                + new RimeInputEngine.Commit(1L, 2L, 3L, "private-commit")
                + page.selection(0);
        assertFalse(diagnostics.contains("private-preedit"));
        assertFalse(diagnostics.contains("private-candidate"));
        assertFalse(diagnostics.contains("private-commit"));
        assertFalse(diagnostics.contains("113"));
        assertTrue(diagnostics.contains("<redacted>"));
    }

    private static CandidatePage page(
            String producer, long generation, long revision, String... values) {
        List<CandidatePage.Item> items = new ArrayList<>();
        for (int index = 0; index < values.length; index++) {
            items.add(new CandidatePage.Item("c" + index, values[index]));
        }
        return new CandidatePage(producer, generation, revision, 0, 1, items);
    }

    private static <T> T assertType(Class<T> type, Object value) {
        assertTrue("expected " + type.getSimpleName(), type.isInstance(value));
        return type.cast(value);
    }

    private static final class DeterministicEngine implements RimeInputEngine {
        private RimeEngineSnapshot state = RimeEngineSnapshot.inactive();
        private boolean closed;

        @Override
        public LifecycleResult activate(Activation request) {
            if (closed) return new Rejected(FailureKind.CLOSED);
            if (state.phase() == RimeEngineSnapshot.Phase.ACTIVE) {
                return new Rejected(FailureKind.ALREADY_ACTIVE);
            }
            state = RimeEngineSnapshot.active(
                    request.editorGeneration(), request.coordinationGeneration(), 1L, "", null);
            return new LifecycleApplied(state);
        }

        @Override
        public LifecycleResult deactivate(Deactivation request) {
            if (!matches(request.editorGeneration(), request.coordinationGeneration())) {
                return new Rejected(FailureKind.STALE_COORDINATION_GENERATION);
            }
            state = RimeEngineSnapshot.inactive();
            return new LifecycleApplied(state);
        }

        @Override
        public ProcessResult process(ProcessRequest request) {
            if (!matches(request.editorGeneration(), request.coordinationGeneration())) {
                return new Rejected(FailureKind.STALE_EDITOR_GENERATION);
            }
            long revision = state.revision() + 1L;
            String next = state.preedit()
                    + new String(Character.toChars(request.key().codePoint()));
            CandidatePage candidates = next.equals("ni")
                    ? page("rime", request.coordinationGeneration(), revision, "甲", "乙")
                    : null;
            state = RimeEngineSnapshot.active(
                    request.editorGeneration(), request.coordinationGeneration(), revision,
                    next, candidates);
            return new StateReady(state);
        }

        @Override
        public ProcessResult selectCandidate(CandidateSelectionRequest request) {
            CandidatePage active = state.candidatePage().orElse(null);
            CandidatePage.Selection selected = request.selection();
            if (active == null
                    || request.editorGeneration() != state.editorGeneration()
                    || selected.generation() != state.coordinationGeneration()
                    || selected.pageRevision() != state.revision()
                    || !active.selection(selected.candidateIndex()).equals(selected)) {
                return new Rejected(FailureKind.STALE_COORDINATION_GENERATION);
            }
            long revision = state.revision() + 1L;
            state = RimeEngineSnapshot.active(
                    state.editorGeneration(), state.coordinationGeneration(), revision, "", null);
            return new CommitReady(new Commit(
                    state.editorGeneration(), state.coordinationGeneration(), revision,
                    selected.expectedText()), state);
        }

        @Override
        public ProcessResult requestCandidatePage(CandidatePageRequest request) {
            return new Rejected(FailureKind.INVALID_OUTPUT);
        }

        @Override
        public SnapshotResult snapshot() {
            return closed
                    ? new Rejected(FailureKind.CLOSED)
                    : new SnapshotReady(state);
        }

        @Override
        public void close() {
            closed = true;
            state = RimeEngineSnapshot.inactive();
        }

        private boolean matches(long editorGeneration, long coordinationGeneration) {
            return !closed
                    && state.phase() == RimeEngineSnapshot.Phase.ACTIVE
                    && state.editorGeneration() == editorGeneration
                    && state.coordinationGeneration() == coordinationGeneration;
        }
    }
}
