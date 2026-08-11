package com.opentypeless.android.speech.journal;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import com.opentypeless.android.speech.core.DeliveryState;
import com.opentypeless.android.speech.core.RevisionOrigin;
import com.opentypeless.android.speech.core.RevisionStage;
import com.opentypeless.android.speech.core.SegmentJoin;
import com.opentypeless.android.speech.core.SegmentRevision;
import com.opentypeless.android.speech.core.SessionId;
import com.opentypeless.android.speech.core.TerminalReason;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.LongSupplier;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Multi-segment, append-only, AES-GCM session journal for Speech Core v2.
 *
 * <p>All methods perform disk/Keystore work and must be called from a serialized worker, never the
 * IME main thread. A directory file lock linearizes different app processes. Each record has an
 * independent authentication tag, so a crash can truncate only the incomplete tail. Explicit
 * discard is compacted to an authenticated tombstone before earlier encrypted content is removed.
 */
public final class VoiceDraftJournal {
    private enum RecordType {
        METADATA,
        SEGMENT_OPENED,
        AUDIO_CHUNK,
        REVISION,
        SEGMENT_SEALED,
        DELIVERY,
        SESSION_ENDED,
        DISCARD,
        ACKNOWLEDGED
    }

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "opentypeless_speech_core_v2_journal_v1";
    private static final int FILE_MAGIC = 0x4f545632; // OTV2
    private static final int RECORD_MAGIC = 0x4f525632; // ORV2
    private static final int VERSION = 1;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / 8;
    private static final int MAX_SESSION_ID_BYTES = 256;
    private static final String EXTENSION = ".otv2";
    private static final Object PROCESS_LOCK = new Object();
    private static volatile SecretKey cachedKey;

    private final File directory;
    private final File directoryLock;
    private final SecretKey suppliedKey;
    private final JournalLimits limits;
    private final LongSupplier nowMillis;

    public VoiceDraftJournal(Context context) {
        this(
                new File(context.getNoBackupFilesDir(), "speech-core-v2-journal"),
                null,
                JournalLimits.DEFAULT,
                System::currentTimeMillis);
    }

    VoiceDraftJournal(
            File directory,
            SecretKey suppliedKey,
            JournalLimits limits,
            LongSupplier nowMillis) {
        this.directory = directory;
        this.directoryLock = new File(directory, ".journal.lock");
        this.suppliedKey = suppliedKey;
        this.limits = limits;
        this.nowMillis = nowMillis;
    }

    public Session startSession(JournalSessionMetadata metadata) {
        return locked(() -> {
            cleanupExpiredLocked(nowMillis.getAsLong());
            JournalToken token = new JournalToken(metadata.sessionId(), metadata.generation());
            File target = fileFor(token);
            if (target.exists()) {
                throw new IllegalStateException("journal session already exists");
            }
            if (journalFiles().size() >= limits.maxSessions()) {
                throw new IllegalStateException("recoverable session quota reached");
            }
            byte[] metadataPayload = payload(RecordType.METADATA, output -> {
                writeString(output, metadata.engineId(), JournalSessionMetadata.MAX_LABEL_BYTES);
                writeString(output, metadata.modelRevision(), JournalSessionMetadata.MAX_LABEL_BYTES);
                writeString(output, metadata.languageTag(), JournalSessionMetadata.MAX_LABEL_BYTES);
                output.writeInt(metadata.sampleRate());
            });
            long projected = headerSize(metadata.sessionId()) + recordSize(metadataPayload.length);
            enforceTotalQuota(projected);
            if (projected > limits.maxSessionBytes()) {
                throw new IllegalStateException("session metadata exceeds journal quota");
            }

            File temporary = temporaryFor(target);
            deleteIfPresent(temporary);
            Header header = new Header(token, metadata.createdAtMillis());
            try (RandomAccessFile file = new RandomAccessFile(temporary, "rw")) {
                writeHeader(file, header);
                appendEncrypted(file, header, 1L, metadataPayload);
            } finally {
                Arrays.fill(metadataPayload, (byte) 0);
            }
            atomicReplace(temporary, target);
            State state = scan(target, false, true);
            return new Session(this, token, fileFingerprint(target), state);
        });
    }

    public Session resume(JournalToken token) {
        return locked(() -> {
            File file = fileFor(token);
            if (!file.isFile()) return null;
            State state = scan(file, false, true);
            if (state.discarded || state.acknowledged) return null;
            return new Session(this, token, fileFingerprint(file), state);
        });
    }

    public JournalRecovery read(JournalToken token) {
        return locked(() -> {
            File file = fileFor(token);
            if (!file.isFile()) return null;
            State state = scan(file, true, true);
            if (state.discarded || state.acknowledged || state.metadata == null) return null;
            return state.recovery();
        });
    }

