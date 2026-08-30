CREATE TABLE IF NOT EXISTS raw_events
(
    event_id         String,
    vendor_id        String,
    mapping_version  Nullable(UInt32),
    received_at      DateTime64(3) DEFAULT now64(3),
    raw_payload      String
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(received_at)
ORDER BY (vendor_id, received_at, event_id)
