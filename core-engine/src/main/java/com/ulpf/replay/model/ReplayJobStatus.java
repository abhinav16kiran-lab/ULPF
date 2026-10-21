package com.ulpf.replay.model;

import java.time.LocalDateTime;

public record ReplayJobStatus(
        String jobId,
        String sourceId,
        String targetTable,
        String status,          // QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED
        long totalEstimate,
        long processed,
        long inserted,
        long skipped,
        long failed,
        int progressPercent,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        String errorMessage
) {
    public static ReplayJobStatus initial(String jobId, String sourceId, String targetTable, long totalEstimate) {
        return new ReplayJobStatus(
                jobId, sourceId, targetTable, "QUEUED",
                totalEstimate, 0, 0, 0, 0, 0,
                LocalDateTime.now(), null, null
        );
    }

    public ReplayJobStatus withProgress(long processed, long inserted, long skipped, long failed) {
        int pct = totalEstimate > 0 ? (int) Math.min(100, (processed * 100) / totalEstimate) : 100;
        return new ReplayJobStatus(
                jobId, sourceId, targetTable, "RUNNING",
                totalEstimate, processed, inserted, skipped, failed, pct,
                startedAt, null, null
        );
    }

    public ReplayJobStatus completed(long processed, long inserted, long skipped, long failed) {
        return new ReplayJobStatus(
                jobId, sourceId, targetTable, "COMPLETED",
                totalEstimate, processed, inserted, skipped, failed, 100,
                startedAt, LocalDateTime.now(), null
        );
    }

    public ReplayJobStatus failed(String error) {
        return new ReplayJobStatus(
                jobId, sourceId, targetTable, "FAILED",
                totalEstimate, processed, inserted, skipped, failed, progressPercent,
                startedAt, LocalDateTime.now(), error
        );
    }

    public ReplayJobStatus cancelled() {
        return new ReplayJobStatus(
                jobId, sourceId, targetTable, "CANCELLED",
                totalEstimate, processed, inserted, skipped, failed, progressPercent,
                startedAt, LocalDateTime.now(), "Cancelled by administrator"
        );
    }
}
