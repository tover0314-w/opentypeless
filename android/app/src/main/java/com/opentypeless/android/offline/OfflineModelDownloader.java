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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private static final int MAX_ARTIFACT_ATTEMPTS = 4;
    private static final long FREE_SPACE_MARGIN = 32L * 1024L * 1024L;
    private static final Pattern CONTENT_RANGE = Pattern.compile(
            "bytes\\s+(\\d+)-(\\d+)/(\\d+)", Pattern.CASE_INSENSITIVE);

    record ResumePlan(long writeOffset, boolean append) {}

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
            File outputFile = new File(directory, artifact.fileName());
            IOException lastError = null;
            for (int attempt = 1; attempt <= MAX_ARTIFACT_ATTEMPTS; attempt++) {
                checkCancelled();
                long existing = outputFile.isFile() ? outputFile.length() : 0L;
                if (existing == artifact.bytes()) return existing;
                if (existing < 0 || existing > artifact.bytes()) {
                    if (!outputFile.delete() && outputFile.exists()) {
                        throw new IOException("Invalid partial model could not be replaced");
                    }
                    existing = 0L;
                }
                try {
                    return downloadArtifactAttempt(
                            artifact,
                            outputFile,
                            existing,
                            completedBefore,
                            total);
                } catch (IOException error) {
                    lastError = error;
                    HttpURLConnection connection = activeConnection;
                    if (connection != null) connection.disconnect();
                    activeConnection = null;
                    if (attempt == MAX_ARTIFACT_ATTEMPTS) break;
                    waitBeforeRetry(attempt);
                }
            }
            throw lastError == null ? new IOException("Model download failed") : lastError;
        }

        private long downloadArtifactAttempt(
                OfflineModelSpec.Artifact artifact,
                File outputFile,
                long existing,
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
                if (existing > 0) connection.setRequestProperty("Range", "bytes=" + existing + "-");
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
                if (code != HttpURLConnection.HTTP_OK
                        && code != HttpURLConnection.HTTP_PARTIAL) {
                    throw new IOException("Model host returned HTTP " + code);
                }
                break;
            }
            if (connection == null) throw new IOException("Model connection was not created");
            long contentLength = connection.getContentLengthLong();
            ResumePlan plan = resumePlan(
                    existing,
                    connection.getResponseCode(),
                    contentLength,
                    connection.getHeaderField("Content-Range"),
                    artifact.bytes());
            long written = plan.writeOffset();
            try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream fileOutput = new FileOutputStream(outputFile, plan.append());
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

        private void waitBeforeRetry(int completedAttempts) throws Cancelled {
            long remaining = Math.min(4_000L, completedAttempts * 750L);
            while (remaining > 0) {
                checkCancelled();
                long slice = Math.min(remaining, 100L);
                try {
                    Thread.sleep(slice);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new Cancelled();
                }
                remaining -= slice;
            }
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

    static ResumePlan resumePlan(
            long existing,
            int responseCode,
            long contentLength,
            String contentRange,
            long expectedTotal) throws IOException {
        if (existing < 0 || existing > expectedTotal || expectedTotal <= 0) {
            throw new IOException("Partial model size is invalid");
        }
        if (responseCode == HttpURLConnection.HTTP_OK) {
            if (contentLength >= 0 && contentLength != expectedTotal) {
                throw new IOException("Model host returned an unexpected file size");
            }
            // Some storage frontends ignore Range. Restart this artifact safely instead of
            // appending a complete response to the partial file.
            return new ResumePlan(0L, false);
        }
        if (responseCode != HttpURLConnection.HTTP_PARTIAL) {
            throw new IOException("Model host did not return a downloadable response");
        }
        Matcher matcher = CONTENT_RANGE.matcher(contentRange == null ? "" : contentRange.trim());
        if (!matcher.matches()) throw new IOException("Model resume range was invalid");
        long start;
        long end;
        long total;
        try {
            start = Long.parseLong(matcher.group(1));
            end = Long.parseLong(matcher.group(2));
            total = Long.parseLong(matcher.group(3));
        } catch (NumberFormatException error) {
            throw new IOException("Model resume range was invalid", error);
        }
        long expectedRemaining = expectedTotal - existing;
        if (start != existing || total != expectedTotal || end != expectedTotal - 1
                || end < start || (contentLength >= 0 && contentLength != expectedRemaining)) {
            throw new IOException("Model resume range did not match the pinned artifact");
        }
        return new ResumePlan(existing, existing > 0);
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
