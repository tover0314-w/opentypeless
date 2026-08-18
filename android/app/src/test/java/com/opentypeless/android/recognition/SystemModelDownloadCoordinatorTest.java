package com.opentypeless.android.recognition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.config.RecognitionRoute;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public final class SystemModelDownloadCoordinatorTest {
    @Test
    public void activitySubscriptionRebindDropsQueuedAndStaleCallbacks() {
        FakePoster poster = new FakePoster();
        SystemModelDownloadCoordinator.Core core =
                new SystemModelDownloadCoordinator.Core(poster);
        List<SystemModelDownloadCoordinator.State> firstActivity = new ArrayList<>();
        SystemModelDownloadCoordinator.Subscription first = core.subscribe(firstActivity::add);
        first.close();
        poster.runAll();
        assertTrue(firstActivity.isEmpty());

        List<SystemModelDownloadCoordinator.State> secondActivity = new ArrayList<>();
        SystemModelDownloadCoordinator.Subscription second = core.subscribe(secondActivity::add);
        poster.runAll();
        assertEquals(1, secondActivity.size());

        FakeStarter starter = new FakeStarter();
        assertTrue(core.start("zh-CN-private", starter));
        poster.runAll();
        starter.callback.onProgress(40);
        poster.runAll();
        starter.callback.onProgress(20);
        starter.callback.onProgress(60);
        poster.runAll();
        assertEquals(60, secondActivity.get(secondActivity.size() - 1).progress());

        second.close();
        starter.callback.onResult(completed());
        poster.runAll();
        int stoppedSize = secondActivity.size();
        starter.callback.onProgress(100);
        starter.callback.onResult(failed());
        poster.runAll();
        assertEquals(stoppedSize, secondActivity.size());

        List<SystemModelDownloadCoordinator.State> recreated = new ArrayList<>();
        core.subscribe(recreated::add);
        poster.runAll();
        assertEquals(SystemRecognitionSupport.DownloadStatus.COMPLETED,
                recreated.get(0).result().status());
        assertFalse(recreated.get(0).toString().contains("zh-CN-private"));
    }

    @Test
    public void cancellationAndReplacementUseOpaqueRequestIdentity() {
        FakePoster poster = new FakePoster();
        SystemModelDownloadCoordinator.Core core =
                new SystemModelDownloadCoordinator.Core(poster);
        FakeStarter first = new FakeStarter();
        assertTrue(core.start("en-US", first));
        long firstGeneration = core.snapshot().generation();

        core.cancel();
        assertTrue(first.operation.cancelled);
        first.callback.onResult(completed());
        assertEquals(firstGeneration, core.snapshot().generation());
        assertTrue(core.snapshot().result() == null);

        FakeStarter replacement = new FakeStarter();
        assertTrue(core.start("fr-FR", replacement));
        assertEquals(firstGeneration + 1L, core.snapshot().generation());
        first.callback.onProgress(100);
        assertEquals(0, core.snapshot().progress());
        replacement.callback.onResult(completed());
        assertEquals(SystemRecognitionSupport.DownloadStatus.COMPLETED,
                core.snapshot().result().status());
    }

    @Test
    public void synchronousOemTerminalCannotLeaveAnAttachedOperation() {
        FakePoster poster = new FakePoster();
        SystemModelDownloadCoordinator.Core core =
                new SystemModelDownloadCoordinator.Core(poster);
        FakeOperation operation = new FakeOperation();

        assertTrue(core.start("ja-JP", callback -> {
            callback.onResult(new SystemRecognitionSupport.DownloadResult(
                    SystemRecognitionSupport.DownloadStatus.REQUESTED,
                    null));
            return operation;
        }));

        assertTrue(operation.cancelled);
        assertFalse(core.snapshot().running());
        assertEquals(SystemRecognitionSupport.DownloadStatus.REQUESTED,
                core.snapshot().result().status());
    }

    @Test
    public void oemStartFailureIsContentFreeAndDoesNotWedgeNextRequest() {
        FakePoster poster = new FakePoster();
        SystemModelDownloadCoordinator.Core core =
                new SystemModelDownloadCoordinator.Core(poster);

        assertFalse(core.start("provider-secret", callback -> {
            throw new IllegalStateException("oem-secret-body");
        }));
        assertEquals(RecognitionRoute.FailureClass.INTERNAL_ERROR,
                core.snapshot().result().failureClass());
        assertFalse(core.snapshot().toString().contains("provider-secret"));
        assertFalse(core.snapshot().toString().contains("oem-secret-body"));

        assertTrue(core.start("en-US", new FakeStarter()));
    }

    @Test
    public void generationExhaustionFailsClosedWithoutStartingOemWork() throws Exception {
        FakePoster poster = new FakePoster();
        SystemModelDownloadCoordinator.Core core =
                new SystemModelDownloadCoordinator.Core(poster);
        Field generation = SystemModelDownloadCoordinator.Core.class.getDeclaredField("generation");
        generation.setAccessible(true);
        generation.setLong(core, Long.MAX_VALUE);
        FakeStarter starter = new FakeStarter();

        assertFalse(core.start("en-US", starter));

        assertEquals(0, starter.starts);
        assertEquals(Long.MAX_VALUE, core.snapshot().generation());
        assertEquals(RecognitionRoute.FailureClass.INTERNAL_ERROR,
                core.snapshot().result().failureClass());
    }

    private static SystemRecognitionSupport.DownloadResult completed() {
        return new SystemRecognitionSupport.DownloadResult(
                SystemRecognitionSupport.DownloadStatus.COMPLETED,
                null);
    }

    private static SystemRecognitionSupport.DownloadResult failed() {
        return new SystemRecognitionSupport.DownloadResult(
                SystemRecognitionSupport.DownloadStatus.FAILED,
                RecognitionRoute.FailureClass.INTERNAL_ERROR);
    }

    private static final class FakePoster implements SystemModelDownloadCoordinator.Poster {
        private final List<Runnable> queued = new ArrayList<>();

        @Override
        public void post(Runnable action) {
            queued.add(action);
        }

        void runAll() {
            List<Runnable> copy = new ArrayList<>(queued);
            queued.clear();
            for (Runnable action : copy) action.run();
        }
    }

    private static final class FakeStarter implements SystemModelDownloadCoordinator.Starter {
        private final FakeOperation operation = new FakeOperation();
        private SystemRecognitionSupport.DownloadCallback callback;
        private int starts;

        @Override
        public SystemRecognitionSupport.Operation start(
                SystemRecognitionSupport.DownloadCallback callback) {
            starts++;
            this.callback = callback;
            return operation;
        }
    }

    private static final class FakeOperation implements SystemRecognitionSupport.Operation {
        private boolean cancelled;

        @Override
        public void cancel() {
            cancelled = true;
        }
    }
}
