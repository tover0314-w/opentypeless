package com.opentypeless.android.offline;

import android.content.Context;

import com.k2fsa.sherpa.onnx.OfflinePunctuation;
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig;
import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig;

/** Process-local CT-Transformer punctuation runtime. It never changes ASR words intentionally. */
public final class LocalPunctuationRecognizer {
    private static final Object LOCK = new Object();
    private static final int MAX_TEXT_CODE_POINTS = 20_000;
    private static OfflinePunctuation punctuation;
    private static String loadedFingerprint;

    private LocalPunctuationRecognizer() {}

    public static boolean isInstalled(Context context) {
        return OfflinePunctuationModelStore.status(context)
                == OfflinePunctuationModelStore.Status.INSTALLED;
    }

    public static void prewarm(Context context) {
        synchronized (LOCK) {
            ensureLoaded(OfflinePunctuationModelStore.requireVerified(context));
        }
    }

    public static String addPunctuation(Context context, String source) {
        String text = boundedText(source);
        synchronized (LOCK) {
            try {
                ensureLoaded(OfflinePunctuationModelStore.requireVerified(context));
                String result = punctuation.addPunctuation(text);
                return boundedText(result);
            } catch (LinkageError error) {
                throw new IllegalStateException("Punctuation runtime is unavailable", error);
            } catch (RuntimeException error) {
                throw new IllegalStateException("Punctuation restoration failed", error);
            }
        }
    }

    public static void releaseShared() {
        synchronized (LOCK) {
            OfflinePunctuation current = punctuation;
            punctuation = null;
            loadedFingerprint = null;
            if (current != null) {
                try {
                    current.release();
                } catch (RuntimeException ignored) {
                    // A future call constructs a fresh native session.
                }
            }
        }
    }

    private static void ensureLoaded(OfflinePunctuationModelStore.InstalledModel installed) {
        if (punctuation != null && installed.fingerprint().equals(loadedFingerprint)) return;
        releaseShared();
        OfflinePunctuationModelConfig model = new OfflinePunctuationModelConfig();
        model.setCtTransformer(installed.model().getAbsolutePath());
        model.setNumThreads(2);
        model.setDebug(false);
        model.setProvider("cpu");
        punctuation = new OfflinePunctuation(null, new OfflinePunctuationConfig(model));
        loadedFingerprint = installed.fingerprint();
    }

    private static String boundedText(String source) {
        String text = source == null ? "" : source.trim();
        if (text.isEmpty()) throw new IllegalArgumentException("Punctuation text is empty");
        if (text.codePointCount(0, text.length()) > MAX_TEXT_CODE_POINTS) {
            throw new IllegalArgumentException("Punctuation text exceeded the limit");
        }
        return text;
    }
}
