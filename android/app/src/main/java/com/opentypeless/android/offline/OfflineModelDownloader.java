package com.opentypeless.android.offline;

import android.content.Context;
import android.os.StatFs;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Downloads a fixed, revision-pinned model without credentials into app-private storage. */
public final class OfflineModelDownloader {
    public interface Callback {
        void onProgress(int percent, long downloadedBytes, long totalBytes);
        void onComplete();
        void onError(String message);
    }

    public interface Operation { void cancel(); }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MAX_REDIRECTS = 5;
    private static final long FREE_SPACE_MARGIN = 32L * 1024L * 1024L;

    private OfflineModelDownloader() {}

    public static Operation download(Context context, Callback callback) {
        if (context == null || callback == null) {
            throw new IllegalArgumentException("Context and callback are required");
        }
        DownloadTask task = new DownloadTask(context.getApplicationContext(), callback);
        EXECUTOR.execute(task);
        return task;
    }

    public static Operation delete(Context context, Callback callback) {
        if (context == null || callback == null) {
            throw new IllegalArgumentException("Context and callback are required");
        }
        AtomicBoolean cancelled = new AtomicBoolean();
        EXECUTOR.execute(() -> {
            try {
                LocalOfflineRecognizer.deleteModel(context.getApplicationContext());
                if (!cancelled.get()) callback.onComplete();
            } catch (RuntimeException error) {
                if (!cancelled.get()) callback.onError(safeMessage(error));
            }
        });
        return () -> cancelled.set(true);
    }

    static boolean trustedDownloadUri(URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getRawUserInfo() != null || uri.getPort() != -1) return false;
        String host = uri.getHost();
        if (host == null) return false;
        host = host.toLowerCase(java.util.Locale.ROOT);
        return host.equals("huggingface.co")
                || host.endsWith(".huggingface.co")
                || host.equals("cdn-lfs.huggingface.co")
                || host.endsWith(".cdn.hf.co")
                || host.endsWith(".xethub.hf.co");
    }

    private static final class DownloadTask implements Runnable, Operation {
        private final Context context;
        private final Callback callback;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile HttpURLConnection activeConnection;
        private File staging;

        DownloadTask(Context context, Callback callback) {
            this.context = context;
            this.callback = callback;
        }

        @Override
        public void run() {
            try {
                OfflineModelSpec spec = OfflineModelSpec.QUALITY;
                requireSpace(context, spec.downloadBytes() + FREE_SPACE_MARGIN);
                staging = OfflineModelStore.newStagingDirectory(context);
                long downloaded = 0;
                downloaded += downloadArtifact(spec.model(), staging, downloaded, spec.downloadBytes());
                downloaded += downloadArtifact(spec.tokens(), staging, downloaded, spec.downloadBytes());
                checkCancelled();
                OfflineModelStore.commitVerifiedStaging(context, staging);
                staging = null;
                callback.onProgress(100, downloaded, spec.downloadBytes());
                callback.onComplete();
            } catch (Cancelled ignored) {
                // Cancellation is an explicit UI action and is not surfaced as a failure.
            } catch (Exception error) {
                callback.onError(safeMessage(error));
            } finally {
                HttpURLConnection connection = activeConnection;
                if (connection != null) connection.disconnect();
                if (staging != null) {
                    try {
                        OfflineModelStore.discardStaging(context, staging);
                    } catch (RuntimeException ignored) {
                        // The verified fixed-path cleanup is best effort after the original error.
                    }
                }
            }
        }

        private long downloadArtifact(
                OfflineModelSpec.Artifact artifact,
                File directory,
                long completedBefore,
                long total) throws IOException, Cancelled {
            URI current = artifact.uri();
            HttpURLConnection connection = null;
            for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
                checkCancelled();
                if (!trustedDownloadUri(current)) {
                    throw new IOException("Model host is not trusted");
                }
                connection = (HttpURLConnection) new URL(current.toString()).openConnection();
                activeConnection = connection;
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setRequestProperty("Accept", "application/octet-stream");
                connection.setRequestProperty("User-Agent", "OpenTypeless-Android/0.2");
                int code = connection.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = connection.getHeaderField("Location");
                    connection.disconnect();
                    activeConnection = null;
                    if (location == null || redirect == MAX_REDIRECTS) {
                        throw new IOException("Model download redirect was invalid");
                    }
                    current = current.resolve(location);
                    continue;
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    throw new IOException("Model host returned HTTP " + code);
                }
                break;
            }
            if (connection == null) throw new IOException("Model connection was not created");
            long contentLength = connection.getContentLengthLong();
            if (contentLength >= 0 && contentLength != artifact.bytes()) {
                throw new IOException("Model host returned an unexpected file size");
            }
            File outputFile = new File(directory, artifact.fileName());
            long written = 0;
            try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream fileOutput = new FileOutputStream(outputFile);
                 BufferedOutputStream output = new BufferedOutputStream(fileOutput, 128 * 1024)) {
                byte[] buffer = new byte[128 * 1024];
                int read;
                int lastPercent = -1;
                while ((read = input.read(buffer)) != -1) {
                    checkCancelled();
                    if (written > artifact.bytes() - read) {
                        throw new IOException("Model download exceeded its pinned size");
                    }
                    output.write(buffer, 0, read);
                    written += read;
                    int percent = (int) (((completedBefore + written) * 100L) / total);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        callback.onProgress(percent, completedBefore + written, total);
                    }
                }
                output.flush();
                fileOutput.getFD().sync();
            } finally {
                connection.disconnect();
                activeConnection = null;
            }
            if (written != artifact.bytes()) {
                throw new IOException("Model download was incomplete");
            }
            return written;
        }

        @Override
        public void cancel() {
            cancelled.set(true);
            HttpURLConnection connection = activeConnection;
            if (connection != null) connection.disconnect();
        }

        private void checkCancelled() throws Cancelled {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new Cancelled();
        }
    }

    private static void requireSpace(Context context, long required) {
        StatFs stat = new StatFs(context.getNoBackupFilesDir().getAbsolutePath());
        if (stat.getAvailableBytes() < required) {
            throw new IllegalStateException("Not enough private storage for the offline model");
        }
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? "Offline model download failed"
                : message;
    }

    private static final class Cancelled extends Exception {}
}
