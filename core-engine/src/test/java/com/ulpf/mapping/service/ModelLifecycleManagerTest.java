package com.ulpf.mapping.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