    public List<JournalRecovery> listRecoverable() {
        return locked(() -> {
            cleanupExpiredLocked(nowMillis.getAsLong());
            ArrayList<JournalRecovery> recoveries = new ArrayList<>();
            for (File file : journalFiles()) {
                try {
                    State state = scan(file, true, true);
                    if (!state.discarded && !state.acknowledged && state.metadata != null) {
                        recoveries.add(state.recovery());
                    }
                } catch (RuntimeException corrupt) {
                    quarantine(file);
                }
            }
            recoveries.sort(Comparator.comparingLong(
                    recovery -> recovery.metadata().createdAtMillis()));
            return List.copyOf(recoveries);
        });
    }

    public void cleanupExpired() {
        locked(() -> {
            cleanupExpiredLocked(nowMillis.getAsLong());
            return null;
        });
    }

    public static final class Session implements AutoCloseable {
        private final VoiceDraftJournal journal;
        private final JournalToken token;
        private byte[] cachedFingerprint;
        private State cachedState;
        private boolean closed;

        private Session(
                VoiceDraftJournal journal,
                JournalToken token,
                byte[] cachedFingerprint,
                State cachedState) {
            this.journal = journal;
            this.token = token;
            this.cachedFingerprint = cachedFingerprint;
            this.cachedState = cachedState;
        }

        public JournalToken token() {
            return token;
        }

        public synchronized JournalWriteResult openSegment(
                long segmentId, SegmentJoin joinBefore) {
            requireOpen();
            if (segmentId <= 0L || joinBefore == null) {
                return JournalWriteResult.REJECTED_CONFLICT;
            }
            return journal.mutate(this, state -> {
                if (state.ended) return Plan.result(JournalWriteResult.REJECTED_TERMINAL);
                SegmentState existing = state.segments.get(segmentId);
                if (existing != null) {
                    return existing.joinBefore == joinBefore
                            ? Plan.result(JournalWriteResult.IGNORED_DUPLICATE)
                            : Plan.result(JournalWriteResult.REJECTED_CONFLICT);
                }
                if (segmentId <= state.lastSegmentId()) {
                    return Plan.result(JournalWriteResult.REJECTED_STALE);
                }
                if (state.segments.size() >= journal.limits.maxSegmentsPerSession()) {
                    return Plan.result(JournalWriteResult.REJECTED_BOUNDS);
                }
                if (state.segments.isEmpty() && joinBefore != SegmentJoin.NONE) {
                    return Plan.result(JournalWriteResult.REJECTED_CONFLICT);
                }
                return Plan.write(journal.payload(RecordType.SEGMENT_OPENED, output -> {
                    output.writeLong(segmentId);
                    output.writeByte(joinBefore.ordinal());
                }));
            });
        }

        public synchronized JournalWriteResult appendAudioChunk(
                long segmentId,
                long chunkIndex,
                long startSample,
                int sampleRate,
                byte[] pcm16LittleEndian) {
            requireOpen();
            byte[] audio = pcm16LittleEndian == null ? new byte[0] : pcm16LittleEndian.clone();
            if (chunkIndex < 0L
                    || startSample < 0L
                    || sampleRate <= 0
                    || audio.length == 0
                    || (audio.length & 1) != 0
                    || audio.length > journal.limits.maxAudioChunkBytes()) {
                return JournalWriteResult.REJECTED_BOUNDS;
            }
            long audioSamples = audio.length / 2L;
            if (startSample > Long.MAX_VALUE - audioSamples) {
                Arrays.fill(audio, (byte) 0);
                return JournalWriteResult.REJECTED_BOUNDS;
            }
            try {
                return journal.mutate(this, state -> {
                    if (state.ended) return Plan.result(JournalWriteResult.REJECTED_TERMINAL);
                    SegmentState segment = state.segments.get(segmentId);
                    if (segment == null) return Plan.result(JournalWriteResult.REJECTED_CONFLICT);
                    if (state.metadata == null || sampleRate != state.metadata.sampleRate()) {
                        return Plan.result(JournalWriteResult.REJECTED_CONFLICT);
                    }
                    byte[] hash = sha256(audio);
                    if (chunkIndex < segment.lastChunkIndex) {
                        return Plan.result(JournalWriteResult.REJECTED_STALE);
                    }
                    if (chunkIndex == segment.lastChunkIndex) {
                        return Arrays.equals(hash, segment.lastChunkHash)
                                ? Plan.result(JournalWriteResult.IGNORED_DUPLICATE)
                                : Plan.result(JournalWriteResult.REJECTED_CONFLICT);
                    }
                    long expectedChunkIndex = segment.lastChunkIndex + 1L;
                    if (chunkIndex != expectedChunkIndex) {
                        return Plan.result(JournalWriteResult.REJECTED_CONFLICT);
                    }
                    if (segment.lastChunkIndex >= 0L && startSample != segment.lastAudioEndSample) {
                        return Plan.result(JournalWriteResult.REJECTED_CONFLICT);
                    }
                    return Plan.write(journal.payload(RecordType.AUDIO_CHUNK, output -> {
                        output.writeLong(segmentId);
                        output.writeLong(chunkIndex);
                        output.writeLong(startSample);
                        output.writeInt(sampleRate);
                        output.writeInt(audio.length);
                        output.write(audio);
                    }));
                });
            } finally {
                Arrays.fill(audio, (byte) 0);
            }
        }

