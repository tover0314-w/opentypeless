package com.opentypeless.android.recognition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.speech.SpeechRecognizer;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class RecognitionSessionControllerTest {
    @Test
    public void deliversCallbacksOnceAndInAndroidOrder() {
        FakeEngine engine = new FakeEngine();
        RecognitionSessionController controller = new RecognitionSessionController(engine);
        RecordingObserver observer = new RecordingObserver();

        assertTrue(controller.start(request(true), observer));
        engine.callback.onReady();
        engine.callback.onReady();
        engine.callback.onBeginningOfSpeech();
        engine.callback.onBeginningOfSpeech();
        engine.callback.onPartial(" partial text ");
        engine.callback.onEndOfSpeech();
        engine.callback.onEndOfSpeech();
        engine.callback.onFinal(" final text ");

        assertEquals(
                List.of("ready", "begin", "partial:partial text", "end", "final:final text"),
                observer.events);
        assertEquals(RecognitionSessionController.State.IDLE, controller.state());
    }

    @Test
    public void finalResultSynthesizesEndOfSpeechWhenEngineOmitsIt() {
        FakeEngine engine = new FakeEngine();
        RecognitionSessionController controller = new RecognitionSessionController(engine);
        RecordingObserver observer = new RecordingObserver();

        controller.start(request(false), observer);
        engine.callback.onReady();
        engine.callback.onPartial("not requested");
        engine.callback.onFinal("done");

        assertEquals(List.of("ready", "end", "final:done"), observer.events);
    }

    @Test
    public void beginningOfSpeechSynthesizesReadyWhenEngineDeliversItFirst() {
        FakeEngine engine = new FakeEngine();
        RecognitionSessionController controller = new RecognitionSessionController(engine);
        RecordingObserver observer = new RecordingObserver();

        controller.start(request(false), observer);
        engine.callback.onBeginningOfSpeech();

        assertEquals(List.of("ready", "begin"), observer.events);
    }

    @Test
    public void stopEndsCaptureButAllowsFinalResult() {
        FakeEngine engine = new FakeEngine();
        RecognitionSessionController controller = new RecognitionSessionController(engine);
        RecordingObserver observer = new RecordingObserver();

        controller.start(request(false), observer);
        controller.stop();
        controller.stop();

        assertEquals(1, engine.stopCount);
        assertEquals(RecognitionSessionController.State.PROCESSING, controller.state());
        engine.callback.onFinal("captured so far");
        assertEquals("final:captured so far", observer.events.get(1));
    }

    @Test
    public void cancelDropsLateCallbacksAndPermitsNextSession() {
        FakeEngine engine = new FakeEngine();
        RecognitionSessionController controller = new RecognitionSessionController(engine);
        RecordingObserver first = new RecordingObserver();
        RecordingObserver second = new RecordingObserver();

        controller.start(request(true), first);
        RecognitionSessionController.Engine.Callback stale = engine.callback;
        controller.cancel();
        stale.onFinal("must be ignored");

        assertEquals(List.of("cancelled"), first.events);
        assertEquals(1, engine.cancelCount);
        assertTrue(controller.start(request(false), second));
        engine.callback.onFinal("new result");
        assertEquals(List.of("end", "final:new result"), second.events);
    }

    @Test
    public void reportsBusyWithoutDisturbingActiveSession() {
        FakeEngine engine = new FakeEngine();
        RecognitionSessionController controller = new RecognitionSessionController(engine);
        RecordingObserver first = new RecordingObserver();
        RecordingObserver second = new RecordingObserver();

        assertTrue(controller.start(request(false), first));
        assertFalse(controller.start(request(false), second));

        assertEquals(SpeechRecognizer.ERROR_RECOGNIZER_BUSY, second.failure.errorCode());
        engine.callback.onFinal("still active");
        assertEquals(List.of("end", "final:still active"), first.events);
    }

    @Test
    public void propagatesExplicitUnsupportedBackendFailure() {
        FakeEngine engine = new FakeEngine();
        engine.startFailure = new RecognitionStartException(
                RecognitionErrors.unsupportedBackend(
                        com.opentypeless.android.settings.RecognitionBackend.SYSTEM_ON_DEVICE));
        RecognitionSessionController controller = new RecognitionSessionController(engine);
        RecordingObserver observer = new RecordingObserver();

        assertFalse(controller.start(request(false), observer));

        assertEquals(SpeechRecognizer.ERROR_CLIENT, observer.failure.errorCode());
        assertTrue(observer.failure.message().contains("only the BYOK / OpenAI-compatible"));
        assertEquals(RecognitionSessionController.State.IDLE, controller.state());
    }

    @Test
    public void blankFinalIsReportedAsNoMatch() {
        FakeEngine engine = new FakeEngine();
        RecognitionSessionController controller = new RecognitionSessionController(engine);
        RecordingObserver observer = new RecordingObserver();

        controller.start(request(false), observer);
        engine.callback.onFinal("   ");

        assertEquals(SpeechRecognizer.ERROR_NO_MATCH, observer.failure.errorCode());
    }

    @Test
    public void cancelCannotReturnWhileFinalObserverIsStillRunning() throws Exception {
        FakeEngine engine = new FakeEngine();
        RecognitionSessionController controller = new RecognitionSessionController(engine);
        CountDownLatch finalEntered = new CountDownLatch(1);
        CountDownLatch releaseFinal = new CountDownLatch(1);
        CountDownLatch cancelReturned = new CountDownLatch(1);
        RecordingObserver observer = new RecordingObserver() {
            @Override
            public void onFinal(RecognitionResult result) {
                finalEntered.countDown();
                await(releaseFinal);
                super.onFinal(result);
            }
        };

        controller.start(request(false), observer);
        Thread finalThread = new Thread(() -> engine.callback.onFinal("done"));
        finalThread.start();
        assertTrue(finalEntered.await(1, TimeUnit.SECONDS));

        Thread cancelThread = new Thread(() -> {
            controller.cancel();
            cancelReturned.countDown();
        });
        cancelThread.start();
        assertFalse(cancelReturned.await(100, TimeUnit.MILLISECONDS));

        releaseFinal.countDown();
        finalThread.join(1_000);
        cancelThread.join(1_000);
        assertTrue(cancelReturned.await(1, TimeUnit.SECONDS));
        assertEquals(List.of("end", "final:done"), observer.events);
    }

    @Test
    public void newSessionIsRejectedUntilEngineCancellationReturns() throws Exception {
        FakeEngine engine = new FakeEngine();
        engine.cancelEntered = new CountDownLatch(1);
        engine.releaseCancel = new CountDownLatch(1);
        RecognitionSessionController controller = new RecognitionSessionController(engine);
        RecordingObserver first = new RecordingObserver();
        RecordingObserver duringCancel = new RecordingObserver();

        controller.start(request(false), first);
        Thread cancelThread = new Thread(controller::cancel);
        cancelThread.start();
        assertTrue(engine.cancelEntered.await(1, TimeUnit.SECONDS));

        assertFalse(controller.start(request(false), duringCancel));
        assertEquals(SpeechRecognizer.ERROR_RECOGNIZER_BUSY, duringCancel.failure.errorCode());

        engine.releaseCancel.countDown();
        cancelThread.join(1_000);
        assertTrue(controller.start(request(false), new RecordingObserver()));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) throw new AssertionError("Timed out");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    private static RecognitionRequest request(boolean partialResults) {
        return new RecognitionRequest("zh-CN", "com.example", "", 1, partialResults);
    }

    private static final class FakeEngine implements RecognitionSessionController.Engine {
        Callback callback;
        RecognitionStartException startFailure;
        int stopCount;
        int cancelCount;
        int shutdownCount;
        CountDownLatch cancelEntered;
        CountDownLatch releaseCancel;

        @Override
        public boolean start(RecognitionRequest request, Callback callback) throws Exception {
            if (startFailure != null) throw startFailure;
            this.callback = callback;
            return true;
        }

        @Override public void stop() { stopCount++; }
        @Override
        public void cancel() {
            cancelCount++;
            if (cancelEntered != null) cancelEntered.countDown();
            if (releaseCancel != null) await(releaseCancel);
        }
        @Override public void shutdown() { shutdownCount++; }
    }

    private static class RecordingObserver implements RecognitionSessionController.Observer {
        final List<String> events = new ArrayList<>();
        RecognitionFailure failure;

        @Override public void onReady() { events.add("ready"); }
        @Override public void onBeginningOfSpeech() { events.add("begin"); }
        @Override public void onEndOfSpeech() { events.add("end"); }
        @Override public void onPartial(RecognitionResult result) {
            events.add("partial:" + result.bestText());
        }
        @Override public void onFinal(RecognitionResult result) {
            events.add("final:" + result.bestText());
        }
        @Override public void onError(RecognitionFailure failure) { this.failure = failure; }
        @Override public void onCancelled() { events.add("cancelled"); }
    }
}
