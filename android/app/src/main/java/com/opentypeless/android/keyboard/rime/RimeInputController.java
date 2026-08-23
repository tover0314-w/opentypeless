package com.opentypeless.android.keyboard.rime;

import com.opentypeless.android.keyboard.candidate.CandidatePage;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** One-session bounded worker that keeps all native Rime work off the IME main thread. */
public final class RimeInputController implements AutoCloseable {
    public enum EnqueueResult {
        QUEUED,
        BACKPRESSURE,
        CLOSED
    }

    @FunctionalInterface
    public interface Listener {
        void onResult(long editorGeneration, long coordinationGeneration,
                RimeInputEngine.ProcessResult result);
    }

    private static final int MAXIMUM_PENDING_COMMANDS = 8;

    private final long editorGeneration;
    private final long coordinationGeneration;
    private final long initialRevision;
    private final Supplier<RimeInputEngine> engineFactory;
    private final Executor callbackExecutor;
    private final Listener listener;
    private final ThreadPoolExecutor worker;
    private final AtomicBoolean closed = new AtomicBoolean();

    private RimeInputEngine engine;
    private boolean activated;
    private RimeInputEngine.Rejected activationFailure;

    public RimeInputController(
            long editorGeneration,
            long coordinationGeneration,
            Supplier<RimeInputEngine> engineFactory,
            Executor callbackExecutor,
            Listener listener) {
        this(
                editorGeneration,
                coordinationGeneration,
                1L,
                engineFactory,
                callbackExecutor,
                listener);
    }

    public RimeInputController(
            long editorGeneration,
            long coordinationGeneration,
            long initialRevision,
            Supplier<RimeInputEngine> engineFactory,
            Executor callbackExecutor,
            Listener listener) {
        RimeEngineSnapshot.requirePositive(editorGeneration, "editorGeneration");
        RimeEngineSnapshot.requirePositive(coordinationGeneration, "coordinationGeneration");
        RimeEngineSnapshot.requirePositive(initialRevision, "initialRevision");
        this.editorGeneration = editorGeneration;
        this.coordinationGeneration = coordinationGeneration;
        this.initialRevision = initialRevision;
        this.engineFactory = Objects.requireNonNull(engineFactory, "engineFactory");
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
        this.listener = Objects.requireNonNull(listener, "listener");
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "OpenTypeless-Rime");
            thread.setDaemon(true);
            return thread;
        };
        worker = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAXIMUM_PENDING_COMMANDS),
                factory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    public EnqueueResult process(RimeInputEngine.Key key) {
        Objects.requireNonNull(key, "key");
        return enqueue(() -> processOnWorker(key));
    }

    /** Opens the native session on the bounded worker before the user's next printable key. */
    public EnqueueResult warmUp() {
        return enqueue(this::activateOnWorker);
    }

    public EnqueueResult selectCandidate(CandidatePage.Selection selection) {
        Objects.requireNonNull(selection, "selection");
        return enqueue(() -> selectCandidateOnWorker(selection));
    }

    public EnqueueResult requestCandidatePage(CandidatePage.PageRequest request) {
        Objects.requireNonNull(request, "request");
        return enqueue(() -> requestCandidatePageOnWorker(request));
    }

    private EnqueueResult enqueue(Runnable command) {
        if (closed.get()) return EnqueueResult.CLOSED;
        try {
            worker.execute(command);
            return EnqueueResult.QUEUED;
        } catch (RejectedExecutionException rejected) {
            return closed.get() ? EnqueueResult.CLOSED : EnqueueResult.BACKPRESSURE;
        }
    }

    private void selectCandidateOnWorker(CandidatePage.Selection selection) {
        if (closed.get()) return;
        RimeInputEngine.ProcessResult result;
        try {
            result = activated && engine != null
                    ? engine.selectCandidate(new RimeInputEngine.CandidateSelectionRequest(
                            editorGeneration, selection))
                    : new RimeInputEngine.Rejected(RimeInputEngine.FailureKind.INACTIVE);
        } catch (RuntimeException failure) {
            result = new RimeInputEngine.Rejected(RimeInputEngine.FailureKind.ENGINE_FAILURE);
        }
        dispatch(result);
    }

    private void requestCandidatePageOnWorker(CandidatePage.PageRequest request) {
        if (closed.get()) return;
        RimeInputEngine.ProcessResult result;
        try {
            result = activated && engine != null
                    ? engine.requestCandidatePage(new RimeInputEngine.CandidatePageRequest(
                            editorGeneration, request))
                    : new RimeInputEngine.Rejected(RimeInputEngine.FailureKind.INACTIVE);
        } catch (RuntimeException failure) {
            result = new RimeInputEngine.Rejected(RimeInputEngine.FailureKind.ENGINE_FAILURE);
        }
        dispatch(result);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        worker.getQueue().clear();
        try {
            worker.execute(this::closeOnWorker);
        } catch (RejectedExecutionException rejected) {
            closeOnWorker();
        } finally {
            worker.shutdown();
        }
    }

    private void processOnWorker(RimeInputEngine.Key key) {
        if (closed.get()) return;
        RimeInputEngine.ProcessResult result;
        try {
            RimeInputEngine.Rejected rejected = activateOnWorker();
            if (rejected != null) {
                dispatch(rejected);
                return;
            }
            result = engine.process(new RimeInputEngine.ProcessRequest(
                    editorGeneration, coordinationGeneration, key));
        } catch (RuntimeException failure) {
            result = new RimeInputEngine.Rejected(RimeInputEngine.FailureKind.ENGINE_FAILURE);
        }
        dispatch(result);
    }

    private RimeInputEngine.Rejected activateOnWorker() {
        if (closed.get() || activated) return null;
        if (activationFailure != null) return activationFailure;
        try {
            engine = Objects.requireNonNull(engineFactory.get(), "Rime engine");
            RimeInputEngine.LifecycleResult activation = engine.activate(
                    new RimeInputEngine.Activation(
                            editorGeneration,
                            coordinationGeneration,
                            initialRevision,
                            RimeInputEngine.LearningMode.ENABLED));
            if (activation instanceof RimeInputEngine.Rejected rejected) {
                activationFailure = rejected;
                return rejected;
            }
            activated = true;
            return null;
        } catch (RuntimeException failure) {
            activationFailure = new RimeInputEngine.Rejected(
                    RimeInputEngine.FailureKind.ENGINE_FAILURE);
            return activationFailure;
        }
    }

    private void dispatch(RimeInputEngine.ProcessResult result) {
        if (closed.get()) return;
        try {
            callbackExecutor.execute(() -> {
                if (!closed.get()) {
                    listener.onResult(editorGeneration, coordinationGeneration, result);
                }
            });
        } catch (RejectedExecutionException rejected) {
            close();
        }
    }

    private void closeOnWorker() {
        RimeInputEngine owned = engine;
        engine = null;
        if (owned == null) return;
        try {
            if (activated) {
                owned.deactivate(new RimeInputEngine.Deactivation(
                        editorGeneration,
                        coordinationGeneration,
                        RimeInputEngine.DeactivationReason.TARGET_FINISHED));
            }
        } catch (RuntimeException ignored) {
            // The controller is already closed and cannot publish another callback.
        } finally {
            activated = false;
            activationFailure = null;
            owned.close();
        }
    }
}