        public synchronized JournalWriteResult appendRevision(SegmentRevision revision) {
            requireOpen();
            if (!revision.sessionId().equals(token.sessionId())) {
                return JournalWriteResult.REJECTED_CONFLICT;
            }
            byte[] encodedText = revision.fullText().getBytes(StandardCharsets.UTF_8);
            if (encodedText.length > journal.limits.maxTextBytes()) {
                return JournalWriteResult.REJECTED_BOUNDS;
            }
            Arrays.fill(encodedText, (byte) 0);
            return journal.mutate(this, state -> {
                SegmentState segment = state.segments.get(revision.segmentId());
                if (segment == null) return Plan.result(JournalWriteResult.REJECTED_CONFLICT);
                if (segment.latestRevision != null) {
                    long current = segment.latestRevision.revisionId();
                    if (revision.revisionId() < current) {
                        return Plan.result(JournalWriteResult.REJECTED_STALE);
                    }
                    if (revision.revisionId() == current) {
                        return samePersistentRevision(segment.latestRevision, revision)
                                ? Plan.result(JournalWriteResult.IGNORED_DUPLICATE)
                                : Plan.result(JournalWriteResult.REJECTED_CONFLICT);
                    }
                }
                return Plan.write(journal.payload(RecordType.REVISION, output -> {
                    output.writeLong(revision.segmentId());
                    output.writeLong(revision.revisionId());
                    output.writeByte(revision.stage().ordinal());
                    writeString(output, revision.fullText(), journal.limits.maxTextBytes());
                    output.writeLong(revision.audioStartMs());
                    output.writeLong(revision.audioEndMs());
                    output.writeByte(revision.origin().ordinal());
                    output.writeBoolean(revision.providerFinal());
                }));
            });
        }

        public synchronized JournalWriteResult sealSegment(long segmentId) {
            requireOpen();
            return journal.mutate(this, state -> {
                SegmentState segment = state.segments.get(segmentId);
                if (segment == null) return Plan.result(JournalWriteResult.REJECTED_CONFLICT);
                if (segment.sealed) return Plan.result(JournalWriteResult.IGNORED_DUPLICATE);
                if (segment.latestRevision == null || segment.latestRevision.fullText().isBlank()) {
                    return Plan.result(JournalWriteResult.REJECTED_CONFLICT);
                }
                return Plan.write(journal.payload(
                        RecordType.SEGMENT_SEALED, output -> output.writeLong(segmentId)));
            });
        }

        public synchronized JournalWriteResult updateDelivery(
                long segmentId, DeliveryState deliveryState) {
            requireOpen();
            if (deliveryState == null) return JournalWriteResult.REJECTED_CONFLICT;
            return journal.mutate(this, state -> {
                SegmentState segment = state.segments.get(segmentId);
                if (segment == null) return Plan.result(JournalWriteResult.REJECTED_CONFLICT);
                if (segment.deliveryState == deliveryState) {
                    return Plan.result(JournalWriteResult.IGNORED_DUPLICATE);
                }
                if (!deliveryTransitionAllowed(segment.deliveryState, deliveryState)) {
                    return Plan.result(JournalWriteResult.REJECTED_CONFLICT);
                }
                return Plan.write(journal.payload(RecordType.DELIVERY, output -> {
                    output.writeLong(segmentId);
                    output.writeByte(deliveryState.ordinal());
                }));
            });
        }

        public synchronized JournalWriteResult end(TerminalReason reason) {
            requireOpen();
            if (reason == null
                    || reason == TerminalReason.NONE
                    || reason == TerminalReason.EXPLICIT_DISCARD) {
                return JournalWriteResult.REJECTED_CONFLICT;
            }
            return journal.mutate(this, state -> {
                if (state.ended) {
                    return state.terminalReason == reason
                            ? Plan.result(JournalWriteResult.IGNORED_DUPLICATE)
                            : Plan.result(JournalWriteResult.REJECTED_CONFLICT);
                }
                return Plan.write(journal.payload(
                        RecordType.SESSION_ENDED,
                        output -> output.writeByte(reason.ordinal())));
            });
        }

        public synchronized JournalWriteResult discard() {
            requireOpen();
            return journal.discard(this);
        }

        /** Acknowledges successful recovery/insertion and removes the owned journal file. */
        public synchronized JournalWriteResult acknowledge() {
            requireOpen();
            return journal.acknowledge(this);
        }

        @Override
        public synchronized void close() {
            closed = true;
            cachedState = null;
            if (cachedFingerprint != null) Arrays.fill(cachedFingerprint, (byte) 0);
            cachedFingerprint = null;
        }

