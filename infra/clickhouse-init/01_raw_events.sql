CREATE DATABASE IF NOT EXISTS ulpf_raw;

CREATE DATABASE IF NOT EXISTS ulpf_events;

-- Raw events table: Unparsed raw log payloads.
-- Re-compressed with ultra-high ZSTD(15) after 7 days (saves ~80% storage, 0 row deletions).
CREATE TABLE IF NOT EXISTS ulpf_raw.raw_events
(
    event_id         STRING,
    lineage_id       STRING,
    vendor_id        String,
    source_id        STRING,
    mapping_version  Nullable(UInt32),
    received_at      DateTime64(3) DEFAULT now64(3),
    raw_payload      String CODEC(ZSTD(1))
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(received_at)
ORDER BY (vendor_id, source_id, received_at, event_id)
TTL received_at + INTERVAL 7 DAY RECOMPRESS CODEC(ZSTD(15));