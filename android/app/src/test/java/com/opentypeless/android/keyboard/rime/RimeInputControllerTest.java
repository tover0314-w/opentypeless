package com.opentypeless.android.keyboard.rime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.keyboard.candidate.CandidatePage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class RimeInputControllerTest {
    @Test
    public void workerPreservesOrderAndDeliversExactIdentity() throws Exception {
        ArrayList<String> events = new ArrayList<>();
        CountDownLatch delivered = new CountDownLatch(2);
        RimeInputController controller = new RimeInputController(
                7L, 11L, RecordingEngine::new, Runnable::run,
                (editor, coordination, result) -> {
                    synchronized (events) {
                        events.add(editor + ":" + coordination + ":" +
                                ((RimeInputEngine.StateReady) result).snapshot().preedit());
                    }
                    delivered.countDown();
                });
        assertEquals(RimeInputController.EnqueueResult.QUEUED,
                controller.process(RimeInputEngine.Key.printable('n')));
        assertEquals(RimeInputController.EnqueueResult.QUEUED,
                controller.process(RimeInputEngine.Key.printable('i')));
        assertTrue(delivered.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("7:11:n", "7:11:ni"), events);
        controller.close();
    }

    @Test
    public void workerForwardsTheReservedInitialRevision() throws Exception {
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<RimeInputEngine.ProcessResult> latest = new AtomicReference<>();
        RimeInputController controller = new RimeInputController(
                7L, 11L, 41L, RecordingEngine::new, Runnable::run,
                (editor, coordination, result) -> {
                    latest.set(result);
                    delivered.countDown();
                });

        assertEquals(RimeInputController.EnqueueResult.QUEUED,
                controller.process(RimeInputEngine.Key.printable('n')));
        assertTrue(delivered.await(5, TimeUnit.SECONDS));
        assertEquals(42L, ((RimeInputEngine.StateReady) latest.get())
                .snapshot().revision());
        controller.close();
    }

    @Test
    public void warmUpActivatesOnceBeforeFirstKeyWithoutPublishingState() throws Exception {
        CountDownLatch activated = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicInteger factories = new AtomicInteger();
        AtomicInteger activations = new AtomicInteger();
        AtomicInteger callbacks = new AtomicInteger();
        RimeInputController controller = new RimeInputController(
                7L, 11L, () -> {
                    factories.incrementAndGet();
                    return new RecordingEngine() {
                        @Override public LifecycleResult activate(Activation request) {
                            activations.incrementAndGet();
                            LifecycleResult result = super.activate(request);
                            activated.countDown();
                            return result;
                        }
                    };
                }, Runnable::run,
                (editor, coordination, result) -> {
                    callbacks.incrementAndGet();
                    delivered.countDown();
                });

        assertEquals(RimeInputController.EnqueueResult.QUEUED, controller.warmUp());
        assertTrue(activated.await(5, TimeUnit.SECONDS));
        assertEquals(0, callbacks.get());
        assertEquals(RimeInputController.EnqueueResult.QUEUED,
                controller.process(RimeInputEngine.Key.printable('n')));
        assertTrue(delivered.await(5, TimeUnit.SECONDS));
        assertEquals(1, factories.get());
        assertEquals(1, activations.get());
        assertEquals(1, callbacks.get());
        controller.close();
    }

    @Test
    public void boundedQueueRejectsFloodAndCloseSuppressesLateCallback() throws Exception {
        CountDownLatch workerEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        RimeInputController controller = new RimeInputController(
                1L, 2L, () -> new BlockingEngine(workerEntered, release), Runnable::run,
                (editor, coordination, result) -> callbacks.incrementAndGet());
        controller.process(RimeInputEngine.Key.printable('a'));
        assertTrue(workerEntered.await(5, TimeUnit.SECONDS));
        boolean backpressure = false;
        for (int index = 0; index < 32; index++) {
            if (controller.process(RimeInputEngine.Key.printable('b'))
                    == RimeInputController.EnqueueResult.BACKPRESSURE) {
                backpressure = true;
                break;
            }
        }
        assertTrue(backpressure);
        controller.close();
        release.countDown();
        assertEquals(RimeInputController.EnqueueResult.CLOSED,
                controller.process(RimeInputEngine.Key.printable('c')));
        assertEquals(0, callbacks.get());
    }

    @Test
    public void candidatePagingAndSelectionUseSameOrderedWorkerAndExactIdentity()
            throws Exception {
        CountDownLatch delivered = new CountDownLatch(3);
        AtomicReference<RimeInputEngine.ProcessResult> latest = new AtomicReference<>();
        PagingEngine engine = new PagingEngine();
        RimeInputController controller = new RimeInputController(
                7L, 11L, () -> engine, Runnable::run,
                (editor, coordination, result) -> {
                    assertEquals(7L, editor);
                    assertEquals(11L, coordination);
                    latest.set(result);
                    delivered.countDown();
                });

        assertEquals(RimeInputController.EnqueueResult.QUEUED,
                controller.process(RimeInputEngine.Key.printable('n')));
        assertTrue(waitForCount(delivered, 2L));
        CandidatePage first = ((RimeInputEngine.StateReady) latest.get())
                .snapshot().candidatePage().orElseThrow();
        assertEquals(RimeInputController.EnqueueResult.QUEUED,
                controller.requestCandidatePage(
                        first.pageRequest(CandidatePage.Direction.NEXT)));
        assertTrue(waitForCount(delivered, 1L));
        CandidatePage second = ((RimeInputEngine.StateReady) latest.get())
                .snapshot().candidatePage().orElseThrow();
        assertEquals(RimeInputController.EnqueueResult.QUEUED,
                controller.selectCandidate(second.selection(0)));
        assertTrue(delivered.await(5, TimeUnit.SECONDS));

        assertTrue(latest.get() instanceof RimeInputEngine.CommitReady);
        assertEquals("丙", ((RimeInputEngine.CommitReady) latest.get()).commit().text());
        assertEquals(1, engine.pageRequests.get());
        assertEquals(1, engine.selections.get());
        controller.close();
    }

    private static boolean waitForCount(CountDownLatch latch, long expectedRemaining)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (latch.getCount() > expectedRemaining && System.nanoTime() < deadline) {
            Thread.yield();
        }
        return latch.getCount() == expectedRemaining;
    }

    private static class RecordingEngine implements RimeInputEngine {
        private long editor;
        private long coordination;
        private long revision;
        private String text = "";

        @Override public LifecycleResult activate(Activation request) {
            editor = request.editorGeneration();
            coordination = request.coordinationGeneration();
            revision = request.initialRevision();
            return new LifecycleApplied(snapshotValue());
        }
        @Override public LifecycleResult deactivate(Deactivation request) {
            return new LifecycleApplied(RimeEngineSnapshot.inactive());
        }
        @Override public ProcessResult process(ProcessRequest request) {
            text += new String(Character.toChars(request.key().codePoint()));
            revision++;
            return new StateReady(snapshotValue());
        }
        @Override public ProcessResult selectCandidate(CandidateSelectionRequest request) {
            return new Rejected(FailureKind.POLICY_DENIED);
        }
        @Override public ProcessResult requestCandidatePage(CandidatePageRequest request) {
            return new Rejected(FailureKind.POLICY_DENIED);
        }
        @Override public SnapshotResult snapshot() { return new SnapshotReady(snapshotValue()); }
        @Override public void close() {}
        private RimeEngineSnapshot snapshotValue() {
            return revision == 0L ? RimeEngineSnapshot.inactive()
                    : RimeEngineSnapshot.active(editor, coordination, revision, text, null);
        }
    }

    private static final class BlockingEngine extends RecordingEngine {
        private final CountDownLatch entered;
        private final CountDownLatch release;
        private BlockingEngine(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }
        @Override public ProcessResult process(ProcessRequest request) {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    return new Rejected(FailureKind.ENGINE_FAILURE);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return new Rejected(FailureKind.ENGINE_FAILURE);
            }
            return super.process(request);
        }
    }

    private static final class PagingEngine implements RimeInputEngine {
        private final AtomicInteger pageRequests = new AtomicInteger();
        private final AtomicInteger selections = new AtomicInteger();
        private long revision;
        private CandidatePage page;

        @Override public LifecycleResult activate(Activation request) {
            revision = 1L;
            return new LifecycleApplied(snapshot(request.editorGeneration(),
                    request.coordinationGeneration(), "", null));
        }

        @Override public LifecycleResult deactivate(Deactivation request) {
            return new LifecycleApplied(RimeEngineSnapshot.inactive());
        }

        @Override public ProcessResult process(ProcessRequest request) {
            revision++;
            page = new CandidatePage(PRODUCER_ID, request.coordinationGeneration(), revision,
                    0, 2, List.of(
                            new CandidatePage.Item("c0", "甲"),
                            new CandidatePage.Item("c1", "乙")));
            return new StateReady(snapshot(request.editorGeneration(),
                    request.coordinationGeneration(), "n", page));
        }

        @Override public ProcessResult selectCandidate(CandidateSelectionRequest request) {
            assertEquals(page.selection(0), request.selection());
            selections.incrementAndGet();
            revision++;
            Commit commit = new Commit(request.editorGeneration(),
                    request.selection().generation(), revision, "丙");
            return new CommitReady(commit, snapshot(request.editorGeneration(),
                    request.selection().generation(), "", null));
        }

        @Override public ProcessResult requestCandidatePage(CandidatePageRequest request) {
            assertEquals(page.pageRequest(CandidatePage.Direction.NEXT), request.request());
            pageRequests.incrementAndGet();
            revision++;
            page = new CandidatePage(PRODUCER_ID, request.request().generation(), revision,
                    1, 2, List.of(new CandidatePage.Item("c2", "丙")));
            return new StateReady(snapshot(request.editorGeneration(),
                    request.request().generation(), "n", page));
        }

        @Override public SnapshotResult snapshot() {
            return new SnapshotReady(snapshot(7L, 11L, "n", page));
        }

        @Override public void close() {}

        private RimeEngineSnapshot snapshot(
                long editor, long coordination, String preedit, CandidatePage candidates) {
            return RimeEngineSnapshot.active(
                    editor, coordination, revision, preedit, candidates);
        }
    }
}
