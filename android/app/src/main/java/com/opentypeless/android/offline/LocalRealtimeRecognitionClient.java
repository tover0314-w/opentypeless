package com.opentypeless.android.offline;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/** Streams PCM to the private ASR process without loading native weights inside the IME. */
public final class LocalRealtimeRecognitionClient implements AutoCloseable {
    public interface Listener {
        void onPartial(String text);
    }

    private static final long BIND_TIMEOUT_SECONDS = 10L;
    private static final long FINAL_TIMEOUT_SECONDS = 30L;
    private static final AtomicLong NEXT_SESSION = new AtomicLong(
            Math.max(1L, System.currentTimeMillis()) * 1_000L + 500L);

    private final Context context;
    private final Object activeLock = new Object();
    private final Object bindingLock = new Object();
    private volatile boolean closed;
    private Session active;
    private Binding sharedBinding;

    public LocalRealtimeRecognitionClient(Context context) {
        this.context = context.getApplicationContext();
    }

    public Session start(Listener listener) {
        if (listener == null) throw new IllegalArgumentException("Listener is required");
        if (closed) throw new IllegalStateException("Live preview client is closed");
        Binding binding = binding();
        ParcelFileDescriptor[] pipe = null;
        Session session = null;
        long sessionId = NEXT_SESSION.incrementAndGet();
        Callback callback = new Callback(sessionId, listener);
        try {
            pipe = ParcelFileDescriptor.createPipe();
            binding.service.startRealtime(sessionId, pipe[0], callback);
            closeQuietly(pipe[0]);
            pipe[0] = null;
            session = new Session(binding, sessionId, pipe[1], callback, this);
            pipe[1] = null;
            synchronized (activeLock) {
                if (closed || active != null) {
                    session.cancel();
                    throw new IllegalStateException("Another live preview session is active");
                }
                active = session;
            }
            // Do not make microphone capture wait for native model loading. Frames are queued into
            // the anonymous pipe while the isolated worker becomes ready, and finish() still
            // requires an authenticated terminal callback. This separates microphone latency from
            // first-pass model latency and makes short hold-to-talk utterances record immediately.
            return session;
        } catch (RemoteException error) {
            if (session != null) session.cancel();
            throw new IllegalStateException("Live preview process could not start", error);
        } catch (IOException error) {
            if (session != null) session.cancel();
            throw new IllegalStateException("Unable to create the live audio pipe", error);
        } catch (RuntimeException error) {
            if (session != null) session.cancel();
            else {
                tryCancel(binding.service, sessionId);
            }
            throw error;
        } finally {
            if (pipe != null) {
                closeQuietly(pipe[0]);
                closeQuietly(pipe[1]);
            }
        }
    }

    public void cancelActive() {
        Session session;
        synchronized (activeLock) {
            session = active;
        }
        if (session != null) session.cancel();
    }

    /** Warms the isolated streaming weights while keeping microphone ownership in the IME. */
    public void prewarm() {
        if (closed) return;
        try {
            binding().service.prewarmRealtime();
        } catch (RemoteException error) {
            invalidateBinding();
            throw new IllegalStateException("Live preview model could not prewarm", error);
        }
    }

    /** Releases warm weights for sequential/low-memory execution without closing this client. */
    public void releaseWarmModel() {
        Binding binding;
        synchronized (bindingLock) {
            binding = sharedBinding;
        }
        if (binding == null || !binding.usable()) return;
        try {
            binding.service.releaseRealtimeModel();
        } catch (RemoteException error) {
            invalidateBinding();
        }
    }

    @Override
    public void close() {
        closed = true;
        cancelActive();
        invalidateBinding();
    }

    private void clear(Session session) {
        synchronized (activeLock) {
            if (active == session) active = null;
        }
    }

    public static final class Session implements AutoCloseable {
        private static final byte[] END = new byte[0];
        private static final int MAX_QUEUED_FRAMES = 100;

        private final Binding binding;
        private final long sessionId;
        private final ParcelFileDescriptor writeEnd;
        private final Callback callback;
        private final LocalRealtimeRecognitionClient owner;
        private final ArrayBlockingQueue<byte[]> frames =
                new ArrayBlockingQueue<>(MAX_QUEUED_FRAMES);
        private final ExecutorService writerExecutor = Executors.newSingleThreadExecutor();
        private final Future<?> writer;
        private volatile boolean finished;
        private volatile boolean cancelled;

        private Session(
                Binding binding,
                long sessionId,
                ParcelFileDescriptor writeEnd,
                Callback callback,
                LocalRealtimeRecognitionClient owner) {
            this.binding = binding;
            this.sessionId = sessionId;
            this.writeEnd = writeEnd;
            this.callback = callback;
            this.owner = owner;
            writer = writerExecutor.submit(this::writeFrames);
        }

        /** Copies one microphone frame so capture never shares a mutable AudioRecord buffer. */
        public void accept(byte[] pcm16, int length) {
            if (finished || cancelled || pcm16 == null || length <= 0) return;
            int safeLength = Math.min(length, pcm16.length) & ~1;
            if (safeLength <= 0) return;
            if (!frames.offer(Arrays.copyOf(pcm16, safeLength))) {
                cancel();
                throw new IllegalStateException("Live preview could not keep up with the microphone");
            }
        }

