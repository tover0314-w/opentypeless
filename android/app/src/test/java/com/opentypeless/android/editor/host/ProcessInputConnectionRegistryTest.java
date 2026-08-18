package com.opentypeless.android.editor.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.view.inputmethod.InputConnection;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public final class ProcessInputConnectionRegistryTest {
    @Test
    public void startsEmptyAndRejectsNullRegistration() {
        ProcessInputConnectionRegistry registry = new ProcessInputConnectionRegistry();
        assertEquals(InputConnectionRegistry.NO_CONNECTION_TOKEN, registry.currentToken());
        assertNull(registry.resolve(InputConnectionRegistry.NO_CONNECTION_TOKEN));
        assertNull(registry.resolve(-1));
        assertNull(registry.resolve(1));
        try {
            registry.register(null);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // Expected.
        }
        assertEquals(InputConnectionRegistry.NO_CONNECTION_TOKEN, registry.currentToken());
    }

    @Test
    public void registrationReturnsOpaquePositiveTokenAndExactReference() {
        ProcessInputConnectionRegistry registry = new ProcessInputConnectionRegistry();
        InputConnection connection = fakeConnection("first");
        long token = registry.register(connection);

        assertTrue(token > 0);
        assertEquals(token, registry.currentToken());
        assertSame(connection, registry.resolve(token));
        assertNull(registry.resolve(token + 1));
    }

    @Test
    public void everyRegistrationRotatesTokenEvenForSameConnection() {
        ProcessInputConnectionRegistry registry = new ProcessInputConnectionRegistry();
        InputConnection connection = fakeConnection("reused");
        long first = registry.register(connection);
        long second = registry.register(connection);

        assertNotEquals(first, second);
        assertNull(registry.resolve(first));
        assertSame(connection, registry.resolve(second));

        InputConnection replacement = fakeConnection("replacement");
        long third = registry.register(replacement);
        assertNotEquals(second, third);
        assertNull(registry.resolve(second));
        assertSame(replacement, registry.resolve(third));
    }

    @Test
    public void expectedInvalidationCannotClearANewerRegistration() {
        ProcessInputConnectionRegistry registry = new ProcessInputConnectionRegistry();
        long stale = registry.register(fakeConnection("stale"));
        InputConnection current = fakeConnection("current");
        long active = registry.register(current);

        assertFalse(registry.invalidate(stale));
        assertFalse(registry.invalidate(0));
        assertSame(current, registry.resolve(active));
        assertTrue(registry.invalidate(active));
        assertEquals(InputConnectionRegistry.NO_CONNECTION_TOKEN, registry.currentToken());
        assertNull(registry.resolve(active));
        assertFalse(registry.invalidate(active));
    }

    @Test
    public void invalidateAllIsIdempotentAndDropsStrongReference() {
        ProcessInputConnectionRegistry registry = new ProcessInputConnectionRegistry();
        long token = registry.register(fakeConnection("value"));
        registry.invalidateAll();
        registry.invalidateAll();

        assertEquals(InputConnectionRegistry.NO_CONNECTION_TOKEN, registry.currentToken());
        assertNull(registry.resolve(token));
    }

    @Test
    public void registryRecreationDoesNotReuseTokensOrResolveOldConnections() {
        ProcessInputConnectionRegistry firstRegistry = new ProcessInputConnectionRegistry();
        long first = firstRegistry.register(fakeConnection("first"));
        ProcessInputConnectionRegistry recreated = new ProcessInputConnectionRegistry();

        assertEquals(InputConnectionRegistry.NO_CONNECTION_TOKEN, recreated.currentToken());
        assertNull(recreated.resolve(first));
        long second = recreated.register(fakeConnection("second"));
        assertNotEquals(first, second);
    }

    @Test
    public void sequentialRegistrationsNeverReuseToken() {
        ProcessInputConnectionRegistry registry = new ProcessInputConnectionRegistry();
        InputConnection connection = fakeConnection("bulk");
        Set<Long> tokens = new HashSet<>();
        for (int index = 0; index < 10_000; index++) {
            long token = registry.register(connection);
            assertTrue(token > 0);
            assertTrue("duplicate token " + token, tokens.add(token));
        }
    }

    @Test
    public void everyEntryPointFailsFastOffOwnerThread() throws Exception {
        ProcessInputConnectionRegistry registry = new ProcessInputConnectionRegistry();
        InputConnection connection = fakeConnection("owner");
        long token = registry.register(connection);

        assertOffOwnerThrows(() -> registry.register(connection));
        assertOffOwnerThrows(registry::currentToken);
        assertOffOwnerThrows(() -> registry.resolve(token));
        assertOffOwnerThrows(() -> registry.invalidate(token));
        assertOffOwnerThrows(() -> {
            registry.invalidateAll();
            return null;
        });

        assertSame(connection, registry.resolve(token));
    }

    @Test
    public void allocatorSaturatesWithoutWrappingOrMutatingAtExhaustion() {
        AtomicLong allocator = new AtomicLong(Long.MAX_VALUE - 1);
        assertEquals(Long.MAX_VALUE, ProcessInputConnectionRegistry.allocateToken(allocator));
        assertEquals(Long.MAX_VALUE, allocator.get());
        assertAllocatorExhausted(allocator);
        assertAllocatorExhausted(allocator);
        assertEquals(Long.MAX_VALUE, allocator.get());
    }

    private static void assertAllocatorExhausted(AtomicLong allocator) {
        try {
            ProcessInputConnectionRegistry.allocateToken(allocator);
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // Expected.
        }
    }

    private static void assertOffOwnerThrows(Callable<?> operation) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> future = executor.submit(operation);
            try {
                future.get();
                fail("expected IllegalStateException");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof IllegalStateException);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static InputConnection fakeConnection(String label) {
        return (InputConnection) Proxy.newProxyInstance(
                InputConnection.class.getClassLoader(),
                new Class<?>[]{InputConnection.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "toString" -> "FakeInputConnection(" + label + ")";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new AssertionError(
                            "registry must not invoke InputConnection." + method.getName());
                });
    }
}
