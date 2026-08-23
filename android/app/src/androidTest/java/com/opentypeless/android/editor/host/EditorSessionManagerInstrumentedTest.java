package com.opentypeless.android.editor.host;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class EditorSessionManagerInstrumentedTest {
    @Test
    public void productionConstructorAllowsMainLooperAndRejectsWorkerThread() throws Exception {
        AtomicReference<EditorSessionManager> mainManager = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> mainManager.set(new EditorSessionManager()));
        assertNotNull(mainManager.get());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(mainManager.get()::close);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> future = executor.submit(
                    (Callable<EditorSessionManager>) EditorSessionManager::new);
            try {
                future.get();
                fail("expected main-looper rejection");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof IllegalStateException);
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
