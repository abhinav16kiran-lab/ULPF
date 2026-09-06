package com.ulpf.dataplane.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe evaluator managing per-source telemetry windows, delta thresholds,
 * max-interval emissions, and lineage ID rotation.
 */
@Component
public class SensorTelemetryEvaluator {

    public record EvaluationResult(boolean shouldEmit, String lineageId, Double value) {}

    private static class SourceSensorState {
        Double lastEmittedValue = null;
        long lastEmittedTimeMs = 0L;
        String currentLineageId = UUID.randomUUID().toString();
    }

    private final Map<String, SourceSensorState> stateMap = new ConcurrentHashMap<>();

    /**
     * Obtains or initializes the active lineage ID for a log source window.
     */
    public String getOrCreateLineageId(String sourceId) {
        if (sourceId == null || sourceId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return stateMap.computeIfAbsent(sourceId, k -> new SourceSensorState()).currentLineageId;
    }

    /**
     * Evaluates whether an incoming sensor reading satisfies delta or max-interval emission criteria.
     * If emitted, updates last emitted state and rotates to a new lineage ID for the next window.
     */
    public EvaluationResult evaluate(String sourceId, Double currentVal, Double delta, Long maxIntervalMs) {
        if (sourceId == null || sourceId.isBlank()) {
            return new EvaluationResult(true, UUID.randomUUID().toString(), currentVal);
        }

        SourceSensorState state = stateMap.computeIfAbsent(sourceId, k -> new SourceSensorState());

        synchronized (state) {
            long now = System.currentTimeMillis();
            long intervalMs = (maxIntervalMs != null && maxIntervalMs > 0) ? maxIntervalMs : 60000L;

            boolean firstEmission = (state.lastEmittedValue == null || state.lastEmittedTimeMs == 0L);
            boolean deltaExceeded = false;
            if (!firstEmission && currentVal != null && delta != null) {
                deltaExceeded = Math.abs(currentVal - state.lastEmittedValue) >= delta;
            }

            boolean intervalExceeded = false;
            if (!firstEmission) {
                intervalExceeded = (now - state.lastEmittedTimeMs) >= intervalMs;
            }

            boolean shouldEmit = firstEmission || deltaExceeded || intervalExceeded;

            String activeLineageId = state.currentLineageId;

            if (shouldEmit) {
                if (currentVal != null) {
                    state.lastEmittedValue = currentVal;
                } else if (state.lastEmittedValue == null) {
                    state.lastEmittedValue = 0.0;
                }
                state.lastEmittedTimeMs = now;
                // Rotate lineage ID for the subsequent aggregation window
                state.currentLineageId = UUID.randomUUID().toString();
                return new EvaluationResult(true, activeLineageId, currentVal);
            } else {
                return new EvaluationResult(false, activeLineageId, currentVal);
            }
        }
    }

    /**
     * Clears all per-source state (useful for unit tests).
     */
    public void clear() {
        stateMap.clear();
    }
}