        private void requireOpen() {
            if (closed) throw new IllegalStateException("journal session handle is closed");
        }
    }

    private JournalWriteResult mutate(Session session, Mutation mutation) {
        return locked(() -> {
            File file = fileFor(session.token);
            if (!file.isFile()) return JournalWriteResult.REJECTED_TERMINAL;
            State state = currentState(session, file);
            if (state.discarded || state.acknowledged) {
                return JournalWriteResult.REJECTED_TERMINAL;
            }
            Plan plan = mutation.plan(state);
            if (plan.result != null) return plan.result;
            if (state.recordCount >= limits.maxRecordsPerSession()) {
                return JournalWriteResult.REJECTED_BOUNDS;
            }
            byte[] payload = plan.payload;
            if (payload.length > maximumPayloadBytes()) {
                return JournalWriteResult.REJECTED_BOUNDS;
            }
            long addition = recordSize(payload.length);
            if (file.length() + addition > limits.maxSessionBytes()
                    || totalJournalBytes() + addition > limits.maxTotalBytes()) {
                return JournalWriteResult.REJECTED_BOUNDS;
            }
            Header header = state.header;
            long sequence = state.lastRecordSequence + 1L;
            try {
                try (RandomAccessFile target = new RandomAccessFile(file, "rw")) {
                    target.seek(target.length());
                    appendEncrypted(target, header, sequence, payload);
                }
                applyPayload(state, payload, false);
            } finally {
                Arrays.fill(payload, (byte) 0);
            }
            state.lastRecordSequence = sequence;
            state.recordCount++;
            session.cachedFingerprint = fileFingerprint(file);
            session.cachedState = state;
            return JournalWriteResult.WRITTEN;
        });
    }

    private JournalWriteResult discard(Session session) {
        return locked(() -> {
            File file = fileFor(session.token);
            if (!file.isFile()) return JournalWriteResult.REJECTED_TERMINAL;
            State state = currentState(session, file);
            if (state.discarded) return JournalWriteResult.IGNORED_DUPLICATE;
            if (state.acknowledged) return JournalWriteResult.REJECTED_TERMINAL;
            byte[] tombstone = payload(RecordType.DISCARD, output -> {});
            try {
                try (RandomAccessFile target = new RandomAccessFile(file, "rw")) {
                    target.seek(target.length());
                    appendEncrypted(
                            target, state.header, state.lastRecordSequence + 1L, tombstone);
                }
                compactToTombstone(file, state.header, tombstone);
            } finally {
                Arrays.fill(tombstone, (byte) 0);
            }
            State compacted = scan(file, false, true);
            session.cachedFingerprint = fileFingerprint(file);
            session.cachedState = compacted;
            return JournalWriteResult.WRITTEN;
        });
    }

    private JournalWriteResult acknowledge(Session session) {
        return locked(() -> {
            File file = fileFor(session.token);
            if (!file.isFile()) return JournalWriteResult.REJECTED_TERMINAL;
            State state = currentState(session, file);
            if (state.discarded || state.acknowledged) {
                return JournalWriteResult.REJECTED_TERMINAL;
            }
            byte[] acknowledgement = payload(RecordType.ACKNOWLEDGED, output -> {});
            try {
                try (RandomAccessFile target = new RandomAccessFile(file, "rw")) {
                    target.seek(target.length());
                    appendEncrypted(
                            target, state.header, state.lastRecordSequence + 1L, acknowledgement);
                }
            } finally {
                Arrays.fill(acknowledgement, (byte) 0);
            }
            if (!file.delete()) {
                throw new IllegalStateException("unable to remove acknowledged journal");
            }
            if (session.cachedFingerprint != null) {
                Arrays.fill(session.cachedFingerprint, (byte) 0);
            }
            session.cachedFingerprint = null;
            session.cachedState = null;
            return JournalWriteResult.WRITTEN;
        });
    }

    private State currentState(Session session, File file) throws IOException {
        byte[] fingerprint = fileFingerprint(file);
        if (session.cachedState != null
                && Arrays.equals(session.cachedFingerprint, fingerprint)) {
            Arrays.fill(fingerprint, (byte) 0);
            return session.cachedState;
        }
        State state = scan(file, false, true);
        if (!state.header.token.equals(session.token)) {
            throw new IllegalStateException("journal generation mismatch");
        }
        if (session.cachedFingerprint != null) {
            Arrays.fill(session.cachedFingerprint, (byte) 0);
        }
        session.cachedFingerprint = fileFingerprint(file);
        session.cachedState = state;
        Arrays.fill(fingerprint, (byte) 0);
        return state;
    }

