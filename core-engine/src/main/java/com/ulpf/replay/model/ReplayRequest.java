package com.ulpf.replay.model;

import java.time.LocalDateTime;

public record ReplayRequest(
        LocalDateTime fromTime,
        LocalDateTime toTime,
        Integer chunkSize
) {
    public int effectiveChunkSize() {
        if (chunkSize == null || chunkSize <= 0) {
            return 1000;
        }
        return Math.min(chunkSize, 10000);
    }
}
