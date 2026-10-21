package com.ulpf.dataplane.service;

import java.time.Instant;
import java.util.Set;

/**
 * Structured payload representing an ingestion alert, schema drift notification,
 * or failure digest suitable for in-app dispatch and external webhook forwarding.
 */
public record AlertPayload(
        String alertType,
        String severity,
        String sourceId,
        String sourceName,
        String vendorId,
        String vendorName,
        String tableName,
        String errorReason,
        long affectedCount,
        Set<String> unmappedKeys,
        String startedAt,
        String message,
        String recommendedAction
) {
    public AlertPayload(
            String alertType,
            String severity,
            String sourceId,
            String sourceName,
            String vendorId,
            String vendorName,
            String tableName,
            String errorReason,
            long affectedCount,
            Set<String> unmappedKeys,
            Instant startedAt,
            String message,
            String recommendedAction
    ) {
        this(
                alertType,
                severity,
                sourceId,
                sourceName,
                vendorId,
                vendorName,
                tableName,
                errorReason,
                affectedCount,
                unmappedKeys,
                startedAt != null ? startedAt.toString() : null,
                message,
                recommendedAction
        );
    }
}