    private State scan(File file, boolean includeAudio, boolean repairTail) {
        try (RandomAccessFile input = new RandomAccessFile(file, repairTail ? "rw" : "r")) {
            Header header = readHeader(input);
            if (!file.getName().equals(fileName(header.token))) {
                throw new IOException("journal filename does not match authenticated generation");
            }
            State state = new State(header);
            long expectedSequence = 1L;
            while (input.getFilePointer() < input.length()) {
                long recordStart = input.getFilePointer();
                try {
                    int magic = input.readInt();
                    if (magic != RECORD_MAGIC) throw new IOException("invalid journal record magic");
                    long sequence = input.readLong();
                    if (sequence != expectedSequence) {
                        throw new IOException("journal record sequence is not monotonic");
                    }
                    int ivLength = input.readInt();
                    int ciphertextLength = input.readInt();
                    if (ivLength != IV_BYTES
                            || ciphertextLength < TAG_BYTES
                            || ciphertextLength > maximumPayloadBytes() + TAG_BYTES) {
                        throw new IOException("invalid journal record bounds");
                    }
                    long remaining = input.length() - input.getFilePointer();
                    if (remaining < (long) ivLength + ciphertextLength) {
                        if (!repairTail) throw new EOFException("truncated journal record");
                        input.setLength(recordStart);
                        input.getFD().sync();
                        break;
                    }
                    byte[] iv = new byte[ivLength];
                    byte[] ciphertext = new byte[ciphertextLength];
                    input.readFully(iv);
                    input.readFully(ciphertext);
                    byte[] clear = null;
                    try {
                        clear = decrypt(header, sequence, iv, ciphertext);
                        applyPayload(state, clear, includeAudio);
                    } finally {
                        if (clear != null) Arrays.fill(clear, (byte) 0);
                        Arrays.fill(ciphertext, (byte) 0);
                    }
                    state.lastRecordSequence = sequence;
                    state.recordCount++;
                    expectedSequence++;
                } catch (EOFException truncated) {
                    if (!repairTail) throw truncated;
                    input.setLength(recordStart);
                    input.getFD().sync();
                    break;
                }
            }
            return state;
        } catch (Exception error) {
            throw new IllegalStateException("unable to read protected Speech Core journal", error);
        }
    }

    private void applyPayload(State state, byte[] payload, boolean includeAudio) throws IOException {
        try (DataInputStream input =
                new DataInputStream(new ByteArrayInputStream(payload))) {
            int typeIndex = input.readUnsignedByte();
            if (typeIndex >= RecordType.values().length) throw new IOException("unknown record type");
            RecordType type = RecordType.values()[typeIndex];
            if ((state.discarded || state.acknowledged) && state.recordCount > 0) {
                throw new IOException("record follows terminal tombstone");
            }
            if (state.metadata == null
                    && type != RecordType.METADATA
                    && type != RecordType.DISCARD) {
                throw new IOException("record precedes session metadata");
            }
            switch (type) {
                case METADATA -> {
                    if (state.metadata != null || state.recordCount != 0) {
                        throw new IOException("duplicate or misplaced metadata record");
                    }
                    state.metadata = new JournalSessionMetadata(
                            state.header.token.sessionId(),
                            state.header.token.generation(),
                            state.header.createdAtMillis,
                            readString(input, JournalSessionMetadata.MAX_LABEL_BYTES),
                            readString(input, JournalSessionMetadata.MAX_LABEL_BYTES),
                            readString(input, JournalSessionMetadata.MAX_LABEL_BYTES),
                            input.readInt());
                }
                case SEGMENT_OPENED -> {
                    if (state.ended) throw new IOException("segment opened after session end");
                    long segmentId = input.readLong();
                    SegmentJoin join = enumValue(SegmentJoin.values(), input.readUnsignedByte());
                    if (segmentId <= state.lastSegmentId()
                            || state.segments.size() >= limits.maxSegmentsPerSession()) {
                        throw new IOException("invalid segment ordering or count");
                    }
                    state.segments.put(segmentId, new SegmentState(segmentId, join));
                }
                case AUDIO_CHUNK -> {
                    if (state.ended) throw new IOException("audio appended after session end");
                    long segmentId = input.readLong();
                    long chunkIndex = input.readLong();
                    long startSample = input.readLong();
                    int sampleRate = input.readInt();
                    int length = input.readInt();
                    if (length <= 0
                            || length > limits.maxAudioChunkBytes()
                            || (length & 1) != 0) {
                        throw new IOException("invalid audio chunk size");
                    }
                    byte[] audio = new byte[length];
                    input.readFully(audio);
                    SegmentState segment = requireSegment(state, segmentId);
                    if (sampleRate != state.metadata.sampleRate()
                            || chunkIndex != segment.lastChunkIndex + 1L
                            || (segment.lastChunkIndex >= 0L
                                    && startSample != segment.lastAudioEndSample)) {
                        throw new IOException("invalid audio chunk order");
                    }
                    segment.lastChunkIndex = chunkIndex;
                    segment.lastChunkHash = sha256(audio);
                    try {
                        segment.lastAudioEndSample = Math.addExact(startSample, length / 2L);
                    } catch (ArithmeticException overflow) {
                        throw new IOException("audio sample span overflow", overflow);
                    }
                    if (includeAudio) {
                        segment.audioChunks.add(new JournalAudioChunk(
                                chunkIndex, startSample, sampleRate, audio));
                    }
                    Arrays.fill(audio, (byte) 0);
                }
                case REVISION -> {
                    long segmentId = input.readLong();
                    long revisionId = input.readLong();
                    RevisionStage stage =
                            enumValue(RevisionStage.values(), input.readUnsignedByte());
                    String text = readString(input, limits.maxTextBytes());
                    long audioStartMs = input.readLong();
                    long audioEndMs = input.readLong();
                    RevisionOrigin origin =
                            enumValue(RevisionOrigin.values(), input.readUnsignedByte());
                    boolean providerFinal = input.readBoolean();
                    SegmentState segment = requireSegment(state, segmentId);
                    if (segment.latestRevision != null
                            && revisionId <= segment.latestRevision.revisionId()) {
                        throw new IOException("invalid revision order");
                    }
                    segment.latestRevision = SegmentRevision.text(
                            state.header.token.sessionId(),
                            segmentId,
                            revisionId,
                            stage,
                            text,
                            origin,
                            providerFinal);
                    if (audioStartMs != SegmentRevision.UNKNOWN_AUDIO_TIME) {
                        segment.latestRevision = new SegmentRevision(
                                state.header.token.sessionId(),
                                segmentId,
                                revisionId,
                                stage,
                                text,
                                List.of(),
                                audioStartMs,
                                audioEndMs,
                                origin,
                                providerFinal);
                    }
                }
                case SEGMENT_SEALED -> requireSegment(state, input.readLong()).sealed = true;
                case DELIVERY -> {
                    SegmentState segment = requireSegment(state, input.readLong());
                    segment.deliveryState =
                            enumValue(DeliveryState.values(), input.readUnsignedByte());
                }
                case SESSION_ENDED -> {
                    state.ended = true;
                    state.terminalReason =
                            enumValue(TerminalReason.values(), input.readUnsignedByte());
                }
                case DISCARD -> {
                    state.discarded = true;
                    state.segments.clear();
                }
                case ACKNOWLEDGED -> state.acknowledged = true;
            }
            if (input.read() != -1) throw new IOException("trailing journal payload bytes");
        }
    }

