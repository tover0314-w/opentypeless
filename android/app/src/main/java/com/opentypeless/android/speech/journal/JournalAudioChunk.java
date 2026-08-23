package com.opentypeless.android.speech.journal;

import java.util.Objects;

/** Recovered PCM16 little-endian chunk. */
public record JournalAudioChunk(
        long chunkIndex, long startSample, int sampleRate, byte[] pcm16LittleEndian) {
    public JournalAudioChunk {
        if (chunkIndex < 0L || startSample < 0L || sampleRate <= 0) {
            throw new IllegalArgumentException("invalid recovered audio chunk metadata");
        }
        pcm16LittleEndian = Objects.requireNonNull(pcm16LittleEndian, "pcm16LittleEndian").clone();
        if (pcm16LittleEndian.length == 0 || (pcm16LittleEndian.length & 1) != 0) {
            throw new IllegalArgumentException("PCM16 chunk must contain complete samples");
        }
    }

    @Override
    public byte[] pcm16LittleEndian() {
        return pcm16LittleEndian.clone();
    }

    /** Length-only inspection avoids cloning recovered audio for bounds and duration accounting. */
    public int byteLength() {
        return pcm16LittleEndian.length;
    }
}
