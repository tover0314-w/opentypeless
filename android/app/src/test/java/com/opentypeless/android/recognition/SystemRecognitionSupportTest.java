package com.opentypeless.android.recognition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.config.RecognitionRoute;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class SystemRecognitionSupportTest {
    @Test
    public void resultShapesAreClosedAndDiagnosticsRedactLanguage() {
        SystemRecognitionSupport.Result installed = new SystemRecognitionSupport.Result(
                SystemRecognitionSupport.Status.INSTALLED,
                "private-language-tag",
                false,
                null);
        SystemRecognitionSupport.DownloadResult failed =
                new SystemRecognitionSupport.DownloadResult(
                        SystemRecognitionSupport.DownloadStatus.FAILED,
                        RecognitionRoute.FailureClass.INTERNAL_ERROR);

        assertFalse(installed.toString().contains("private-language-tag"));
        assertFalse(failed.toString().contains("provider-secret"));
        assertThrows(IllegalArgumentException.class, () ->
                new SystemRecognitionSupport.Result(
                        SystemRecognitionSupport.Status.INSTALLED,
                        "en-US",
                        false,
                        RecognitionRoute.FailureClass.INTERNAL_ERROR));
        assertThrows(IllegalArgumentException.class, () ->
                new SystemRecognitionSupport.Result(
                        SystemRecognitionSupport.Status.ERROR,
                        "",
                        false,
                        null));
        assertThrows(IllegalArgumentException.class, () ->
                new SystemRecognitionSupport.DownloadResult(
                        SystemRecognitionSupport.DownloadStatus.COMPLETED,
                        RecognitionRoute.FailureClass.INTERNAL_ERROR));
    }

    @Test
    public void firstTerminalWinsAndCancellationDropsLateOemCallbacks() {
        FakeScheduler scheduler = new FakeScheduler();
        SystemRecognitionSupport.OneShotOperation operation =
                new SystemRecognitionSupport.OneShotOperation(scheduler);
        AtomicInteger results = new AtomicInteger();
        SystemRecognitionSupport.Callback callback = ignored -> results.incrementAndGet();
        SystemRecognitionSupport.Result result = new SystemRecognitionSupport.Result(
                SystemRecognitionSupport.Status.INSTALLED,
                "en-US",
                false,
                null);

        SystemRecognitionSupport.complete(operation, callback, result);
        SystemRecognitionSupport.complete(operation, callback, result);

        assertEquals(1, results.get());
        assertFalse(operation.isActive());

        SystemRecognitionSupport.OneShotOperation cancelled =
                new SystemRecognitionSupport.OneShotOperation(scheduler);
        cancelled.cancel();
        scheduler.runPosted();
        SystemRecognitionSupport.complete(cancelled, callback, result);
        assertEquals(1, results.get());
    }

    @Test
    public void progressIsBoundedMonotonicAndStopsAtTerminal() {
        FakeScheduler scheduler = new FakeScheduler();
        SystemRecognitionSupport.OneShotOperation operation =
                new SystemRecognitionSupport.OneShotOperation(scheduler);
        List<Integer> progress = new ArrayList<>();
        SystemRecognitionSupport.DownloadCallback callback =
                new SystemRecognitionSupport.DownloadCallback() {
                    @Override
                    public void onProgress(int percent) {
                        progress.add(percent);
                    }

                    @Override
                    public void onResult(SystemRecognitionSupport.DownloadResult result) {}
                };

        SystemRecognitionSupport.reportDownloadProgress(operation, callback, -20);
        SystemRecognitionSupport.reportDownloadProgress(operation, callback, 0);
        SystemRecognitionSupport.reportDownloadProgress(operation, callback, 40);
        SystemRecognitionSupport.reportDownloadProgress(operation, callback, 20);
        SystemRecognitionSupport.reportDownloadProgress(operation, callback, 140);
        SystemRecognitionSupport.completeDownload(
                operation,
                callback,
                new SystemRecognitionSupport.DownloadResult(
                        SystemRecognitionSupport.DownloadStatus.COMPLETED,
                        null));
        SystemRecognitionSupport.reportDownloadProgress(operation, callback, 100);

        assertEquals(List.of(0, 40, 100), progress);
    }

    @Test
    public void api33DispatchReportsOnceThenRetiresAfterGrace() {
        FakeScheduler scheduler = new FakeScheduler();
        SystemRecognitionSupport.OneShotOperation operation =
                new SystemRecognitionSupport.OneShotOperation(scheduler);
        AtomicInteger results = new AtomicInteger();
        SystemRecognitionSupport.DownloadCallback callback = ignored -> results.incrementAndGet();

        SystemRecognitionSupport.reportDownloadDispatched(
                operation,
                callback,
                new SystemRecognitionSupport.DownloadResult(
                        SystemRecognitionSupport.DownloadStatus.REQUESTED,
                        null));
        SystemRecognitionSupport.completeDownload(
                operation,
                callback,
                new SystemRecognitionSupport.DownloadResult(
                        SystemRecognitionSupport.DownloadStatus.FAILED,
                        RecognitionRoute.FailureClass.INTERNAL_ERROR));

        assertEquals(1, results.get());
        assertTrue(operation.isActive());
        scheduler.runDelayed();
        assertFalse(operation.isActive());
    }

    @Test
    public void cancellationDisarmsTimeoutBeforeItCanClassify() {
        FakeScheduler scheduler = new FakeScheduler();
        SystemRecognitionSupport.OneShotOperation operation =
                new SystemRecognitionSupport.OneShotOperation(scheduler);
        AtomicInteger timeouts = new AtomicInteger();

        operation.armTimeout(timeouts::incrementAndGet);
        operation.cancel();
        scheduler.runPosted();
        scheduler.runDelayed();

        assertEquals(0, timeouts.get());
        assertFalse(operation.isActive());
    }

    private static final class FakeScheduler implements SystemRecognitionSupport.Scheduler {
        private final List<Runnable> posted = new ArrayList<>();
        private final List<Runnable> delayed = new ArrayList<>();

        @Override
        public void post(Runnable action) {
            posted.add(action);
        }

        @Override
        public void postDelayed(Runnable action, long delayMillis) {
            delayed.add(action);
        }

        @Override
        public void removeCallbacks(Runnable action) {
            posted.remove(action);
            delayed.remove(action);
        }

        void runPosted() {
            run(posted);
        }

        void runDelayed() {
            run(delayed);
        }

        private static void run(List<Runnable> actions) {
            List<Runnable> copy = new ArrayList<>(actions);
            actions.clear();
            for (Runnable action : copy) action.run();
        }
    }
}