    private void compactToTombstone(File target, Header header, byte[] tombstone) throws Exception {
        File temporary = temporaryFor(target);
        deleteIfPresent(temporary);
        try (RandomAccessFile file = new RandomAccessFile(temporary, "rw")) {
            writeHeader(file, header);
            appendEncrypted(file, header, 1L, tombstone);
        }
        atomicReplace(temporary, target);
    }

    private void appendEncrypted(
            RandomAccessFile file, Header header, long sequence, byte[] payload) throws Exception {
        if (payload.length > maximumPayloadBytes()) {
            throw new IllegalArgumentException("journal record exceeds payload limit");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        // AndroidKeyStore keys with randomized encryption enabled intentionally reject a
        // caller-provided GCM IV. Let the provider generate the nonce for every record and persist
        // it beside the ciphertext. This keeps the strongest Keystore policy and also avoids IV
        // reuse across process restarts.
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length != IV_BYTES) {
            throw new IllegalStateException("AES-GCM provider returned an invalid IV");
        }
        cipher.updateAAD(aad(header, sequence));
        byte[] ciphertext = cipher.doFinal(payload);
        file.writeInt(RECORD_MAGIC);
        file.writeLong(sequence);
        file.writeInt(iv.length);
        file.writeInt(ciphertext.length);
        file.write(iv);
        file.write(ciphertext);
        file.getFD().sync();
        Arrays.fill(ciphertext, (byte) 0);
    }

