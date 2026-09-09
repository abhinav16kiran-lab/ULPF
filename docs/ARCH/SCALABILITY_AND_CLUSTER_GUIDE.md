# Universal Log Processing Framework (ULPF)
## Scalability Architecture: Partitioning, Sharding & Replication Guide

When log volume grows exponentially (terabyte to petabyte scale), ULPF relies on ClickHouse's native columnar storage mechanisms to scale horizontally without application code changes.

---

## 1. Data Partitioning (Built-in)

ULPF tables partition data physically on disk by monthly intervals:

```sql
CREATE TABLE ulpf_raw.raw_events
(
    ...
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(received_at)
ORDER BY (vendor_id, source_id, received_at, event_id)
TTL received_at + INTERVAL 7 DAY RECOMPRESS CODEC(ZSTD(15));
```

### Key Mechanics:
1. **Partition Pruning**: ClickHouse automatically skips non-matching monthly partition directories during queries. A query for `WHERE received_at >= '2026-09-01'` ignores all historical partitions from previous months without disk I/O.
2. **Automated Compression Lifecycle**:
   - Recent Data (0–7 days): Compressed with `ZSTD(1)` for high-throughput live write performance.
   - Aged Data (>7 days): ClickHouse background merge threads automatically re-compress partition chunks to ultra-dense `ZSTD(15)`, saving ~80% storage footprint.
3. **Automated Retention TTL**: Add automated deletion policies to cold partitions:
   ```sql
   ALTER TABLE ulpf_raw.raw_events MODIFY TTL received_at + INTERVAL 1 YEAR DELETE;
   ```

---

## 2. Horizontal Sharding (`Distributed` Engine)

When single-node storage limits are reached, ULPF scales out horizontally across an $N$-node ClickHouse cluster.

```text
                             Spring Boot Core Engine
                                       │
                                       │ POST /v1/events
                                       ▼
                       ClickHouse Distributed Engine
                       (ulpf_raw.raw_events_distributed)
                                       │
                  ┌────────────────────┼────────────────────┐
                  │ Sharding Key:      │ sipHash64(vendor_id)
                  ▼                    ▼                    ▼
           ┌──────────────┐     ┌──────────────┐     ┌──────────────┐
           │   Shard 1    │     │   Shard 2    │     │   Shard 3    │
           │ (Node 01/02) │     │ (Node 03/04) │     │ (Node 05/06) │
           └──────────────┘     └──────────────┘     └──────────────┘
```

### Cluster Table DDL:
1. **Underlying Local Table** (on each shard node):
   ```sql
   CREATE TABLE ulpf_raw.raw_events_local ON CLUSTER ulpf_cluster
   (
       event_id String,
       vendor_id String,
       source_id String,
       lineage_id String,
       received_at DateTime64(3),
       raw_payload String CODEC(ZSTD(1))
   )
   ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/raw_events_local', '{replica}')
   PARTITION BY toYYYYMM(received_at)
   ORDER BY (vendor_id, source_id, received_at, event_id);
   ```

2. **Distributed Entry Table** (cluster wide):
   ```sql
   CREATE TABLE ulpf_raw.raw_events_distributed ON CLUSTER ulpf_cluster AS ulpf_raw.raw_events_local
   ENGINE = Distributed(ulpf_cluster, ulpf_raw, raw_events_local, sipHash64(vendor_id));
   ```

### Key Mechanics:
- **Deterministic Sharding**: `sipHash64(vendor_id)` ensures all logs from the same vendor reside on the same shard for fast localized aggregation.
- **Scatter-Gather Execution**: Queries to `raw_events_distributed` execute concurrently across all shard nodes, combining partial results at microsecond speeds.

---

## 3. High Availability Replication (`ReplicatedMergeTree` + ClickHouse Keeper)

To guarantee zero data loss and 99.999% uptime, every shard node maintains active read/write replicas:

- **Consensus Synchronization**: ClickHouse Keeper (or ZooKeeper) coordinates log part replication asynchronously across replica nodes.
- **Automatic Failover**: If `Shard 1 (Node 01)` crashes, `Shard 1 (Node 02)` immediately takes over reads and writes without dropping incoming HTTP log batches.
- **Read Load-Balancing**: Analytical read queries (`/v1/analytics`, `/v1/analytics/search`) automatically load-balance across all active replicas.

---

## 4. Hot / Cold Tiered Storage (NVMe $\rightarrow$ Cloud S3 / Disk)

ULPF supports multi-tier storage policies to keep operating costs low at petabyte scale:

```xml
<!-- ClickHouse storage_configuration policy -->
<clickhouse>
    <storage_configuration>
        <disks>
            <nvme>
                <path>/var/lib/clickhouse/disks/nvme/</path>
            </nvme>
            <s3_cold>
                <type>s3</type>
                <endpoint>https://my-bucket.s3.amazonaws.com/clickhouse/</endpoint>
            </s3_cold>
        </disks>
        <policies>
            <hot_to_cold>
                <volumes>
                    <hot>
                        <disk>nvme</disk>
                    </hot>
                    <cold>
                        <disk>s3_cold</disk>
                    </cold>
                </volumes>
            </hot_to_cold>
        </policies>
    </storage_configuration>
</clickhouse>
```

### Table Policy Assignment:
```sql
ALTER TABLE ulpf_raw.raw_events MODIFY SETTING storage_policy = 'hot_to_cold';
```
- **Hot Tier (NVMe/SSD)**: First 7 days stored on local high-speed NVMe for maximum write & real-time search performance.
- **Cold Tier (S3 / Blob Storage)**: Data older than 7 days automatically moves to S3. Logs remain **100% queryable via standard SQL** with zero manual restore operations.

---

## 📊 Scale-Out Architecture Summary

| Scalability Mechanism | Problem Solved | ULPF Implementation |
| :--- | :--- | :--- |
| **Partitioning** | Disk scan overhead on historic ranges | `PARTITION BY toYYYYMM(received_at)` |
| **Re-Compression (TTL)** | High disk storage costs | `ZSTD(1)` live $\rightarrow$ `ZSTD(15)` after 7 days |
| **Horizontal Sharding** | Single-node CPU & RAM limits | `Distributed` Engine + `sipHash64(vendor_id)` |
| **Replication** | Hardware node failure / downtime | `ReplicatedMergeTree` + ClickHouse Keeper |
| **Tiered Storage** | Expensive SSD storage at scale | Hot NVMe $\rightarrow$ Cold AWS S3 Object Storage |
