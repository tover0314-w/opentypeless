package com.opentypeless.android.audio;

/**
 * Lightweight deterministic VAD for the upload backend. It never sends audio itself and is used
 * only to end a recording after sustained post-speech silence and trim long leading silence.
 */
public final class AdaptiveVad {
    public enum Decision { CONTINUE, END_OF_SPEECH, NO_SPEECH_TIMEOUT }

    private static final long MIN_THRESHOLD = 550L;
    private final long bytesPerSecond;
    private final long speechConfirmationBytes;
    private final long manualEndpointConfirmationBytes;
    private final long onsetGapToleranceBytes;
    private final long endSilenceBytes;
    private final long noSpeechTimeoutBytes;
    private double noiseFloor = 180.0;
    private long candidateVoiceBytes;
    private long candidateGapBytes;
    private long candidateSpeechStart = -1L;
    private long candidateLastVoiceEnd = -1L;
    private long endpointVoiceBytes;
    private long endpointSpeechStart = -1L;
    private long endpointLastVoiceEnd = -1L;
    private long speechStartByte = -1L;
    private long lastVoiceEndByte = -1L;
    private long silenceBytes;

    public AdaptiveVad(int sampleRate) {
        if (sampleRate <= 0) throw new IllegalArgumentException("Sample rate must be positive");
        bytesPerSecond = sampleRate * 2L;
        speechConfirmationBytes = bytesForMs(300);
        manualEndpointConfirmationBytes = bytesForMs(120);
        onsetGapToleranceBytes = bytesForMs(180);
        endSilenceBytes = bytesForMs(2_000);
        noSpeechTimeoutBytes = bytesForMs(15_000);
    }

    public Decision accept(byte[] pcm16, int length, long capturedBytes) {
        if (length <= 1) return Decision.CONTINUE;
        double rms = rms16(pcm16, length);
        double onsetThreshold = Math.max(MIN_THRESHOLD, noiseFloor * 2.8 + 120.0);
        boolean voice = rms >= onsetThreshold;

        if (speechStartByte < 0) {
            if (voice) {
                if (endpointSpeechStart < 0L) {
                    endpointSpeechStart = Math.max(0L, capturedBytes - length);
                }
                endpointVoiceBytes += length;
                endpointLastVoiceEnd = capturedBytes;
                if (candidateSpeechStart < 0L) {
                    candidateSpeechStart = Math.max(0L, capturedBytes - length);
                }
                candidateVoiceBytes += length;
                candidateGapBytes = 0L;
                candidateLastVoiceEnd = capturedBytes;
                if (candidateVoiceBytes >= speechConfirmationBytes) {
                    speechStartByte = candidateSpeechStart;
                    lastVoiceEndByte = candidateLastVoiceEnd;
                    silenceBytes = 0L;
                }
            } else {
                if (candidateSpeechStart >= 0L) {
                    candidateGapBytes += length;
                    if (candidateGapBytes > onsetGapToleranceBytes) resetCandidate();
                } else {
                    noiseFloor = noiseFloor * 0.94 + rms * 0.06;
                }
            }
            return speechStartByte < 0 && capturedBytes >= noSpeechTimeoutBytes
                    ? Decision.NO_SPEECH_TIMEOUT
                    : Decision.CONTINUE;
        }

        // A lower release threshold preserves quiet word endings after speech has been confirmed.
        double releaseThreshold = Math.max(350.0, onsetThreshold * 0.68);
        if (rms >= releaseThreshold) {
            lastVoiceEndByte = capturedBytes;
            silenceBytes = 0L;
        } else {
            silenceBytes += length;
        }
        return silenceBytes >= endSilenceBytes
                ? Decision.END_OF_SPEECH
                : Decision.CONTINUE;
    }

    private void resetCandidate() {
        candidateVoiceBytes = 0L;
        candidateGapBytes = 0L;
        candidateSpeechStart = -1L;
        candidateLastVoiceEnd = -1L;
    }

    public boolean heardSpeech() {
        return speechStartByte >= 0L;
    }

    /**
     * Accepts weaker speech evidence only when the user explicitly ends a hold-to-talk style
     * capture. Automatic endpointing still requires the full 300 ms confirmation above, so a
     * short cough or click cannot open ordinary single-utterance recording.
     */
    boolean confirmAtManualEndpoint() {
        if (heardSpeech()) return true;
        if (endpointSpeechStart < 0L
                || endpointLastVoiceEnd < 0L
                || endpointVoiceBytes < manualEndpointConfirmationBytes) {
            return false;
        }
        speechStartByte = endpointSpeechStart;
        lastVoiceEndByte = endpointLastVoiceEnd;
        return true;
    }

    public int recommendedStart(int totalBytes) {
        if (!heardSpeech()) return 0;
        long withPreRoll = Math.max(0L, speechStartByte - bytesForMs(350));
        return evenBound(withPreRoll, totalBytes);
    }

    public int recommendedEnd(int totalBytes, boolean autoStopped) {
        if (!autoStopped || lastVoiceEndByte < 0L) return evenBound(totalBytes, totalBytes);
        long withPostRoll = Math.min(totalBytes, lastVoiceEndByte + bytesForMs(400));
        return evenBound(withPostRoll, totalBytes);
    }

    private long bytesForMs(long milliseconds) {
        return bytesPerSecond * milliseconds / 1_000L;
    }

    private static int evenBound(long value, int maximum) {
        int bounded = (int) Math.max(0L, Math.min(value, maximum));
        return bounded & ~1;
    }

    private static double rms16(byte[] pcm, int length) {
        long sumSquares = 0L;
        int samples = 0;
        for (int index = 0; index + 1 < length; index += 2) {
            int sample = (short) ((pcm[index] & 0xff) | (pcm[index + 1] << 8));
            sumSquares += (long) sample * sample;
            samples++;
        }
        return samples == 0 ? 0.0 : Math.sqrt((double) sumSquares / samples);
    }
}
