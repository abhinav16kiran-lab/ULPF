package com.ulpf.dataplane.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SensorTelemetryEvaluatorTest {

    private SensorTelemetryEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new SensorTelemetryEvaluator();
        evaluator.clear();
    }

    @Test
    void testFirstReadingAlwaysEmitsAndRotatesLineage() {
        String sourceId = "src_sensor_001";
        String initialLineage = evaluator.getOrCreateLineageId(sourceId);

        var res = evaluator.evaluate(sourceId, 10.0, 2.0, 60000L);

        assertTrue(res.shouldEmit());
        assertEquals(initialLineage, res.lineageId());
        assertEquals(10.0, res.value());

        // Lineage ID should have rotated for the next window
        String nextLineage = evaluator.getOrCreateLineageId(sourceId);
        assertNotEquals(initialLineage, nextLineage);
    }

    @Test
    void testDeltaThresholdEmissionAndSuppression() {
        String sourceId = "src_sensor_002";

        // 1st reading -> emit (10.0)
        var res1 = evaluator.evaluate(sourceId, 10.0, 2.0, 60000L);
        assertTrue(res1.shouldEmit());
        String lineage1 = res1.lineageId();

        // 2nd reading -> delta (11.0 - 10.0 = 1.0 < 2.0) -> SUPPRESSED
        var res2 = evaluator.evaluate(sourceId, 11.0, 2.0, 60000L);
        assertFalse(res2.shouldEmit());
        String lineage2 = res2.lineageId();
        assertNotEquals(lineage1, lineage2); // res2 is in the second aggregate window

        // 3rd reading -> delta (12.5 - 10.0 = 2.5 >= 2.0) -> EMITTED!
        var res3 = evaluator.evaluate(sourceId, 12.5, 2.0, 60000L);
        assertTrue(res3.shouldEmit());
        assertEquals(lineage2, res3.lineageId()); // res3 emitted the second window!
        assertEquals(12.5, res3.value());

        // 4th reading -> next window started (lineage3), 13.0 vs 12.5 (0.5 < 2.0) -> SUPPRESSED
        var res4 = evaluator.evaluate(sourceId, 13.0, 2.0, 60000L);
        assertFalse(res4.shouldEmit());
        assertNotEquals(lineage2, res4.lineageId());
    }

    @Test
    void testMaxIntervalTimeoutEmission() throws InterruptedException {
        String sourceId = "src_sensor_003";

        // 1st reading -> emit (100.0)
        var res1 = evaluator.evaluate(sourceId, 100.0, 50.0, 100L); // max_interval 100ms
        assertTrue(res1.shouldEmit());

        // Wait 150ms to exceed max interval
        Thread.sleep(150L);

        // 2nd reading with same value (100.0) -> delta is 0, but max_interval TIMEOUT -> EMITTED!
        var res2 = evaluator.evaluate(sourceId, 100.0, 50.0, 100L);
        assertTrue(res2.shouldEmit());
        assertEquals(100.0, res2.value());
    }
}
