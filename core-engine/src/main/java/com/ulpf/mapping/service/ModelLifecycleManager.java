package com.ulpf.mapping.service;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Loads the layer-4 embedding model (all-MiniLM-L6-v2) into ONNX Runtime on demand, hands out read leases for
 * inference, and unloads it after an idle timeout to give the native memory back.
 *
 * <p>If the ONNX native library cannot load on this platform (for example a musl-based image without
 * libstdc++), the JVM throws a {@link LinkageError}. That is an {@code Error}, which the layer fallback in
 * {@link MappingEngineOrchestrator} does not catch. This class turns it into an {@link IllegalStateException},
 * remembers it, and does not try again, so layer 4 is unavailable but onboarding still works.
 */
@Service
public class ModelLifecycleManager {

    private static final Logger log = LoggerFactory.getLogger(ModelLifecycleManager.class);

    @Value("${mapping.model.path:models/all-MiniLM-L6-v2/model.onnx}")
    private String modelPath = "models/all-MiniLM-L6-v2/model.onnx";

    @Value("${mapping.model.idle-timeout-ms:180000}")
    private long idleTimeoutMs = 180000L;

    private OrtEnvironment env;
    private OrtSession session;
    private long lastAccessedTime = 0;
    private final java.util.concurrent.locks.ReentrantReadWriteLock rwLock = new java.util.concurrent.locks.ReentrantReadWriteLock(true);

    private final Supplier<OrtEnvironment> environmentSupplier;
    /** Set once when the native library cannot load: a failed class initialisation cannot be retried. */
    private volatile String nativeRuntimeFailure;

    public ModelLifecycleManager() {
        this.environmentSupplier = OrtEnvironment::getEnvironment;
    }

    public ModelLifecycleManager(String modelPath) {
        this(modelPath, OrtEnvironment::getEnvironment);
    }

    /** For tests: replaces the ONNX environment lookup, which loads the native library on first use. */
    ModelLifecycleManager(String modelPath, Supplier<OrtEnvironment> environmentSupplier) {
        this.modelPath = modelPath;
        this.environmentSupplier = environmentSupplier;
    }

    /**
     * Scoped resource lease representing an active in-flight inference reader.
     * While any lease is active, the model will not be unloaded.
     */
    public class SessionLease implements AutoCloseable {
        private final OrtSession session;
        private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean(false);

        private SessionLease(OrtSession session) {
            this.session = session;
        }

        public OrtSession session() {
            if (closed.get()) {
                throw new IllegalStateException("Session lease has already been closed");
            }
            return session;
        }

        public OrtSession getSession() {
            return session();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                rwLock.readLock().unlock();
                lastAccessedTime = System.currentTimeMillis();
            }
        }
    }

    /**
     * Acquires a shared read lease on the active OrtSession for thread-safe inference.
     * Prevents model unloading while the lease is open.
     */
    public SessionLease acquireSession() {
        ensureLoaded();
        rwLock.readLock().lock();
        try {
            if (session == null) {
                rwLock.readLock().unlock();
                ensureLoaded();
                rwLock.readLock().lock();
            }
            lastAccessedTime = System.currentTimeMillis();
            return new SessionLease(session);
        } catch (RuntimeException e) {
            rwLock.readLock().unlock();
            throw e;
        }
    }

    /**
     * Idempotently loads model.onnx into C++ native heap memory if not already active.
     * Updates the last accessed timestamp to delay idle unload.
     */
    public synchronized void ensureLoaded() {
        lastAccessedTime = System.currentTimeMillis();

        if (session != null) {
            return;
        }
        if (nativeRuntimeFailure != null) {
            throw new IllegalStateException("ONNX native runtime unavailable: " + nativeRuntimeFailure);
        }

        try {
            File modelFile = Path.of(modelPath).toFile();
            if (!modelFile.exists()) {
                throw new IllegalStateException("ONNX model file not found at path: " + modelFile.getAbsolutePath());
            }

            log.info("Loading ONNX model into native C++ heap from: {}", modelFile.getAbsolutePath());
            long startTime = System.currentTimeMillis();

            env = environmentSupplier.get();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            session = env.createSession(modelFile.getAbsolutePath(), options);

            long duration = System.currentTimeMillis() - startTime;
            log.info("ONNX model successfully loaded into native RAM in {} ms", duration);

        } catch (LinkageError e) {
            nativeRuntimeFailure = e.toString();
            log.error("ONNX native runtime unavailable on this platform; mapping layer 4 is turned off until restart", e);
            unloadModel();
            throw new IllegalStateException("ONNX native runtime unavailable: " + nativeRuntimeFailure, e);
        } catch (Exception e) {
            log.error("Failed to load ONNX model into native memory", e);
            unloadModel();
            throw new RuntimeException("Failed to load ONNX model", e);
        }
    }

    /**
     * Synchronously closes the ONNX native session and environment, releasing C++ heap memory back to the OS.
     * Waits for all active inference read leases to finish before unloading.
     */
    public void unloadModel() {
        rwLock.writeLock().lock();
        try {
            unloadModelInternal();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    private synchronized void unloadModelInternal() {
        if (session == null && env == null) {
            return;
        }

        log.info("Unloading ONNX model and releasing C++ native memory back to OS...");

        if (session != null) {
            try {
                session.close();
            } catch (Exception e) {
                log.warn("Error closing ONNX OrtSession", e);
            } finally {
                session = null;
            }
        }

        if (env != null) {
            try {
                env.close();
            } catch (Exception e) {
                log.warn("Error closing ONNX OrtEnvironment", e);
            } finally {
                env = null;
            }
        }

        log.info("ONNX model native RAM release complete (0 MB allocated).");
    }

    /**
     * Checks if the model is currently active in native RAM.
     */
    public synchronized boolean isLoaded() {
        return session != null;
    }

    /**
     * Returns the active OrtSession (ensuring loaded first).
     * Note: Prefer acquireSession() with try-with-resources for thread-safe inference protection.
     */
    public synchronized OrtSession getSession() {
        ensureLoaded();
        return session;
    }

    /**
     * Returns the number of currently active read leases.
     */
    public int getActiveLeaseCount() {
        return rwLock.getReadLockCount();
    }

    /**
     * Background scheduler running every 60 seconds.
     * Automatically unloads the model if loaded and idle for > idleTimeoutMs.
     * Uses tryLock() to ensure it never interrupts or closes an active inference lease.
     */
    @Scheduled(fixedDelay = 60000)
    public void checkIdleTimeout() {
        if (!isLoaded()) {
            return;
        }

        if (System.currentTimeMillis() - lastAccessedTime > idleTimeoutMs) {
            if (rwLock.writeLock().tryLock()) {
                try {
                    if (isLoaded() && (System.currentTimeMillis() - lastAccessedTime > idleTimeoutMs)) {
                        log.info("ONNX model has been idle for over {} ms. Triggering automatic memory release...", idleTimeoutMs);
                        unloadModelInternal();
                    }
                } finally {
                    rwLock.writeLock().unlock();
                }
            } else {
                log.debug("Skipping scheduled ONNX model unload: active inference in progress.");
            }
        }
    }
}
