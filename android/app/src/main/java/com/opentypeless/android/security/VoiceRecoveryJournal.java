package com.opentypeless.android.security;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Crash-safe, no-backup storage for one captured utterance or its completed transcript.
 *
 * <p>The journal is deliberately single-slot. Starting another recording while an unresolved
 * entry exists would make it possible to overwrite the only recoverable copy of the earlier
 * utterance. The random entry id is non-secret and remains in the file header so a late callback
 * can acknowledge only its own entry; all user content and route metadata is AES-GCM protected.
 */
public final class VoiceRecoveryJournal {
    public enum Kind { AUDIO, COMPLETED_TEXT }

    public record Entry(
            String id,
            Kind kind,
            String backend,
            String language,
            String endpoint,
            String model,
            long createdAtMillis,
            long durationMs,
            boolean reachedLimit,
            boolean autoStopped,
            byte[] wav,
            String completedText) {
        public Entry {
            id = safe(id);
            kind = kind == null ? Kind.AUDIO : kind;
            backend = safe(backend);
            language = safe(language);
            endpoint = safe(endpoint);
            model = safe(model);
            wav = wav == null ? new byte[0] : wav;
            completedText = safe(completedText);
        }

        @Override
        public byte[] wav() {
            return wav.clone();
        }

        private byte[] rawWav() {
            return wav;
        }

        private static String safe(String value) {
            return value == null ? "" : value;
        }
    }

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "opentypeless_voice_recovery_v1";
    private static final byte[] AAD_PREFIX =
            "OpenTypelessVoiceRecovery:v1:".getBytes(StandardCharsets.UTF_8);
    private static final int MAGIC = 0x4f545652; // OTVR
    private static final int VERSION = 1;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MAX_ID_BYTES = 96;
    private static final int MAX_BACKEND_BYTES = 96;
    private static final int MAX_LANGUAGE_BYTES = 96;
    private static final int MAX_ENDPOINT_BYTES = 2_048;
    private static final int MAX_MODEL_BYTES = 256;
    private static final int MAX_AUDIO_BYTES = 32 * 1024 * 1024;
    private static final int MAX_TEXT_BYTES = 256 * 1024;
    private static final Object PROCESS_LOCK = new Object();
    private static volatile SecretKey cachedKey;

    private final File directory;
    private final File pending;
    private final File temporary;
    private final SecretKey suppliedKey;

    public VoiceRecoveryJournal(Context context) {
        this(new File(context.getNoBackupFilesDir(), "voice-recovery"), null);
    }

    VoiceRecoveryJournal(File directory, SecretKey suppliedKey) {
        this.directory = directory;
        this.pending = new File(directory, "pending.otvr");
        this.temporary = new File(directory, "pending.tmp");
        this.suppliedKey = suppliedKey;
    }

    public boolean hasPending() {
        synchronized (PROCESS_LOCK) {
            return pending.isFile();
        }
    }

    public String pendingId() {
        synchronized (PROCESS_LOCK) {
            if (!pending.isFile()) return "";
            return readHeader(pending).id();
        }
    }

    public boolean saveAudio(
            String id,
            String backend,
            String language,
            String endpoint,
            String model,
            long createdAtMillis,
            long durationMs,
            boolean reachedLimit,
            boolean autoStopped,
            byte[] wav) {
        return saveAudioIfAccepted(
                id, backend, language, endpoint, model, createdAtMillis, durationMs,
                reachedLimit, autoStopped, wav, () -> true);
    }

    /**
     * Atomically refuses or removes a checkpoint when an explicit discard races the disk write.
     * The predicate is checked both before and after the authenticated atomic replacement while
     * holding the same process lock used by {@link #discard(String)}.
     */
    public boolean saveAudioIfAccepted(
            String id,
            String backend,
            String language,
            String endpoint,
            String model,
            long createdAtMillis,
            long durationMs,
            boolean reachedLimit,
            boolean autoStopped,
            byte[] wav,
            BooleanSupplier accepted) {
        if (accepted == null) throw new IllegalArgumentException("Recovery acceptance is required");
        byte[] safeAudio = wav == null ? new byte[0] : wav;
        if (safeAudio.length == 0 || safeAudio.length > MAX_AUDIO_BYTES) {
            throw new IllegalArgumentException("Recoverable audio has an invalid size");
        }
        Entry entry = new Entry(
                requireId(id), Kind.AUDIO, backend, language, endpoint, model,
                createdAtMillis, durationMs,
                reachedLimit, autoStopped, safeAudio, "");
        synchronized (PROCESS_LOCK) {
            if (!accepted.getAsBoolean()) return false;
            if (pending.exists()) {
                throw new IllegalStateException("A recoverable recording is already waiting");
            }
            writeAtomically(entry);
            if (!accepted.getAsBoolean()) {
                if (!deletePending()) {
                    throw new IllegalStateException(
                            "Unable to remove a discarded voice recovery checkpoint");
                }
                return false;
            }
            return true;
        }
    }

