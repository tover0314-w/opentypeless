package com.opentypeless.android.editor.host;

import android.view.inputmethod.InputConnection;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owner-thread-confined registry for the one active editor connection in this process.
 *
 * <p>Tokens are opaque, are never persisted, and rotate on every registration even when an OEM
 * reuses the same InputConnection object. The registry deliberately keeps no historical mapping.
 */
final class ProcessInputConnectionRegistry implements InputConnectionRegistry {
    private static final AtomicLong TOKEN_ALLOCATOR = new AtomicLong();

    private final Thread ownerThread;
    private long currentToken = NO_CONNECTION_TOKEN;
    private InputConnection currentConnection;

    ProcessInputConnectionRegistry() {
        ownerThread = Thread.currentThread();
    }

    /** Registers one active connection and invalidates every previously returned token. */
    long register(InputConnection connection) {
        requireOwnerThread();
        InputConnection safeConnection = Objects.requireNonNull(connection, "connection");
        long token = allocateToken();
        currentConnection = safeConnection;
        currentToken = token;
        return token;
    }

    @Override
    public long currentToken() {
        requireOwnerThread();
        return currentToken;
    }

    @Override
    public InputConnection resolve(long token) {
        requireOwnerThread();
        if (token <= NO_CONNECTION_TOKEN || token != currentToken) return null;
        return currentConnection;
    }

    /** Invalidates the active connection only if the caller still owns its exact token. */
    boolean invalidate(long expectedToken) {
        requireOwnerThread();
        if (expectedToken <= NO_CONNECTION_TOKEN || expectedToken != currentToken) return false;
        clearCurrent();
        return true;
    }

    /** Host teardown escape hatch. Idempotently drops the only retained Android capability. */
    void invalidateAll() {
        requireOwnerThread();
        clearCurrent();
    }

    private void clearCurrent() {
        currentConnection = null;
        currentToken = NO_CONNECTION_TOKEN;
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException(
                    "InputConnectionRegistry may only be accessed from its owner thread");
        }
    }

    private static long allocateToken() {
        return allocateToken(TOKEN_ALLOCATOR);
    }

    static long allocateToken(AtomicLong allocator) {
        Objects.requireNonNull(allocator, "allocator");
        while (true) {
            long current = allocator.get();
            if (current == Long.MAX_VALUE) {
                throw new IllegalStateException("InputConnection token space is exhausted");
            }
            long token = current + 1;
            if (allocator.compareAndSet(current, token)) return token;
        }
    }
}