    private byte[] decrypt(Header header, long sequence, byte[] iv, byte[] ciphertext)
            throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(aad(header, sequence));
        return cipher.doFinal(ciphertext);
    }

    private byte[] aad(Header header, long sequence) throws IOException {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(FILE_MAGIC);
            output.writeInt(VERSION);
            writeString(output, header.token.sessionId().value(), MAX_SESSION_ID_BYTES);
            output.writeLong(header.token.generation());
            output.writeLong(header.createdAtMillis);
            output.writeLong(sequence);
            output.flush();
            return bytes.toByteArray();
        }
    }

    private byte[] payload(RecordType type, PayloadWriter writer) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeByte(type.ordinal());
            writer.write(output);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException("unable to encode journal record", error);
        }
    }

    private void writeHeader(RandomAccessFile output, Header header) throws IOException {
        output.writeInt(FILE_MAGIC);
        output.writeInt(VERSION);
        byte[] session = header.token.sessionId().value().getBytes(StandardCharsets.UTF_8);
        if (session.length == 0 || session.length > MAX_SESSION_ID_BYTES) {
            throw new IOException("invalid journal session id length");
        }
        output.writeInt(session.length);
        output.write(session);
        output.writeLong(header.token.generation());
        output.writeLong(header.createdAtMillis);
    }

    private Header readHeader(RandomAccessFile input) throws IOException {
        input.seek(0L);
        if (input.readInt() != FILE_MAGIC || input.readInt() != VERSION) {
            throw new IOException("invalid Speech Core journal header");
        }
        int length = input.readInt();
        if (length <= 0 || length > MAX_SESSION_ID_BYTES) {
            throw new IOException("invalid journal session id length");
        }
        byte[] encoded = new byte[length];
        input.readFully(encoded);
        String sessionValue = new String(encoded, StandardCharsets.UTF_8);
        Arrays.fill(encoded, (byte) 0);
        long generation = input.readLong();
        long createdAt = input.readLong();
        return new Header(new JournalToken(SessionId.of(sessionValue), generation), createdAt);
    }

    private void cleanupExpiredLocked(long now) {
        for (File file : journalFiles()) {
            try {
                State state = scan(file, false, true);
                Header header = state.header;
                long age = Math.max(0L, now - header.createdAtMillis);
                if (age > limits.recoveryTtlMs()) {
                    deleteIfPresent(file);
                    continue;
                }
                if ((state.discarded || state.acknowledged)
                        && age > limits.discardTombstoneTtlMs()) {
                    deleteIfPresent(file);
                }
            } catch (RuntimeException corrupt) {
                quarantine(file);
            }
        }
        File[] temporary = directory.listFiles(
                file -> file.isFile() && file.getName().contains(EXTENSION + ".tmp"));
        if (temporary != null) {
            for (File file : temporary) deleteIfPresent(file);
        }
    }

    private List<File> journalFiles() {
        File[] files = directory.listFiles(
                file -> file.isFile() && file.getName().endsWith(EXTENSION));
        if (files == null) return List.of();
        return Arrays.asList(files);
    }

    private long totalJournalBytes() {
        long total = 0L;
        for (File file : journalFiles()) total = Math.addExact(total, file.length());
        return total;
    }

    private void enforceTotalQuota(long addition) {
        if (totalJournalBytes() + addition > limits.maxTotalBytes()) {
            throw new IllegalStateException("Speech Core journal storage quota reached");
        }
    }

    private File fileFor(JournalToken token) {
        return new File(directory, fileName(token));
    }

    private static String fileName(JournalToken token) {
        byte[] digest = sha256((token.sessionId().value() + "\u0000" + token.generation())
                .getBytes(StandardCharsets.UTF_8));
        StringBuilder name = new StringBuilder(digest.length * 2 + EXTENSION.length());
        for (byte value : digest) name.append(String.format(java.util.Locale.ROOT, "%02x", value));
        Arrays.fill(digest, (byte) 0);
        return name.append(EXTENSION).toString();
    }

    private static File temporaryFor(File target) {
        return new File(target.getParentFile(), target.getName() + ".tmp");
    }

    private void quarantine(File file) {
        File quarantine = new File(
                directory, file.getName() + ".corrupt." + Math.max(0L, nowMillis.getAsLong()));
        try {
            Files.move(file.toPath(), quarantine.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // Leave the unreadable encrypted file in place; future safe sessions use another id.
        }
    }

    private <T> T locked(LockedOperation<T> operation) {
        synchronized (PROCESS_LOCK) {
            try {
                if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
                    throw new IOException("unable to create Speech Core journal directory");
                }
                try (RandomAccessFile lockFile = new RandomAccessFile(directoryLock, "rw");
                        java.nio.channels.FileLock ignored = lockFile.getChannel().lock()) {
                    return operation.run();
                }
            } catch (RuntimeException error) {
                throw error;
            } catch (Exception error) {
                throw new IllegalStateException("Speech Core journal operation failed", error);
            }
        }
    }

    private SecretKey key() throws Exception {
        return suppliedKey == null ? getOrCreateKey() : suppliedKey;
    }

    private static synchronized SecretKey getOrCreateKey() throws Exception {
        SecretKey cached = cachedKey;
        if (cached != null) return cached;
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        SecretKey existing = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        if (existing != null) {
            cachedKey = existing;
            return existing;
        }
        KeyGenerator generator =
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        cachedKey = generator.generateKey();
        return cachedKey;
    }

    private int maximumPayloadBytes() {
        return Math.max(limits.maxAudioChunkBytes(), limits.maxTextBytes()) + 4_096;
    }

    private static long recordSize(int payloadLength) {
        return 4L + 8L + 4L + 4L + IV_BYTES + payloadLength + TAG_BYTES;
    }

    private static long headerSize(SessionId sessionId) {
        return 4L
                + 4L
                + 4L
                + sessionId.value().getBytes(StandardCharsets.UTF_8).length
                + 8L
                + 8L;
    }

    private static void atomicReplace(File source, File target) throws IOException {
        try {
            Files.move(
                    source.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeString(DataOutputStream output, String value, int maximumBytes)
            throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length < 0 || encoded.length > maximumBytes) {
            throw new IOException("journal string exceeds bound");
        }
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static String readString(DataInputStream input, int maximumBytes) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maximumBytes) {
            throw new IOException("journal string exceeds bound");
        }
        byte[] encoded = new byte[length];
        input.readFully(encoded);
        String value = new String(encoded, StandardCharsets.UTF_8);
        Arrays.fill(encoded, (byte) 0);
        return value;
    }

    private static <T> T enumValue(T[] values, int index) throws IOException {
        if (index < 0 || index >= values.length) throw new IOException("invalid enum value");
        return values[index];
    }

    private static SegmentState requireSegment(State state, long segmentId) throws IOException {
        SegmentState segment = state.segments.get(segmentId);
        if (segment == null) throw new IOException("journal segment is missing");
        return segment;
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static boolean samePersistentRevision(
            SegmentRevision left, SegmentRevision right) {
        return left.sessionId().equals(right.sessionId())
                && left.segmentId() == right.segmentId()
                && left.revisionId() == right.revisionId()
                && left.stage() == right.stage()
                && left.fullText().equals(right.fullText())
                && left.audioStartMs() == right.audioStartMs()
                && left.audioEndMs() == right.audioEndMs()
                && left.origin() == right.origin()
                && left.providerFinal() == right.providerFinal();
    }

    private static boolean deliveryTransitionAllowed(
            DeliveryState current, DeliveryState requested) {
        return switch (current) {
            case NOT_PROJECTED -> requested == DeliveryState.COMPOSING
                    || requested == DeliveryState.RECOVERABLE;
            case COMPOSING -> requested == DeliveryState.FROZEN
                    || requested == DeliveryState.COMMITTED
                    || requested == DeliveryState.RECOVERABLE;
            case FROZEN -> requested == DeliveryState.RECOVERABLE;
            case RECOVERABLE -> requested == DeliveryState.COMMITTED;
            case COMMITTED -> false;
        };
    }

    private static byte[] fileFingerprint(File file) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long length = input.length();
            int tailLength = (int) Math.min(128L, length);
            byte[] material = new byte[Long.BYTES + tailLength];
            ByteBuffer.wrap(material).putLong(length);
            if (tailLength > 0) {
                input.seek(length - tailLength);
                input.readFully(material, Long.BYTES, tailLength);
            }
            byte[] fingerprint = sha256(material);
            Arrays.fill(material, (byte) 0);
            return fingerprint;
        }
    }

    private static void deleteIfPresent(File file) {
        if (file.exists() && !file.delete()) {
            throw new IllegalStateException("unable to remove stale journal file");
        }
    }

    private record Header(JournalToken token, long createdAtMillis) {}

    private static final class SegmentState {
        final long segmentId;
        final SegmentJoin joinBefore;
        final ArrayList<JournalAudioChunk> audioChunks = new ArrayList<>();
        long lastChunkIndex = -1L;
        long lastAudioEndSample;
        byte[] lastChunkHash = new byte[0];
        SegmentRevision latestRevision;
        boolean sealed;
        DeliveryState deliveryState = DeliveryState.NOT_PROJECTED;

        SegmentState(long segmentId, SegmentJoin joinBefore) {
            this.segmentId = segmentId;
            this.joinBefore = joinBefore;
        }
    }

    private static final class State {
        final Header header;
        final TreeMap<Long, SegmentState> segments = new TreeMap<>();
        JournalSessionMetadata metadata;
        long lastRecordSequence;
        int recordCount;
        boolean discarded;
        boolean acknowledged;
        boolean ended;
        TerminalReason terminalReason = TerminalReason.NONE;

        State(Header header) {
            this.header = header;
        }

        long lastSegmentId() {
            return segments.isEmpty() ? 0L : segments.lastKey();
        }

        JournalRecovery recovery() {
            ArrayList<JournalSegmentRecovery> recoveredSegments =
                    new ArrayList<>(segments.size());
            for (Map.Entry<Long, SegmentState> entry : segments.entrySet()) {
                SegmentState segment = entry.getValue();
                recoveredSegments.add(new JournalSegmentRecovery(
                        segment.segmentId,
                        segment.joinBefore,
                        segment.audioChunks,
                        Optional.ofNullable(segment.latestRevision),
                        segment.sealed,
                        segment.deliveryState));
            }
            return new JournalRecovery(
                    header.token,
                    metadata,
                    recoveredSegments,
                    ended,
                    terminalReason);
        }
    }

    private record Plan(JournalWriteResult result, byte[] payload) {
        static Plan result(JournalWriteResult result) {
            return new Plan(result, null);
        }

        static Plan write(byte[] payload) {
            return new Plan(null, payload);
        }
    }

    @FunctionalInterface
    private interface Mutation {
        Plan plan(State state) throws Exception;
    }

    @FunctionalInterface
    private interface PayloadWriter {
        void write(DataOutputStream output) throws IOException;
    }

    @FunctionalInterface
    private interface LockedOperation<T> {
        T run() throws Exception;
    }
}