        /** Closes the PCM pipe in order, then waits for the best first-pass text. */
        public String finish() {
            if (cancelled) throw new CancellationException("Live preview was cancelled");
            if (finished) return callback.finalText;
            finished = true;
            boolean completed = false;
            try {
                if (!frames.offer(END, 2L, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Live preview audio queue did not drain");
                }
                writer.get(10L, TimeUnit.SECONDS);
                if (!callback.terminal.await(FINAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new TimeoutException("Live preview final result timed out");
                }
                callback.requireNoError();
                completed = true;
                return callback.finalText;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new CancellationException("Live preview finalization was cancelled");
            } catch (java.util.concurrent.ExecutionException error) {
                Throwable cause = error.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("Live preview audio pipe failed", cause);
            } catch (TimeoutException error) {
                throw new IllegalStateException(error.getMessage(), error);
            } finally {
                cleanup(!completed);
            }
        }

        public void cancel() {
            if (cancelled) return;
            cancelled = true;
            cleanup(true);
        }

        @Override
        public void close() {
            if (!finished) cancel();
        }

        private void writeFrames() {
            try (FileOutputStream output = new FileOutputStream(writeEnd.getFileDescriptor())) {
                while (true) {
                    byte[] frame = frames.take();
                    if (frame == END) break;
                    output.write(frame);
                }
                output.flush();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                if (!cancelled) throw new CancellationException("Live preview writer interrupted");
            } catch (IOException error) {
                if (!cancelled) throw new IllegalStateException(
                        "Live preview audio pipe failed", error);
            } finally {
                closeQuietly(writeEnd);
            }
        }

        private void cleanup(boolean requestCancel) {
            if (requestCancel) tryCancel(binding.service, sessionId);
            closeQuietly(writeEnd);
            writer.cancel(true);
            writerExecutor.shutdownNow();
            owner.clear(this);
        }
    }

    private static final class Callback extends ILocalRealtimeRecognitionCallback.Stub {
        final long sessionId;
        final Listener listener;
        final CountDownLatch ready = new CountDownLatch(1);
        final CountDownLatch terminal = new CountDownLatch(1);
        volatile String finalText = "";
        volatile String error = "";

        Callback(long sessionId, Listener listener) {
            this.sessionId = sessionId;
            this.listener = listener;
        }

        @Override
        public void onReady(long callbackSessionId) {
            if (callbackSessionId != sessionId) return;
            ready.countDown();
        }

        @Override
        public void onPartial(long callbackSessionId, String text) {
            if (callbackSessionId != sessionId || text == null || text.isBlank()) return;
            String clean = boundedText(text);
            listener.onPartial(clean);
        }

        @Override
        public void onFinal(long callbackSessionId, String text) {
            if (callbackSessionId != sessionId) return;
            finalText = text == null || text.isBlank() ? "" : boundedText(text);
            ready.countDown();
            terminal.countDown();
        }

        @Override
        public void onError(long callbackSessionId, String message) {
            if (callbackSessionId != sessionId) return;
            error = message == null || message.isBlank()
                    ? "Live preview recognition failed"
                    : message;
            ready.countDown();
            terminal.countDown();
        }

        void requireNoError() {
            if (!error.isEmpty()) throw new IllegalStateException(error);
        }

        private static String boundedText(String value) {
            String text = value.trim();
            if (text.codePointCount(0, text.length()) > 20_000) {
                throw new IllegalStateException("Live preview output exceeded the limit");
            }
            return text;
        }
    }

    private Binding binding() {
        synchronized (bindingLock) {
            if (closed) throw new IllegalStateException("Live preview client is closed");
            if (sharedBinding != null && sharedBinding.usable()) return sharedBinding;
            if (sharedBinding != null) sharedBinding.unbind(context);
            sharedBinding = bindFresh();
            return sharedBinding;
        }
    }

    private Binding bindFresh() {
        Connection connection = new Connection();
        Intent intent = new Intent(context, LocalStreamingRecognitionService.class);
        boolean bound;
        try {
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (RuntimeException error) {
            throw new IllegalStateException("Unable to start the live preview process", error);
        }
        if (!bound) throw new IllegalStateException("Unable to bind the live preview process");
        try {
            if (!connection.connected.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new TimeoutException("Live preview process did not bind in time");
            }
            if (closed) throw new IllegalStateException("Live preview client is closed");
            if (connection.service == null) {
                throw new IllegalStateException("Live preview process binding failed");
            }
            return new Binding(connection, connection.service);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            connection.unbind(context);
            throw new CancellationException("Live preview binding was cancelled");
        } catch (TimeoutException | RuntimeException error) {
            connection.unbind(context);
            throw new IllegalStateException(error.getMessage(), error);
        }
    }

    private void invalidateBinding() {
        synchronized (bindingLock) {
            Binding binding = sharedBinding;
            sharedBinding = null;
            if (binding != null) binding.unbind(context);
        }
    }

    private static void tryCancel(ILocalOfflineRecognitionService service, long sessionId) {
        if (service == null || sessionId <= 0L) return;
        try {
            service.cancel(sessionId);
        } catch (RemoteException ignored) {
            // Killing the private model process is the expected deterministic cancellation path.
        }
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor == null) return;
        try {
            descriptor.close();
        } catch (IOException ignored) {
            // Pipe peers and cancellation can race to close the descriptor.
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
        boolean usable() {
            return service != null && connection.service == service;
        }

        void unbind(Context context) {
            connection.unbind(context);
        }
    }
}
