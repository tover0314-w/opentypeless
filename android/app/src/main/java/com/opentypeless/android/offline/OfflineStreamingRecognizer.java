package com.opentypeless.android.offline;

import android.content.Context;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult;
import com.k2fsa.sherpa.onnx.OnlineStream;

/** True first-pass streaming recognizer; SenseVoice remains the authoritative second pass. */
public final class OfflineStreamingRecognizer {
    private static final Object LOCK = new Object();
    private static final int MAX_OUTPUT_CODE_POINTS = 20_000;
    private static OnlineRecognizer recognizer;
    private static String loadedFingerprint;

    private OfflineStreamingRecognizer() {}

    public static boolean isInstalled(Context context) {
        return OfflineStreamingModelStore.status(context)
                == OfflineStreamingModelStore.Status.INSTALLED;
    }

    public static Session openSession(Context context) {
        if (context == null) throw new IllegalArgumentException("Context is required");
        return new Session(OfflineStreamingModelStore.requireVerified(context));
    }

    /** Loads and verifies the shared online recognizer without opening a microphone stream. */
    public static void prewarm(Context context) {
        if (context == null) throw new IllegalArgumentException("Context is required");
        OfflineStreamingModelStore.InstalledModel installed =
                OfflineStreamingModelStore.requireVerified(context);
        synchronized (LOCK) {
            try {
                ensureLoaded(installed);
            } catch (LinkageError error) {
                throw new IllegalStateException(
                        "Live preview recognition runtime is unavailable", error);
            } catch (RuntimeException error) {
                throw new IllegalStateException("Live preview model could not start", error);
            }
        }
    }

    public static final class Session implements AutoCloseable {
        private final OnlineStream stream;
        private boolean closed;
        private String lastText = "";

        private Session(OfflineStreamingModelStore.InstalledModel installed) {
            synchronized (LOCK) {
                try {
                    ensureLoaded(installed);
                    stream = recognizer.createStream("");
                } catch (LinkageError error) {
                    throw new IllegalStateException(
                            "Live preview recognition runtime is unavailable", error);
                } catch (RuntimeException error) {
                    throw new IllegalStateException("Live preview model could not start", error);
                }
            }
        }

        /** Accepts little-endian PCM16 and returns a changed hypothesis, or an empty string. */
        public String acceptPcm16(byte[] pcm16, int length) {
            if (pcm16 == null || length <= 0) return "";
            int safeLength = Math.min(length, pcm16.length) & ~1;
            if (safeLength <= 0) return "";
            float[] samples = pcm16ToFloat(pcm16, safeLength);
            synchronized (LOCK) {
                requireOpen();
                stream.acceptWaveform(samples, 16_000);
                decodeReady();
                return changedResult();
            }
        }

        /** Flushes the native online stream and returns its best final first-pass text. */
        public String finish() {
            synchronized (LOCK) {
                requireOpen();
                // sherpa-onnx 1.13.x uses this option to flush streaming Paraformer state.
                stream.setOption("is_final", "1");
                stream.inputFinished();
                decodeReady();
                String result = resultText();
                if (!result.isEmpty()) lastText = result;
                return lastText;
            }
        }

        private void decodeReady() {
            while (recognizer.isReady(stream)) recognizer.decode(stream);
        }

        private String changedResult() {
            String result = resultText();
            if (result.isEmpty() || result.equals(lastText)) return "";
            lastText = result;
            return result;
        }

        private String resultText() {
            OnlineRecognizerResult result = recognizer.getResult(stream);
            String text = result == null || result.getText() == null
                    ? ""
                    : result.getText().trim();
            if (text.codePointCount(0, text.length()) > MAX_OUTPUT_CODE_POINTS) {
                throw new IllegalStateException("Live preview output exceeded the limit");
            }
            return text;
        }

        private void requireOpen() {
            if (closed) throw new IllegalStateException("Live preview session is closed");
        }

        @Override
        public void close() {
            synchronized (LOCK) {
                if (closed) return;
                closed = true;
                // The streaming recognizer lives in its own private process. Retaining the shared
                // weights across sessions removes the 1+ second cold-start tax without keeping the
                // much larger quality model in the IME process. The service releases this cache on
                // memory pressure or process teardown.
                stream.release();
            }
        }
    }

    static float[] pcm16ToFloat(byte[] pcm16, int length) {
        int safeLength = Math.min(length, pcm16.length) & ~1;
        float[] samples = new float[safeLength / 2];
        for (int index = 0, sampleIndex = 0; index < safeLength; index += 2, sampleIndex++) {
            int low = pcm16[index] & 0xff;
            int high = pcm16[index + 1];
            short sample = (short) (low | (high << 8));
            samples[sampleIndex] = sample / 32768.0f;
        }
        return samples;
    }

    private static void ensureLoaded(OfflineStreamingModelStore.InstalledModel installed) {
        if (recognizer != null && installed.fingerprint().equals(loadedFingerprint)) return;
        releaseLocked();

        FeatureConfig features = new FeatureConfig();
        features.setSampleRate(16_000);
        features.setFeatureDim(80);

        OnlineModelConfig model = new OnlineModelConfig();
        model.getParaformer().setEncoder(installed.encoder().getAbsolutePath());
        model.getParaformer().setDecoder(installed.decoder().getAbsolutePath());
        model.setTokens(installed.tokens().getAbsolutePath());
        model.setNumThreads(2);
        model.setProvider("cpu");

        OnlineRecognizerConfig config = new OnlineRecognizerConfig();
        config.setFeatConfig(features);
        config.setModelConfig(model);
        config.setEnableEndpoint(false);
        config.setDecodingMethod("greedy_search");
        recognizer = new OnlineRecognizer(null, config);
        loadedFingerprint = installed.fingerprint();
    }

    public static void releaseShared() {
        synchronized (LOCK) {
            releaseLocked();
        }
    }

    private static void releaseLocked() {
        OnlineRecognizer current = recognizer;
        recognizer = null;
        loadedFingerprint = null;
        if (current != null) {
            try {
                current.release();
            } catch (RuntimeException ignored) {
                // The next session performs a clean native initialization.
            }
        }
    }
}