    /** Atomically replaces captured audio with the authoritative completed text. */
    public void complete(
            String id,
            String backend,
            String language,
            String endpoint,
            String model,
            long createdAtMillis,
            long durationMs,
            boolean reachedLimit,
            boolean autoStopped,
            String completedText) {
        String safeText = completedText == null ? "" : completedText;
        byte[] encoded = safeText.getBytes(StandardCharsets.UTF_8);
        if (encoded.length == 0 || encoded.length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("Recoverable transcript has an invalid size");
        }
        Arrays.fill(encoded, (byte) 0);
        Entry entry = new Entry(
                requireId(id), Kind.COMPLETED_TEXT, backend, language, endpoint, model,
                createdAtMillis, durationMs,
                reachedLimit, autoStopped, new byte[0], safeText);
        synchronized (PROCESS_LOCK) {
            requireMatchingPending(id);
            writeAtomically(entry);
        }
    }

    public Entry read() {
        synchronized (PROCESS_LOCK) {
            if (!pending.isFile()) return null;
            try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                    new FileInputStream(pending)))) {
                Header header = readHeader(input);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, header.iv()));
                cipher.updateAAD(aad(header.id()));
                try (DataInputStream clear = new DataInputStream(
                        new CipherInputStream(input, cipher))) {
                    int kindValue = clear.readUnsignedByte();
                    if (kindValue < 0 || kindValue >= Kind.values().length) {
                        throw new IOException("Unknown recovery payload kind");
                    }
                    Kind kind = Kind.values()[kindValue];
                    String backend = readString(clear, MAX_BACKEND_BYTES);
                    String language = readString(clear, MAX_LANGUAGE_BYTES);
                    String endpoint = readString(clear, MAX_ENDPOINT_BYTES);
                    String model = readString(clear, MAX_MODEL_BYTES);
                    long createdAt = clear.readLong();
                    long duration = clear.readLong();
                    boolean reachedLimit = clear.readBoolean();
                    boolean autoStopped = clear.readBoolean();
                    int contentLength = clear.readInt();
                    int maximum = kind == Kind.AUDIO ? MAX_AUDIO_BYTES : MAX_TEXT_BYTES;
                    if (contentLength <= 0 || contentLength > maximum) {
                        throw new IOException("Invalid recovery payload length");
                    }
                    byte[] content = new byte[contentLength];
                    clear.readFully(content);
                    // Reading to authenticated EOF is mandatory for AES-GCM tag verification.
                    if (clear.read() != -1) throw new IOException("Trailing recovery payload data");
                    if (kind == Kind.AUDIO) {
                        return new Entry(
                                header.id(), kind, backend, language, endpoint, model,
                                createdAt, duration,
                                reachedLimit, autoStopped, content, "");
                    }
                    String text = new String(content, StandardCharsets.UTF_8);
                    Arrays.fill(content, (byte) 0);
                    return new Entry(
                            header.id(), kind, backend, language, endpoint, model,
                            createdAt, duration,
                            reachedLimit, autoStopped, new byte[0], text);
                }
            } catch (Exception error) {
                throw new IllegalStateException("Unable to read protected voice recovery", error);
            }
        }
    }

    /** Deletes only the entry owned by {@code id}; a late callback cannot remove a newer entry. */
    public boolean discard(String id) {
        synchronized (PROCESS_LOCK) {
            if (!pending.isFile()) return false;
            if (!readHeader(pending).id().equals(id)) return false;
            return deletePending();
        }
    }

    public boolean discardAny() {
        synchronized (PROCESS_LOCK) {
            return !pending.exists() || deletePending();
        }
    }

    File pendingFileForTest() {
        return pending;
    }

    private void writeAtomically(Entry entry) {
        ensureDirectory();
        if (temporary.exists() && !temporary.delete()) {
            throw new IllegalStateException("Unable to replace temporary voice recovery");
        }
        byte[] iv = new byte[0];
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            // Android Keystore keys with randomized encryption reject caller-provided IVs.
            // Let the provider generate the nonce and persist exactly the returned value.
            cipher.init(Cipher.ENCRYPT_MODE, key());
            iv = cipher.getIV();
            if (iv == null || iv.length != IV_BYTES) {
                throw new IllegalStateException("Invalid generated voice recovery IV");
            }
            cipher.updateAAD(aad(entry.id()));
            try (FileOutputStream file = new FileOutputStream(temporary);
                 BufferedOutputStream buffered = new BufferedOutputStream(file);
                 DataOutputStream header = new DataOutputStream(buffered)) {
                header.writeInt(MAGIC);
                header.writeInt(VERSION);
                writeString(header, entry.id(), MAX_ID_BYTES);
                header.writeInt(iv.length);
                header.write(iv);
                header.flush();
                try (DataOutputStream encrypted = new DataOutputStream(
                        new CipherOutputStream(buffered, cipher))) {
                    encrypted.writeByte(entry.kind().ordinal());
                    writeString(encrypted, entry.backend(), MAX_BACKEND_BYTES);
                    writeString(encrypted, entry.language(), MAX_LANGUAGE_BYTES);
                    writeString(encrypted, entry.endpoint(), MAX_ENDPOINT_BYTES);
                    writeString(encrypted, entry.model(), MAX_MODEL_BYTES);
                    encrypted.writeLong(entry.createdAtMillis());
                    encrypted.writeLong(entry.durationMs());
                    encrypted.writeBoolean(entry.reachedLimit());
                    encrypted.writeBoolean(entry.autoStopped());
                    byte[] content = entry.kind() == Kind.AUDIO
                            ? entry.rawWav()
                            : entry.completedText().getBytes(StandardCharsets.UTF_8);
                    encrypted.writeInt(content.length);
                    encrypted.write(content);
                    if (entry.kind() != Kind.AUDIO) Arrays.fill(content, (byte) 0);
                }
            }
            // CipherOutputStream has closed the first descriptor; reopen solely to fsync the file.
            try (FileOutputStream sync = new FileOutputStream(temporary, true)) {
                sync.getFD().sync();
            }
            moveIntoPlace();
        } catch (Exception error) {
            if (temporary.exists()) temporary.delete();
            throw new IllegalStateException("Unable to protect captured voice audio", error);
        } finally {
            Arrays.fill(iv, (byte) 0);
        }
    }

    private void moveIntoPlace() throws IOException {
        try {
            Files.move(
                    temporary.toPath(),
                    pending.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(
                    temporary.toPath(),
                    pending.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void requireMatchingPending(String id) {
        if (!pending.isFile() || !readHeader(pending).id().equals(id)) {
            throw new IllegalStateException("The recoverable recording has changed");
        }
    }

    private boolean deletePending() {
        if (temporary.exists()) temporary.delete();
        return !pending.exists() || pending.delete();
    }

    private void ensureDirectory() {
        if ((!directory.isDirectory() && !directory.mkdirs()) || !directory.isDirectory()) {
            throw new IllegalStateException("Unable to create voice recovery storage");
        }
    }

    private Header readHeader(File file) {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                new FileInputStream(file)))) {
            return readHeader(input);
        } catch (IOException error) {
            throw new IllegalStateException("Unable to read voice recovery header", error);
        }
    }

    private Header readHeader(DataInputStream input) throws IOException {
        if (input.readInt() != MAGIC || input.readInt() != VERSION) {
            throw new IOException("Unsupported voice recovery format");
        }
        String id = readString(input, MAX_ID_BYTES);
        int ivLength = input.readInt();
        if (ivLength != IV_BYTES) throw new IOException("Invalid voice recovery IV");
        byte[] iv = new byte[ivLength];
        input.readFully(iv);
        return new Header(requireId(id), iv);
    }

    private static void writeString(DataOutputStream output, String value, int maximumBytes)
            throws IOException {
        byte[] encoded = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (encoded.length > maximumBytes) throw new IOException("Recovery metadata is too long");
        output.writeInt(encoded.length);
        output.write(encoded);
        Arrays.fill(encoded, (byte) 0);
    }

    private static String readString(DataInputStream input, int maximumBytes) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maximumBytes) {
            throw new IOException("Invalid recovery metadata length");
        }
        byte[] encoded = new byte[length];
        try {
            input.readFully(encoded);
            return new String(encoded, StandardCharsets.UTF_8);
        } catch (EOFException error) {
            throw new IOException("Truncated recovery metadata", error);
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    private SecretKey key() throws Exception {
        return suppliedKey == null ? getOrCreateKey() : suppliedKey;
    }

    private static synchronized SecretKey getOrCreateKey() throws Exception {
        SecretKey existingCached = cachedKey;
        if (existingCached != null) return existingCached;
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        SecretKey existing = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        if (existing != null) {
            cachedKey = existing;
            return existing;
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        SecretKey generated = generator.generateKey();
        cachedKey = generated;
        return generated;
    }

    private static byte[] aad(String id) {
        byte[] identifier = id.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                AAD_PREFIX.length + identifier.length);
        output.write(AAD_PREFIX, 0, AAD_PREFIX.length);
        output.write(identifier, 0, identifier.length);
        Arrays.fill(identifier, (byte) 0);
        return output.toByteArray();
    }

    private static String requireId(String id) {
        String value = id == null ? "" : id;
        if (!value.matches("[A-Za-z0-9_-]{16,96}")) {
            throw new IllegalArgumentException("Invalid voice recovery id");
        }
        return value;
    }

    private record Header(String id, byte[] iv) {}
}
