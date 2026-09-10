CREATE DATABASE IF NOT EXISTS ulpf_raw;

CREATE DATABASE IF NOT EXISTS ulpf_events;

-- Raw events table: Unparsed raw log payloads.
-- Re-compressed with ultra-high ZSTD(15) after 7 days (saves ~80% storage, 0 row deletions).
CREATE TABLE IF NOT EXISTS ulpf_raw.raw_events
(
    event_id         String,
    lineage_id       String,
    vendor_id        String,
    source_id        String,
    mapping_version  Nullable(UInt32),
    received_at      DateTime64(3) DEFAULT now64(3),
    raw_payload      String CODEC(ZSTD(1)),
    INDEX idx_raw_token raw_payload TYPE tokenbf_v1(30720, 2, 0) GRANULARITY 1
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(received_at)
ORDER BY (vendor_id, source_id, received_at, event_id)
TTL received_at + INTERVAL 7 DAY RECOMPRESS CODEC(ZSTD(15));

-- Canonical events table: Mapped ECS events with unmapped leftovers.
CREATE TABLE IF NOT EXISTS ulpf_events.canonical_events
(
    event_id          String,
    lineage_id        String,
    vendor_id         String,
    source_id         String,
    mapping_version   Nullable(UInt32),
    timestamp         DateTime64(3) DEFAULT now64(3),
    numeric_value     Nullable(Float64),
    canonical_payload String CODEC(ZSTD(1)),
    raw_unmapped      String CODEC(ZSTD(1))
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(timestamp)
ORDER BY (vendor_id, source_id, timestamp, event_id);