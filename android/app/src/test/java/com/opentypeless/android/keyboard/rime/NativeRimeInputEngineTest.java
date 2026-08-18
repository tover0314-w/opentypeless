package com.opentypeless.android.keyboard.rime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.keyboard.candidate.CandidatePage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class NativeRimeInputEngineTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void asciiAndBackspaceProduceMonotonicBoundedPreedit() {
        ArrayList<String> calls = new ArrayList<>();
        NativeRimeInputEngine engine = engine(calls);
        assertTrue(engine.activate(new RimeInputEngine.Activation(
                3L, 5L, RimeInputEngine.LearningMode.ENABLED))
                instanceof RimeInputEngine.LifecycleApplied);

        RimeInputEngine.StateReady n = state(engine.process(request('n')));
        assertEquals("n", n.snapshot().preedit());
        RimeInputEngine.StateReady ni = state(engine.process(request('i')));
        assertEquals("ni", ni.snapshot().preedit());
        assertEquals("甲", ni.snapshot().candidatePage().orElseThrow().items().get(0).text());
        RimeInputEngine.StateReady back = state(engine.process(new RimeInputEngine.ProcessRequest(
                3L, 5L, RimeInputEngine.Key.backspace())));
        assertEquals("n", back.snapshot().preedit());
        assertEquals(List.of("n", "ni", "n"), calls);
        assertTrue(back.snapshot().revision() > ni.snapshot().revision());
    }

    @Test
    public void activationContinuesTheReservedEditorRevisionSpace() {
        NativeRimeInputEngine engine = engine(new ArrayList<>());
        assertTrue(engine.activate(new RimeInputEngine.Activation(
                3L, 5L, 41L, RimeInputEngine.LearningMode.ENABLED))
                instanceof RimeInputEngine.LifecycleApplied);

        RimeInputEngine.StateReady first = state(engine.process(request('n')));
        assertEquals(42L, first.snapshot().revision());
    }

    @Test
    public void unsupportedUnicodeEnterAndUnboundCandidateFailClosed() {
        NativeRimeInputEngine disabled = engine(new ArrayList<>());
        assertRejected(disabled.activate(new RimeInputEngine.Activation(
                3L, 5L, RimeInputEngine.LearningMode.DISABLED)),
                RimeInputEngine.FailureKind.POLICY_DENIED);

        NativeRimeInputEngine engine = engine(new ArrayList<>());
        engine.activate(new RimeInputEngine.Activation(
                3L, 5L, RimeInputEngine.LearningMode.ENABLED));
        assertRejected(engine.process(new RimeInputEngine.ProcessRequest(
                3L, 5L, RimeInputEngine.Key.printable('中'))),
                RimeInputEngine.FailureKind.POLICY_DENIED);
        assertRejected(engine.process(new RimeInputEngine.ProcessRequest(
                3L, 5L, RimeInputEngine.Key.enter())),
                RimeInputEngine.FailureKind.POLICY_DENIED);
        assertRejected(engine.selectCandidate(nullSelection()),
                RimeInputEngine.FailureKind.STALE_COORDINATION_GENERATION);
        assertFalse(engine.snapshot() instanceof RimeInputEngine.Rejected);
    }

    @Test
    public void candidatePagesSelectExactAbsoluteIndexOnceAndRejectReplay() {
        ArrayList<String> calls = new ArrayList<>();
        ArrayList<Integer> selections = new ArrayList<>();
        List<String> candidates = new ArrayList<>();
        for (int index = 0; index < 12; index++) candidates.add("候" + index);
        NativeRimeInputEngine engine = pagingEngine(calls, selections, candidates);
        engine.activate(new RimeInputEngine.Activation(
                3L, 5L, RimeInputEngine.LearningMode.ENABLED));

        state(engine.process(request('n')));
        CandidatePage first = state(engine.process(request('i')))
                .snapshot().candidatePage().orElseThrow();
        assertEquals(0, first.pageIndex());
        assertEquals(3, first.pageCount());
        assertEquals(5, first.items().size());
        assertEquals("c0", first.items().get(0).id());

        CandidatePage second = state(engine.requestCandidatePage(
                new RimeInputEngine.CandidatePageRequest(
                        3L, first.pageRequest(CandidatePage.Direction.NEXT))))
                .snapshot().candidatePage().orElseThrow();
        assertEquals(1, second.pageIndex());
        assertEquals(3, second.pageCount());
        assertEquals("c5", second.items().get(0).id());
        CandidatePage.Selection selected = second.selection(1);

        RimeInputEngine.ProcessResult committed = engine.selectCandidate(
                new RimeInputEngine.CandidateSelectionRequest(3L, selected));
        assertTrue(committed instanceof RimeInputEngine.CommitReady);
        assertEquals("候6", ((RimeInputEngine.CommitReady) committed).commit().text());
        assertEquals(List.of(6), selections);

        assertRejected(engine.selectCandidate(
                new RimeInputEngine.CandidateSelectionRequest(3L, selected)),
                RimeInputEngine.FailureKind.INACTIVE);
        assertEquals(List.of(6), selections);
        assertEquals(List.of("n", "ni"), calls);
    }

    @Test
    public void staleCandidateIdentityNeverCallsNativeSelection() {
        ArrayList<Integer> selections = new ArrayList<>();
        NativeRimeInputEngine engine = pagingEngine(
                new ArrayList<>(), selections, List.of("甲", "乙"));
        engine.activate(new RimeInputEngine.Activation(
                3L, 5L, RimeInputEngine.LearningMode.ENABLED));
        state(engine.process(request('n')));
        CandidatePage page = state(engine.process(request('i')))
                .snapshot().candidatePage().orElseThrow();
        CandidatePage.Selection exact = page.selection(0);
        CandidatePage.Selection forged = new CandidatePage.Selection(
                exact.producerId(), exact.generation(), exact.pageRevision(),
                exact.pageIndex(), exact.candidateIndex(), exact.candidateId(), "伪造");

        assertRejected(engine.selectCandidate(
                new RimeInputEngine.CandidateSelectionRequest(3L, forged)),
                RimeInputEngine.FailureKind.STALE_COORDINATION_GENERATION);
        assertTrue(selections.isEmpty());
    }

    @Test
    public void staleGenerationAndCloseNeverCallNativeAgain() {
        ArrayList<String> calls = new ArrayList<>();
        NativeRimeInputEngine engine = engine(calls);
        engine.activate(new RimeInputEngine.Activation(
                3L, 5L, RimeInputEngine.LearningMode.ENABLED));
        assertRejected(engine.process(new RimeInputEngine.ProcessRequest(
                4L, 5L, RimeInputEngine.Key.printable('n'))),
                RimeInputEngine.FailureKind.STALE_EDITOR_GENERATION);
        engine.close();
        assertRejected(engine.process(request('n')), RimeInputEngine.FailureKind.CLOSED);
        assertTrue(calls.isEmpty());
    }

    @Test
    public void selectedSchemaAndClosedOptionsApplyBeforeFirstNativeKey() {
        LinkedHashMap<String, Boolean> options = new LinkedHashMap<>();
        ArrayList<String> events = new ArrayList<>();
        RimeRuntimeConfig configuration = new RimeRuntimeConfig(
                "alternate", false, false, true);
        NativeRimeInputEngine engine = new NativeRimeInputEngine(
                new File("runtime"), configuration, (shared, user, schema) -> {
                    events.add("open:" + schema);
                    return new NativeRimeInputEngine.Session() {
                        @Override public void setOption(String name, boolean enabled) {
                            options.put(name, enabled);
                            events.add("option:" + name);
                        }
                        @Override public NativeRimeInputEngine.NativeSnapshot processAscii(
                                String input) {
                            events.add("key:" + input);
                            return new NativeRimeInputEngine.NativeSnapshot(input, List.of());
                        }
                        @Override public NativeRimeInputEngine.NativeSnapshot resetComposition() {
                            return new NativeRimeInputEngine.NativeSnapshot("", List.of());
                        }
                        @Override public String selectCandidate(int index) { return "甲"; }
                        @Override public void synchronizeUserData() {}
                        @Override public void close() {}
                    };
                });

        assertTrue(engine.activate(new RimeInputEngine.Activation(
                3L, 5L, RimeInputEngine.LearningMode.ENABLED))
                instanceof RimeInputEngine.LifecycleApplied);
        state(engine.process(request('n')));

        assertEquals(List.of(
                "open:alternate",
                "option:simplification",
                "option:ascii_punct",
                "option:full_shape",
                "key:n"), events);
        assertEquals(Map.of(
                "simplification", false,
                "ascii_punct", false,
                "full_shape", true), options);
    }

    @Test
    public void exactCandidateClosesSessionBeforeCreatingRecoveryPoint() {
        ArrayList<String> events = new ArrayList<>();
        NativeRimeInputEngine engine = new NativeRimeInputEngine(
                new File("runtime"), RimeRuntimeConfig.defaults("local"),
                () -> lease(events, true, false),
                (shared, user, schema) -> session(events));
        assertTrue(engine.activate(new RimeInputEngine.Activation(
                3L, 5L, RimeInputEngine.LearningMode.ENABLED))
                instanceof RimeInputEngine.LifecycleApplied);
        state(engine.process(request('n')));
        CandidatePage page = state(engine.process(request('i')))
                .snapshot().candidatePage().orElseThrow();
        events.clear();

        RimeInputEngine.ProcessResult result = engine.selectCandidate(
                new RimeInputEngine.CandidateSelectionRequest(3L, page.selection(0)));

        assertTrue(result instanceof RimeInputEngine.CommitReady);
        assertEquals(List.of(
                "select:0", "session-close", "checkpoint", "lease-close"), events);
    }

    @Test
    public void fixedLengthNativeAutoCommitReturnsCommitAndCreatesRecoveryPoint() {
        ArrayList<String> events = new ArrayList<>();
        NativeRimeInputEngine engine = new NativeRimeInputEngine(
                new File("runtime"), RimeRuntimeConfig.defaults("local"),
                () -> lease(events, true, false),
                (shared, user, schema) -> new NativeRimeInputEngine.Session() {
                    private String pendingCommit;
                    @Override public void setOption(String optionName, boolean enabled) {}
                    @Override public NativeRimeInputEngine.NativeSnapshot processAscii(String input) {
                        if (input.length() == 4) pendingCommit = "合成提交";
                        return new NativeRimeInputEngine.NativeSnapshot(
                                pendingCommit == null ? input : "",
                                pendingCommit == null ? List.of("合成候选") : List.of());
                    }
                    @Override public String takePendingCommit() {
                        String commit = pendingCommit;
                        pendingCommit = null;
                        return commit;
                    }
                    @Override public NativeRimeInputEngine.NativeSnapshot resetComposition() {
                        return new NativeRimeInputEngine.NativeSnapshot("", List.of());
                    }
                    @Override public String selectCandidate(int index) {
                        throw new AssertionError("auto commit must not select a candidate");
                    }
                    @Override public void synchronizeUserData() {}
                    @Override public void close() { events.add("session-close"); }
                });
        assertTrue(engine.activate(new RimeInputEngine.Activation(
                3L, 5L, RimeInputEngine.LearningMode.ENABLED))
                instanceof RimeInputEngine.LifecycleApplied);
        state(engine.process(request('a')));
        state(engine.process(request('b')));
        state(engine.process(request('c')));

        RimeInputEngine.ProcessResult result = engine.process(request('d'));

        assertTrue(result instanceof RimeInputEngine.CommitReady);
        assertEquals("合成提交", ((RimeInputEngine.CommitReady) result).commit().text());
        assertEquals(List.of("session-close", "checkpoint", "lease-close"), events);
    }

    @Test
    public void failedOpenRestoresOneCheckpointThenRetriesExactlyOnce() {
        ArrayList<String> events = new ArrayList<>();
        int[] opens = {0};
        NativeRimeInputEngine engine = new NativeRimeInputEngine(
                new File("runtime"), RimeRuntimeConfig.defaults("local"),
                () -> lease(events, true, false),
                (shared, user, schema) -> {
                    events.add("open:" + (++opens[0]));
                    if (opens[0] == 1) throw new IOException("synthetic corrupt userdb");
                    return session(events);
                });

        assertTrue(engine.activate(new RimeInputEngine.Activation(
                3L, 5L, RimeInputEngine.LearningMode.ENABLED))
                instanceof RimeInputEngine.LifecycleApplied);
        assertEquals(2, opens[0]);
        assertEquals(1L, events.stream().filter("restore"::equals).count());
    }

    @Test
    public void missingRecoveryPointNeverLoopsAfterFailedOpen() {
        ArrayList<String> events = new ArrayList<>();
        int[] opens = {0};
        NativeRimeInputEngine engine = new NativeRimeInputEngine(
                new File("runtime"), RimeRuntimeConfig.defaults("local"),
                () -> lease(events, false, false),
                (shared, user, schema) -> {
                    opens[0]++;
                    throw new IOException("synthetic unavailable engine");
                });

        assertRejected(engine.activate(new RimeInputEngine.Activation(
                3L, 5L, RimeInputEngine.LearningMode.ENABLED)),
                RimeInputEngine.FailureKind.ENGINE_UNAVAILABLE);
        assertEquals(1, opens[0]);
        assertEquals(1L, events.stream().filter("restore"::equals).count());
        assertTrue(events.contains("lease-close"));
    }

    @Test
    public void checkpointFailureNeverReturnsACommit() {
        ArrayList<String> events = new ArrayList<>();
        NativeRimeInputEngine engine = new NativeRimeInputEngine(
                new File("runtime"), RimeRuntimeConfig.defaults("local"),
                () -> lease(events, true, true),
                (shared, user, schema) -> session(events));
        engine.activate(new RimeInputEngine.Activation(
                3L, 5L, RimeInputEngine.LearningMode.ENABLED));
        state(engine.process(request('n')));
        CandidatePage page = state(engine.process(request('i')))
                .snapshot().candidatePage().orElseThrow();

        assertRejected(engine.selectCandidate(
                new RimeInputEngine.CandidateSelectionRequest(3L, page.selection(0))),
                RimeInputEngine.FailureKind.ENGINE_FAILURE);
        assertTrue(events.contains("session-close"));
        assertTrue(events.contains("lease-close"));
    }

    @Test
    public void exactDeploymentMarkerSkipsMaintenanceAndResourceChangeRedeploys()
            throws Exception {
        File userDirectory = temporary.newFolder("deployment-marker");
        String firstId = "a".repeat(64);
        String secondId = "b".repeat(64);
        ArrayList<String> events = new ArrayList<>();

        NativeRimeInputEngine first = markerEngine(userDirectory, firstId, events, false);
        assertTrue(first.activate(new RimeInputEngine.Activation(
                3L, 5L, RimeInputEngine.LearningMode.ENABLED))
                instanceof RimeInputEngine.LifecycleApplied);
        first.close();

        NativeRimeInputEngine same = markerEngine(userDirectory, firstId, events, false);
        assertTrue(same.activate(new RimeInputEngine.Activation(
                3L, 5L, RimeInputEngine.LearningMode.ENABLED))
                instanceof RimeInputEngine.LifecycleApplied);
        same.close();

        NativeRimeInputEngine changed = markerEngine(userDirectory, secondId, events, false);
        assertTrue(changed.activate(new RimeInputEngine.Activation(
                3L, 5L, RimeInputEngine.LearningMode.ENABLED))
                instanceof RimeInputEngine.LifecycleApplied);
        changed.close();

        assertEquals(List.of("deploy", "prepared", "deploy"), events);
    }

    @Test
    public void corruptPreparedCacheFallsBackToOneFreshDeployment() throws Exception {
        File userDirectory = temporary.newFolder("prepared-fallback");
        String deploymentId = "c".repeat(64);
        ArrayList<String> events = new ArrayList<>();
        NativeRimeInputEngine initial = markerEngine(
                userDirectory, deploymentId, events, false);
        assertTrue(initial.activate(new RimeInputEngine.Activation(
                3L, 5L, RimeInputEngine.LearningMode.ENABLED))
                instanceof RimeInputEngine.LifecycleApplied);
        initial.close();

        NativeRimeInputEngine recovered = markerEngine(
                userDirectory, deploymentId, events, true);
        assertTrue(recovered.activate(new RimeInputEngine.Activation(
                3L, 5L, RimeInputEngine.LearningMode.ENABLED))
                instanceof RimeInputEngine.LifecycleApplied);
        recovered.close();

        assertEquals(List.of("deploy", "prepared-failed", "deploy"), events);
    }

    private static NativeRimeInputEngine.UserDataLease lease(
            List<String> events, boolean restoreAvailable, boolean failCheckpoint) {
        return new NativeRimeInputEngine.UserDataLease() {
            @Override public File directory() { return new File("userdata"); }
            @Override public void checkpoint() throws Exception {
                events.add("checkpoint");
                if (failCheckpoint) throw new IOException("synthetic checkpoint failure");
            }
            @Override public boolean restoreLatestCheckpoint() {
                events.add("restore");
                return restoreAvailable;
            }
            @Override public void close() { events.add("lease-close"); }
        };
    }

    private static NativeRimeInputEngine markerEngine(
            File userDirectory,
            String deploymentId,
            List<String> events,
            boolean failPrepared) {
        return new NativeRimeInputEngine(
                new File("runtime"),
                RimeRuntimeConfig.defaults("local"),
                () -> new NativeRimeInputEngine.UserDataLease() {
                    @Override public File directory() { return userDirectory; }
                    @Override public void checkpoint() {}
                    @Override public boolean restoreLatestCheckpoint() { return false; }
                    @Override public void close() {}
                },
                (shared, user, schema) -> {
                    events.add("deploy");
                    return session(new ArrayList<>());
                },
                (shared, user, schema) -> {
                    events.add(failPrepared ? "prepared-failed" : "prepared");
                    if (failPrepared) throw new IOException("synthetic stale deployment");
                    return session(new ArrayList<>());
                },
                deploymentId);
    }

    private static NativeRimeInputEngine.Session session(List<String> events) {
        return new NativeRimeInputEngine.Session() {
            @Override public void setOption(String optionName, boolean enabled) {}
            @Override public NativeRimeInputEngine.NativeSnapshot processAscii(String input) {
                return new NativeRimeInputEngine.NativeSnapshot(
                        input, input.equals("ni") ? List.of("甲", "乙") : List.of());
            }
            @Override public NativeRimeInputEngine.NativeSnapshot resetComposition() {
                return new NativeRimeInputEngine.NativeSnapshot("", List.of());
            }
            @Override public String selectCandidate(int index) {
                events.add("select:" + index);
                return index == 0 ? "甲" : "乙";
            }
            @Override public void synchronizeUserData() { events.add("sync"); }
            @Override public void close() { events.add("session-close"); }
        };
    }

    private static NativeRimeInputEngine engine(List<String> calls) {
        return new NativeRimeInputEngine(
                new File("runtime"), RimeRuntimeConfig.defaults("local"),
                (shared, user, schema) ->
                new NativeRimeInputEngine.Session() {
                    @Override
                    public void setOption(String optionName, boolean enabled) {}

                    @Override
                    public NativeRimeInputEngine.NativeSnapshot processAscii(String input) {
                        calls.add(input);
                        return new NativeRimeInputEngine.NativeSnapshot(
                                input, input.equals("ni") ? List.of("甲", "乙") : List.of());
                    }

                    @Override
                    public NativeRimeInputEngine.NativeSnapshot resetComposition() {
                        return new NativeRimeInputEngine.NativeSnapshot("", List.of());
                    }

                    @Override
                    public String selectCandidate(int index) {
                        return "甲";
                    }

                    @Override public void synchronizeUserData() {}

                    @Override public void close() {}
                });
    }

    private static NativeRimeInputEngine pagingEngine(
            List<String> calls,
            List<Integer> selections,
            List<String> candidates) {
        return new NativeRimeInputEngine(
                new File("runtime"), RimeRuntimeConfig.defaults("local"),
                (shared, user, schema) ->
                new NativeRimeInputEngine.Session() {
                    @Override
                    public void setOption(String optionName, boolean enabled) {}

                    @Override
                    public NativeRimeInputEngine.NativeSnapshot processAscii(String input) {
                        calls.add(input);
                        return new NativeRimeInputEngine.NativeSnapshot(
                                input, input.equals("ni") ? candidates : List.of());
                    }

                    @Override
                    public NativeRimeInputEngine.NativeSnapshot resetComposition() {
                        return new NativeRimeInputEngine.NativeSnapshot("", List.of());
                    }

                    @Override
                    public String selectCandidate(int index) {
                        selections.add(index);
                        return candidates.get(index);
                    }

                    @Override public void synchronizeUserData() {}

                    @Override public void close() {}
                });
    }

    private static RimeInputEngine.ProcessRequest request(char value) {
        return new RimeInputEngine.ProcessRequest(
                3L, 5L, RimeInputEngine.Key.printable(value));
    }

    private static RimeInputEngine.StateReady state(RimeInputEngine.ProcessResult result) {
        assertTrue(result instanceof RimeInputEngine.StateReady);
        return (RimeInputEngine.StateReady) result;
    }

    private static void assertRejected(Object result, RimeInputEngine.FailureKind expected) {
        assertTrue(result instanceof RimeInputEngine.Rejected);
        assertEquals(expected, ((RimeInputEngine.Rejected) result).failure());
    }

    private static RimeInputEngine.CandidateSelectionRequest nullSelection() {
        CandidatePage page =
                new CandidatePage(
                        "rime", 5L, 1L, 0, 1,
                        List.of(new CandidatePage.Item(
                                "c0", "甲")));
        return new RimeInputEngine.CandidateSelectionRequest(3L, page.selection(0));
    }
}
