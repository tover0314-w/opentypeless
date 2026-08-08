package com.opentypeless.android.ime;

import com.opentypeless.android.audio.AudioRecorder;
import com.opentypeless.android.net.OpenAiCompatibleClient;
import com.opentypeless.android.settings.AppSettings;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class VoicePipeline {
    public enum State { IDLE, RECORDING, TRANSCRIBING, POLISHING }

    public interface Listener {
        void onState(State state, String message);
        void onResult(String text, String message);
        void onError(String message);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final AtomicLong generation = new AtomicLong();
    private final AudioRecorder recorder = new AudioRecorder();
    private final OpenAiCompatibleClient client = new OpenAiCompatibleClient();
    private volatile Future<?> activeTask;

    public boolean start(AppSettings settings, Listener listener) {
        if (!state.compareAndSet(State.IDLE, State.RECORDING)) return false;
        long run = generation.incrementAndGet();
        listener.onState(State.RECORDING, "Listening… tap mic to stop");
        activeTask = executor.submit(() -> execute(run, settings, listener));
        return true;
    }

    private void execute(long run, AppSettings settings, Listener listener) {
        try {
            byte[] wav = recorder.recordUntilStopped();
            if (!isCurrent(run)) return;
            state.set(State.TRANSCRIBING);
            listener.onState(State.TRANSCRIBING, "Transcribing…");
            String text = client.transcribe(wav, settings);
            String resultMessage = "Inserted";

            if (!isCurrent(run)) return;
            if (settings.polishEnabled()) {
                state.set(State.POLISHING);
                listener.onState(State.POLISHING, "Polishing…");
                try {
                    text = client.polish(text, settings);
                } catch (Exception error) {
                    if (!isCurrent(run)) return;
                    resultMessage = "Inserted raw transcript because AI polish failed";
                }
            }
            if (!isCurrent(run)) return;
            listener.onResult(text, resultMessage);
        } catch (Exception error) {
            if (isCurrent(run)) {
                String message = error.getMessage();
                listener.onError(message == null || message.trim().isEmpty()
                        ? "Voice input failed"
                        : message);
            }
        } finally {
            if (isCurrent(run)) {
                state.set(State.IDLE);
            }
        }
    }

    public void stopRecording() {
        if (state.get() == State.RECORDING) recorder.stop();
    }

    public void cancel() {
        generation.incrementAndGet();
        recorder.stop();
        client.cancelActiveRequest();
        Future<?> task = activeTask;
        if (task != null) task.cancel(true);
        state.set(State.IDLE);
    }

    public State state() {
        return state.get();
    }

    public void shutdown() {
        cancel();
        executor.shutdownNow();
    }

    private boolean isCurrent(long run) {
        return generation.get() == run;
    }
}
