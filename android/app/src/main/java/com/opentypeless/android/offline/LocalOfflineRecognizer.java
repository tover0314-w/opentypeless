package com.opentypeless.android.offline;

import android.content.Context;
import android.app.ActivityManager;
import android.os.Build;

import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.opentypeless.android.audio.Pcm16WaveDecoder;

import java.util.Locale;

/** Quality-tier SenseVoice recognizer. Native calls are serialized and released after each turn. */
public final class LocalOfflineRecognizer {
    private static final Object LOCK = new Object();
    private static final int MAX_OUTPUT_CODE_POINTS = 20_000;
    private static OfflineRecognizer recognizer;
    private static String loadedFingerprint;

    private LocalOfflineRecognizer() {}

    public static String transcribe(Context context, byte[] wav) {
        return transcribe(context, wav, "");
    }

    public static String transcribe(Context context, byte[] wav, String configuredLanguage) {
        try (Session session = openSession(context, configuredLanguage)) {
            return session.transcribe(wav);
        }
    }

    /**
     * Keeps the model loaded for one user-initiated recording so prefix previews can be decoded
     * without paying the native model load cost on every update. close() always releases the
     * native allocation; sessions are serialized through the same process-wide lock as final ASR.
     */
    public static Session openSession(Context context, String configuredLanguage) {
        if (context == null) throw new IllegalArgumentException("Context is required");
        return new Session(
                OfflineModelStore.requireVerified(context),
                senseVoiceLanguage(configuredLanguage));
    }

    public static final class Session implements AutoCloseable {
        private final OfflineModelStore.InstalledModel installed;
        private final String modelLanguage;
        private boolean closed;

        private Session(
                OfflineModelStore.InstalledModel installed,
                String modelLanguage) {
            this.installed = installed;
            this.modelLanguage = modelLanguage;
        }

        public String transcribe(byte[] wav) {
            return transcribe(wav, false);
        }

        public String transcribeWithPunctuation(byte[] wav) {
            return transcribe(wav, true);
        }

        private String transcribe(byte[] wav, boolean useInverseTextNormalization) {
            Pcm16WaveDecoder.Waveform waveform = Pcm16WaveDecoder.decode(wav);
            synchronized (LOCK) {
                if (closed) throw new IllegalStateException("Offline recognition session is closed");
                try {
                    ensureLoaded(installed, modelLanguage, useInverseTextNormalization);
                    OfflineStream stream = recognizer.createStream();
                    try {
                        stream.acceptWaveform(waveform.samples(), waveform.sampleRate());
                        recognizer.decode(stream);
                        OfflineRecognizerResult result = recognizer.getResult(stream);
                        String text = result == null ? "" : result.getText();
                        if (text == null || text.trim().isEmpty()) {
                            throw new IllegalStateException("Offline recognition returned no text");
                        }
                        String trimmed = text.trim();
                        if (trimmed.codePointCount(0, trimmed.length()) > MAX_OUTPUT_CODE_POINTS) {
                            throw new IllegalStateException(
                                    "Offline recognition output exceeded the limit");
                        }
                        return trimmed;
                    } finally {
                        stream.release();
                    }
                } catch (LinkageError error) {
                    throw new IllegalStateException(
                            "Offline recognition runtime is unavailable", error);
                } catch (RuntimeException error) {
                    throw new IllegalStateException("Offline recognition failed", error);
                }
            }
        }

        @Override
        public void close() {
            synchronized (LOCK) {
                if (closed) return;
                closed = true;
                // A cached native model leaves an IME process at roughly 450 MiB RSS on the API
                // 36 arm64 gate. Release as soon as this recording's final decode completes.
                releaseLocked();
            }
        }
    }

    public static boolean isInstalled(Context context) {
        return OfflineModelStore.status(context) == OfflineModelStore.Status.INSTALLED;
    }

    public static boolean isSupportedDevice(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        return manager != null && !manager.isLowRamDevice() && supportsAbi(Build.SUPPORTED_ABIS);
    }

    static boolean supportsAbi(String[] abis) {
        if (abis == null) return false;
        for (String abi : abis) {
            if ("arm64-v8a".equals(abi) || "x86_64".equals(abi)) return true;
        }
        return false;
    }

    public static void deleteModel(Context context) {
        synchronized (LOCK) {
            releaseLocked();
            OfflineModelStore.delete(context);
        }
    }

    public static void releaseShared() {
        synchronized (LOCK) {
            releaseLocked();
        }
    }

    static String senseVoiceLanguage(String configuredLanguage) {
        String normalized = configuredLanguage == null
                ? ""
                : configuredLanguage.trim().replace('_', '-').toLowerCase(Locale.ROOT);
        if (normalized.equals("zh") || normalized.startsWith("zh-")
                || normalized.equals("cmn") || normalized.startsWith("cmn-")) {
            return "zh";
        }
        // Explicit English was slightly worse than auto on the pinned public A/B. Keep all
        // unmeasured language families on auto until they pass the same per-language gate.
        return "auto";
    }

    private static void ensureLoaded(
            OfflineModelStore.InstalledModel installed,
            String modelLanguage,
            boolean useInverseTextNormalization) {
        String desiredFingerprint = installed.fingerprint()
                + ":language=" + modelLanguage
                + ":itn=" + useInverseTextNormalization;
        if (recognizer != null && desiredFingerprint.equals(loadedFingerprint)) return;
        releaseLocked();
        OfflineModelConfig model = new OfflineModelConfig();
        model.getSenseVoice().setModel(installed.model().getAbsolutePath());
        model.getSenseVoice().setLanguage(modelLanguage);
        model.getSenseVoice().setUseInverseTextNormalization(useInverseTextNormalization);
        model.setTokens(installed.tokens().getAbsolutePath());
        model.setNumThreads(4);
        model.setProvider("cpu");
        model.setModelType("sense_voice");

        OfflineRecognizerConfig config = new OfflineRecognizerConfig();
        config.setModelConfig(model);
        config.setDecodingMethod("greedy_search");
        recognizer = new OfflineRecognizer(null, config);
        loadedFingerprint = desiredFingerprint;
    }

    private static void releaseLocked() {
        OfflineRecognizer current = recognizer;
        recognizer = null;
        loadedFingerprint = null;
        if (current != null) {
            try {
                current.release();
            } catch (RuntimeException ignored) {
                // A failed native release must not prevent deletion or a clean reinitialization.
            }
        }
    }
}
