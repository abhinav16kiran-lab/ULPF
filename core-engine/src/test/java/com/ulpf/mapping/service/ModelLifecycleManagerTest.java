package com.ulpf.mapping.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelLifecycleManagerTest {

    private ModelLifecycleManager lifecycleManager;

    @BeforeEach
    void setUp() {
        lifecycleManager = new ModelLifecycleManager();
    }

    @AfterEach
    void tearDown() {
        lifecycleManager.unloadModel();
    }

    @Test
    void testModelLoadAndUnloadLifecycle() {
        // Initial state: model is not in RAM
        assertFalse(lifecycleManager.isLoaded(), "Model should initially not be loaded in memory");

        // Idempotently load model into native C++ heap
        lifecycleManager.ensureLoaded();

        assertTrue(lifecycleManager.isLoaded(), "Model should be marked as loaded in memory");
        assertNotNull(lifecycleManager.getSession(), "OrtSession should be non-null when loaded");

        // Calling ensureLoaded again should be idempotent
        lifecycleManager.ensureLoaded();
        assertTrue(lifecycleManager.isLoaded());

        // Unload model explicitly and release native memory back to OS
        lifecycleManager.unloadModel();

        assertFalse(lifecycleManager.isLoaded(), "Model should be unloaded after calling unloadModel()");
    }

    @Test
    void testSessionLeasePreventsUnloadUntilClosed() throws Exception {
        ModelLifecycleManager.SessionLease lease = lifecycleManager.acquireSession();
        assertNotNull(lease.session(), "Lease session should be valid and non-null");
        assertTrue(lifecycleManager.isLoaded(), "Model should be loaded");
        org.junit.jupiter.api.Assertions.assertEquals(1, lifecycleManager.getActiveLeaseCount(), "Active lease count should be 1");

        java.util.concurrent.atomic.AtomicBoolean unloadCompleted = new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread unloadThread = new Thread(() -> {
            lifecycleManager.unloadModel();
            unloadCompleted.set(true);
        });
        unloadThread.start();

        // Give unload thread time to attempt acquiring write lock
        Thread.sleep(150);
        assertFalse(unloadCompleted.get(), "unloadModel() should be blocked while an active SessionLease is open");
        assertTrue(lifecycleManager.isLoaded(), "Model should remain loaded while lease is held");

        // Now release the lease
        lease.close();
        org.junit.jupiter.api.Assertions.assertEquals(0, lifecycleManager.getActiveLeaseCount(), "Active lease count should drop to 0");

        // Wait for unload thread to finish
        unloadThread.join(2000);
        assertTrue(unloadCompleted.get(), "unloadModel() should finish promptly once the lease is released");
        assertFalse(lifecycleManager.isLoaded(), "Model should be unloaded after release");
    }

    @Test
    void testCheckIdleTimeoutSkippedWhileLeaseActive() {
        try (ModelLifecycleManager.SessionLease lease = lifecycleManager.acquireSession()) {
            assertTrue(lifecycleManager.isLoaded());
            org.junit.jupiter.api.Assertions.assertEquals(1, lifecycleManager.getActiveLeaseCount());

            // Even if idleTimeoutMs has elapsed, checkIdleTimeout() should skip unloading
            lifecycleManager.checkIdleTimeout();
            assertTrue(lifecycleManager.isLoaded(), "checkIdleTimeout() must not unload model while lease is open");
        }

        org.junit.jupiter.api.Assertions.assertEquals(0, lifecycleManager.getActiveLeaseCount());
    }

    @Test
    void testMultipleConcurrentLeases() {
        try (ModelLifecycleManager.SessionLease lease1 = lifecycleManager.acquireSession();
             ModelLifecycleManager.SessionLease lease2 = lifecycleManager.acquireSession();
             ModelLifecycleManager.SessionLease lease3 = lifecycleManager.acquireSession()) {

            assertNotNull(lease1.session());
            assertNotNull(lease2.session());
            assertNotNull(lease3.session());
            org.junit.jupiter.api.Assertions.assertEquals(3, lifecycleManager.getActiveLeaseCount(), "Should have 3 concurrent readers");
        }

        org.junit.jupiter.api.Assertions.assertEquals(0, lifecycleManager.getActiveLeaseCount(), "All leases should be released");
    }

    /**
     * The container bug of 2026-11-15: on a musl image the ONNX native library cannot load, and the JVM throws
     * UnsatisfiedLinkError, an Error that passed every catch (Exception) and failed the onboarding request.
     * The manager must turn it into an exception that the layer fallback handles.
     */
    @Test
    void aNativeRuntimeThatCannotLoadBecomesAnIllegalStateException(@TempDir Path dir) throws Exception {
        Path model = Files.createFile(dir.resolve("model.onnx"));
        ModelLifecycleManager broken = new ModelLifecycleManager(model.toString(), () -> {
            throw new UnsatisfiedLinkError("Error loading shared library libstdc++.so.6: No such file or directory");
        });

        IllegalStateException e = assertThrows(IllegalStateException.class, broken::ensureLoaded);

        assertTrue(e.getMessage().contains("ONNX native runtime unavailable"), e.getMessage());
        assertFalse(broken.isLoaded());
    }

    @Test
    void afterTheNativeRuntimeFailedOnceItIsNotLoadedAgain(@TempDir Path dir) throws Exception {
        Path model = Files.createFile(dir.resolve("model.onnx"));
        AtomicInteger attempts = new AtomicInteger();
        ModelLifecycleManager broken = new ModelLifecycleManager(model.toString(), () -> {
            attempts.incrementAndGet();
            throw new UnsatisfiedLinkError("libstdc++.so.6 missing");
        });

        assertThrows(IllegalStateException.class, broken::ensureLoaded);
        assertThrows(IllegalStateException.class, broken::ensureLoaded);
        assertThrows(IllegalStateException.class, broken::acquireSession);

        assertEquals(1, attempts.get(), "a failed native load must not be retried on every field");
    }
}
