package com.opentypeless.android.offline;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/** Client for the private offline-model process. WAV bytes travel only through an anonymous pipe. */
public final class LocalOfflineRecognitionClient implements AutoCloseable {
    public record Result(String exactText, String punctuatedText) {
        public Result {
            exactText = requireText(exactText);
            punctuatedText = requireText(punctuatedText);
        }

        private static String requireText(String value) {
            String text = value == null ? "" : value.trim();
            if (text.isEmpty()) throw new IllegalStateException(
                    "Offline recognition returned no text");
            if (text.codePointCount(0, text.length()) > 20_000) {
                throw new IllegalStateException("Offline recognition output exceeded the limit");
            }
            return text;
        }
    }

    private static final long BIND_TIMEOUT_SECONDS = 10L;
    private static final AtomicLong NEXT_SESSION = new AtomicLong(
            Math.max(1L, System.currentTimeMillis()) * 1_000L);

    private final Context context;
    private final ExecutorService pipeWriter = Executors.newSingleThreadExecutor();
    private final Object activeLock = new Object();
    private volatile boolean closed;
    private long activeSessionId = -1L;
    private ILocalOfflineRecognitionService activeService;
    private ParcelFileDescriptor activeWriteEnd;

    public LocalOfflineRecognitionClient(Context context) {
        this.context = context.getApplicationContext();
    }

    public Result recognize(
            byte[] wav,
            String language,
            boolean useInverseTextNormalization) {
        if (wav == null || wav.length < 44
                || wav.length > LocalOfflineRecognitionService.MAX_WAV_BYTES) {
            throw new IllegalArgumentException("Offline WAV size is invalid");
        }
        requireUsableThread();
        Binding binding = bind();
        long sessionId = NEXT_SESSION.incrementAndGet();
        ParcelFileDescriptor[] pipe = null;
        Future<?> writer = null;
        try {
            requireUsableThread();
            pipe = ParcelFileDescriptor.createPipe();
            ParcelFileDescriptor readEnd = pipe[0];
            ParcelFileDescriptor writeEnd = pipe[1];
            synchronized (activeLock) {
                requireUsableThread();
                activeSessionId = sessionId;
                activeService = binding.service;
                activeWriteEnd = writeEnd;
            }
            writer = pipeWriter.submit(() -> writePipe(writeEnd, wav));
            Bundle bundle = binding.service.transcribe(
                    sessionId,
                    readEnd,
                    language,
                    useInverseTextNormalization);
            waitForWriter(writer);
            if (bundle == null) throw new IllegalStateException(
                    "Offline recognition returned no result");
            return new Result(
                    bundle.getString(LocalOfflineRecognitionService.RESULT_EXACT),
                    bundle.getString(LocalOfflineRecognitionService.RESULT_PUNCTUATED));
        } catch (RemoteException error) {
            if (Thread.currentThread().isInterrupted() || sessionWasCancelled(sessionId)) {
                throw new CancellationException("Offline recognition cancelled");
            }
            throw new IllegalStateException("Offline model process stopped unexpectedly", error);
        } catch (IOException error) {
            throw new IllegalStateException("Unable to create the private audio pipe", error);
        } finally {
            synchronized (activeLock) {
                if (activeSessionId == sessionId) {
                    activeSessionId = -1L;
                    activeService = null;
                    activeWriteEnd = null;
                }
            }
            closeQuietly(pipe == null ? null : pipe[0]);
            closeQuietly(pipe == null ? null : pipe[1]);
            if (writer != null && !writer.isDone()) writer.cancel(true);
            binding.unbind(context);
        }
    }

    /** Returns the worker PID for a process-isolation acceptance check; it loads no model. */
    public int servicePidForDiagnostics() {
        requireUsableThread();
        Binding binding = bind();
        try {
            return binding.service.servicePid();
        } catch (RemoteException error) {
            throw new IllegalStateException("Offline model process was unavailable", error);
        } finally {
            binding.unbind(context);
        }
    }

    public void cancelActive() {
        ILocalOfflineRecognitionService service;
        long sessionId;
        ParcelFileDescriptor writeEnd;
        synchronized (activeLock) {
            service = activeService;
            sessionId = activeSessionId;
            writeEnd = activeWriteEnd;
            activeSessionId = -1L;
            activeService = null;
            activeWriteEnd = null;
        }
        closeQuietly(writeEnd);
        if (service != null && sessionId > 0L) {
            try {
                service.cancel(sessionId);
            } catch (RemoteException ignored) {
                // A killed worker process is the expected cancellation terminal.
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        cancelActive();
        pipeWriter.shutdownNow();
    }

    private Binding bind() {
        if (closed) throw new IllegalStateException("Offline recognition client is closed");
        Connection connection = new Connection();
        Intent intent = new Intent(context, LocalOfflineRecognitionService.class);
        boolean bound;
        try {
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (RuntimeException error) {
            throw new IllegalStateException("Unable to start the offline model process", error);
        }
        if (!bound) throw new IllegalStateException("Unable to bind the offline model process");
        try {
            if (!connection.connected.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new TimeoutException("Offline model process did not bind in time");
            }
            requireUsableThread();
            if (connection.service == null) {
                throw new IllegalStateException("Offline model process binding failed");
            }
            return new Binding(connection, connection.service);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            connection.unbind(context);
            throw new CancellationException("Offline recognition cancelled while binding");
        } catch (TimeoutException | RuntimeException error) {
            connection.unbind(context);
            throw new IllegalStateException(error.getMessage(), error);
        }
    }

    private void requireUsableThread() {
        if (closed) throw new IllegalStateException("Offline recognition client is closed");
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Offline recognition cancelled");
        }
    }

    private boolean sessionWasCancelled(long sessionId) {
        synchronized (activeLock) {
            return activeSessionId != sessionId;
        }
    }

    private static void writePipe(ParcelFileDescriptor descriptor, byte[] wav) {
        try (FileOutputStream output = new FileOutputStream(descriptor.getFileDescriptor())) {
            output.write(wav);
            output.flush();
        } catch (IOException error) {
            if (!Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Private audio pipe write failed", error);
            }
        } finally {
            closeQuietly(descriptor);
        }
    }

    private static void waitForWriter(Future<?> writer) {
        try {
            writer.get(5L, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Private audio pipe write failed", cause);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Offline recognition cancelled");
        } catch (TimeoutException error) {
            throw new IllegalStateException("Private audio pipe did not close", error);
        }
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor == null) return;
        try {
            descriptor.close();
        } catch (IOException ignored) {
            // Cancellation and the pipe writer can race to close the same descriptor.
        }
    }

    private static final class Connection implements ServiceConnection {
        final CountDownLatch connected = new CountDownLatch(1);
        volatile ILocalOfflineRecognitionService service;
        private boolean bound = true;

        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ILocalOfflineRecognitionService.Stub.asInterface(binder);
            connected.countDown();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }

        @Override
        public void onBindingDied(ComponentName name) {
            service = null;
            connected.countDown();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            service = null;
            connected.countDown();
        }

        synchronized void unbind(Context context) {
            if (!bound) return;
            bound = false;
            try {
                context.unbindService(this);
            } catch (IllegalArgumentException ignored) {
                // Binding may have died before cleanup.
            }
        }
    }

    private record Binding(Connection connection, ILocalOfflineRecognitionService service) {
        void unbind(Context context) {
            connection.unbind(context);
        }
    }
}
