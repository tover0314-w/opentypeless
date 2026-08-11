package com.opentypeless.android.offline;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/** Serialized client for the warm, isolated punctuation worker. */
public final class LocalPunctuationRecognitionClient implements AutoCloseable {
    private static final long BIND_TIMEOUT_SECONDS = 10L;
    private static final AtomicLong NEXT_SESSION = new AtomicLong(
            Math.max(1L, System.currentTimeMillis()) * 1_000L + 750L);

    private final Context context;
    private final Object bindingLock = new Object();
    private final Object operationLock = new Object();
    private final Object activeLock = new Object();
    private volatile boolean closed;
    private Binding sharedBinding;
    private ILocalPunctuationService activeService;
    private long activeSessionId = -1L;

    public LocalPunctuationRecognitionClient(Context context) {
        this.context = context.getApplicationContext();
    }

    public String punctuate(String source) {
        String text = source == null ? "" : source.trim();
        if (text.isEmpty()) throw new IllegalArgumentException("Punctuation text is empty");
        if (text.codePointCount(0, text.length()) > 20_000) {
            throw new IllegalArgumentException("Punctuation text exceeded the limit");
        }
        synchronized (operationLock) {
            requireOpen();
            Binding binding = binding();
            long sessionId = NEXT_SESSION.incrementAndGet();
            synchronized (activeLock) {
                requireOpen();
                activeService = binding.service;
                activeSessionId = sessionId;
            }
            try {
                String result = binding.service.punctuate(sessionId, text);
                if (result == null || result.trim().isEmpty()) {
                    throw new IllegalStateException("Punctuation model returned no text");
                }
                return result.trim();
            } catch (RemoteException error) {
                if (Thread.currentThread().isInterrupted() || sessionWasCancelled(sessionId)) {
                    throw new CancellationException("Punctuation request was cancelled");
                }
                invalidateBinding();
                throw new IllegalStateException("Punctuation process stopped unexpectedly", error);
            } finally {
                synchronized (activeLock) {
                    if (activeSessionId == sessionId) {
                        activeSessionId = -1L;
                        activeService = null;
                    }
                }
            }
        }
    }

    public void prewarm() {
        if (closed || !LocalPunctuationRecognizer.isInstalled(context)) return;
        try {
            binding().service.prewarm();
        } catch (RemoteException error) {
            invalidateBinding();
            throw new IllegalStateException("Punctuation model could not prewarm", error);
        }
    }

    public void releaseWarmModel() {
        Binding binding;
        synchronized (bindingLock) {
            binding = sharedBinding;
        }
        if (binding == null || !binding.usable()) return;
        try {
            binding.service.releaseModel();
        } catch (RemoteException error) {
            invalidateBinding();
        }
    }

    /** Ends the current dictation lease and lets Android reclaim the model-only worker process. */
    public void releaseSessionWorker() {
        if (closed) return;
        cancelActive();
        releaseWarmModel();
        invalidateBinding();
    }

    public void cancelActive() {
        ILocalPunctuationService service;
        long sessionId;
        synchronized (activeLock) {
            service = activeService;
            sessionId = activeSessionId;
            activeService = null;
            activeSessionId = -1L;
        }
        if (service != null && sessionId > 0L) {
            try {
                service.cancel(sessionId);
            } catch (RemoteException ignored) {
                // A killed text worker is the expected cancellation terminal.
            }
        }
    }

    public int servicePidForDiagnostics() {
        requireOpen();
        try {
            return binding().service.servicePid();
        } catch (RemoteException error) {
            invalidateBinding();
            throw new IllegalStateException("Punctuation process was unavailable", error);
        }
    }

    @Override
    public void close() {
        closed = true;
        cancelActive();
        invalidateBinding();
    }

    private Binding binding() {
        synchronized (bindingLock) {
            requireOpen();
            if (sharedBinding != null && sharedBinding.usable()) return sharedBinding;
            if (sharedBinding != null) sharedBinding.unbind(context);
            Connection connection = new Connection();
            Intent intent = new Intent(context, LocalPunctuationRecognitionService.class);
            boolean bound;
            try {
                bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            } catch (RuntimeException error) {
                throw new IllegalStateException("Unable to start punctuation process", error);
            }
            if (!bound) throw new IllegalStateException("Unable to bind punctuation process");
            try {
                if (!connection.connected.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new TimeoutException("Punctuation process did not bind in time");
                }
                requireOpen();
                if (connection.service == null) {
                    throw new IllegalStateException("Punctuation process binding failed");
                }
                sharedBinding = new Binding(connection, connection.service);
                return sharedBinding;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                connection.unbind(context);
                throw new CancellationException("Punctuation binding was cancelled");
            } catch (TimeoutException | RuntimeException error) {
                connection.unbind(context);
                throw new IllegalStateException(error.getMessage(), error);
            }
        }
    }

    private void invalidateBinding() {
        synchronized (bindingLock) {
            Binding binding = sharedBinding;
            sharedBinding = null;
            if (binding != null) binding.unbind(context);
        }
    }

    private boolean sessionWasCancelled(long sessionId) {
        synchronized (activeLock) {
            return activeSessionId != sessionId;
        }
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Punctuation client is closed");
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Punctuation request was cancelled");
        }
    }

    private static final class Connection implements ServiceConnection {
        final CountDownLatch connected = new CountDownLatch(1);
        volatile ILocalPunctuationService service;
        private boolean bound = true;

        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ILocalPunctuationService.Stub.asInterface(binder);
            connected.countDown();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            connected.countDown();
        }

        void unbind(Context context) {
            if (!bound) return;
            bound = false;
            try {
                context.unbindService(this);
            } catch (IllegalArgumentException ignored) {
                // A dead worker may already have removed the binding.
            }
        }
    }

    private record Binding(Connection connection, ILocalPunctuationService service) {
        boolean usable() {
            return service != null && connection.service != null;
        }

        void unbind(Context context) {
            connection.unbind(context);
        }
    }
}
