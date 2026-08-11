package com.opentypeless.android.offline;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/** Private, independently installable store for the optional live-preview model. */
public final class OfflineStreamingModelStore {
    public enum Status { MISSING, INSTALLED, CORRUPT }

    public record InstalledModel(
            File directory,
            File encoder,
            File decoder,
            File tokens,
            String fingerprint) {}

    private static final Object LOCK = new Object();
    private static final String ROOT = "offline_models";
    private static final String MARKER = "installed-v1.txt";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static volatile InstalledModel verified;

    private OfflineStreamingModelStore() {}

    public static Status status(Context context) {
        File directory = installDirectory(context);
        if (!directory.exists()) return Status.MISSING;
        return hasExpectedLayout(directory, OfflineStreamingModelSpec.REALTIME)
                ? Status.INSTALLED
                : Status.CORRUPT;
    }

    public static InstalledModel requireVerified(Context context) {
        synchronized (LOCK) {
            OfflineStreamingModelSpec spec = OfflineStreamingModelSpec.REALTIME;
            File directory = installDirectory(context);
            if (!hasExpectedLayout(directory, spec)) {
                throw new IllegalStateException("Live preview model is not installed or incomplete");
            }
            File encoder = new File(directory, spec.encoder().fileName());
            File decoder = new File(directory, spec.decoder().fileName());
            File tokens = new File(directory, spec.tokens().fileName());
            String fingerprint = fingerprint(encoder, decoder, tokens);
            InstalledModel cached = verified;
            if (cached != null && cached.fingerprint().equals(fingerprint)) return cached;
            try {
                requireHash(encoder, spec.encoder());
                requireHash(decoder, spec.decoder());
                requireHash(tokens, spec.tokens());
            } catch (IllegalStateException error) {
                File marker = new File(directory, MARKER);
                if (marker.exists()) marker.delete();
                verified = null;
                throw error;
            }
            InstalledModel result = new InstalledModel(
                    directory, encoder, decoder, tokens, fingerprint);
            verified = result;
            return result;
        }
    }

    static File installDirectory(Context context) {
        return new File(
                new File(context.getNoBackupFilesDir(), ROOT),
                OfflineStreamingModelSpec.REALTIME.id());
    }

    static File newStagingDirectory(Context context) {
        File root = new File(context.getNoBackupFilesDir(), ROOT);
        if (!root.exists() && !root.mkdirs()) {
            throw new IllegalStateException("Offline model storage could not be created");
        }
        File staging = new File(root, ".streaming-staging-"
                + Long.toUnsignedString(RANDOM.nextLong()));
        if (!staging.mkdir()) throw new IllegalStateException("Model staging could not be created");
        return staging;
    }

    static void commitVerifiedStaging(Context context, File staging) {
        synchronized (LOCK) {
            OfflineStreamingModelSpec spec = OfflineStreamingModelSpec.REALTIME;
            requireContained(context, staging);
            requireHash(new File(staging, spec.encoder().fileName()), spec.encoder());
            requireHash(new File(staging, spec.decoder().fileName()), spec.decoder());
            requireHash(new File(staging, spec.tokens().fileName()), spec.tokens());
            writeMarker(staging, spec);
            File target = installDirectory(context);
            if (target.exists()) deleteFixedDirectory(context, target);
            if (!staging.renameTo(target)) {
                throw new IllegalStateException("Verified live preview model could not be installed atomically");
            }
            verified = null;
        }
    }

    static void delete(Context context) {
        synchronized (LOCK) {
            File target = installDirectory(context);
            if (target.exists()) deleteFixedDirectory(context, target);
            verified = null;
        }
    }

    static void discardStaging(Context context, File staging) {
        if (staging == null || !staging.exists()) return;
        synchronized (LOCK) {
            requireContained(context, staging);
            if (!staging.getName().startsWith(".streaming-staging-")) {
                throw new IllegalArgumentException("Refusing to remove a non-streaming staging directory");
            }
            deleteTree(staging);
        }
    }

    private static boolean hasExpectedLayout(
            File directory,
            OfflineStreamingModelSpec spec) {
        File marker = new File(directory, MARKER);
        File encoder = new File(directory, spec.encoder().fileName());
        File decoder = new File(directory, spec.decoder().fileName());
        File tokens = new File(directory, spec.tokens().fileName());
        if (!marker.isFile()
                || encoder.length() != spec.encoder().bytes()
                || decoder.length() != spec.decoder().bytes()
                || tokens.length() != spec.tokens().bytes()) {
            return false;
        }
        try (FileInputStream input = new FileInputStream(marker)) {
            if (marker.length() > 2_048) return false;
            byte[] data = new byte[(int) marker.length()];
            int offset = 0;
            while (offset < data.length) {
                int read = input.read(data, offset, data.length - offset);
                if (read < 0) return false;
                offset += read;
            }
            return new String(data, StandardCharsets.UTF_8).equals(markerText(spec));
        } catch (IOException error) {
            return false;
        }
    }

    private static void writeMarker(File directory, OfflineStreamingModelSpec spec) {
        byte[] data = markerText(spec).getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(new File(directory, MARKER))) {
            output.write(data);
            output.getFD().sync();
        } catch (IOException error) {
            throw new IllegalStateException("Live preview model marker could not be written", error);
        }
    }

    private static String markerText(OfflineStreamingModelSpec spec) {
        return "opentypeless-streaming-model-v1\n"
                + "id=" + spec.id() + "\n"
                + "revision=" + spec.revision() + "\n"
                + "encoder_sha256=" + spec.encoder().sha256() + "\n"
                + "decoder_sha256=" + spec.decoder().sha256() + "\n"
                + "tokens_sha256=" + spec.tokens().sha256() + "\n";
    }

    private static void requireHash(File file, OfflineModelSpec.Artifact artifact) {
        if (!file.isFile() || file.length() != artifact.bytes()) {
            throw new IllegalStateException("Live preview model file size is invalid");
        }
        String actual;
        try (FileInputStream input = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            actual = hex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("Live preview model could not be verified", error);
        }
        if (!MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.US_ASCII),
                artifact.sha256().getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalStateException("Live preview model SHA-256 did not match");
        }
    }

    private static String fingerprint(File encoder, File decoder, File tokens) {
        return encoder.length() + ":" + encoder.lastModified() + ":"
                + decoder.length() + ":" + decoder.lastModified() + ":"
                + tokens.length() + ":" + tokens.lastModified();
    }

    private static String hex(byte[] value) {
        char[] out = new char[value.length * 2];
        char[] alphabet = "0123456789abcdef".toCharArray();
        for (int index = 0; index < value.length; index++) {
            int unsigned = value[index] & 0xff;
            out[index * 2] = alphabet[unsigned >>> 4];
            out[index * 2 + 1] = alphabet[unsigned & 0x0f];
        }
        return new String(out);
    }

    private static void deleteFixedDirectory(Context context, File directory) {
        requireContained(context, directory);
        File expected = installDirectory(context);
        if (!directory.equals(expected)) {
            throw new IllegalArgumentException("Refusing to delete an unexpected model path");
        }
        deleteTree(directory);
    }

    private static void requireContained(Context context, File file) {
        try {
            File root = new File(context.getNoBackupFilesDir(), ROOT).getCanonicalFile();
            File candidate = file.getCanonicalFile();
            if (!candidate.toPath().startsWith(root.toPath()) || candidate.equals(root)) {
                throw new IllegalArgumentException("Model path escapes private storage");
            }
        } catch (IOException error) {
            throw new IllegalStateException("Model path could not be resolved", error);
        }
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        if (!file.delete() && file.exists()) {
            throw new IllegalStateException("Live preview model files could not be removed");
        }
    }
}
