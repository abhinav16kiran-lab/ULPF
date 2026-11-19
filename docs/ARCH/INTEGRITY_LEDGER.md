# Integrity Ledger (Merkle blocks over raw events)

- **Created:** 2026-11-19
- **Last verified against the code:** 2026-11-19, commit `7f843a5` (the code is the same as `main` at `8412f87`)
- **How it was verified:** by reading every class of `com.ulpf.integrity`, every caller, the SQLite schema, the ClickHouse table, the frontend pages and the tests. Facts that were also measured or run say so. Facts found only by reading say "found by reading; not reproduced".
- **Rule for editors:** when you change this feature, update this file in the same change, and update the two dates and the commit above. If the code and this file disagree, the code is right; fix this file.

## 1. What it is, in one paragraph

Each time the engine stores a batch of raw events in ClickHouse, it also writes a small record ("block") per source into SQLite. A block holds a SHA-256 fingerprint (the Merkle root) of exactly those raw events. Later, an admin can ask the engine to read the same events back from ClickHouse, compute the fingerprint again, and compare. If one byte of a stored event (its ids or its payload) has changed, the fingerprints differ, and the engine reports the block as tampered.

## 2. Why it exists

- The SIH problem statement (`docs/ARCH/REQUIREMENTS_DIRECTLY_FROM_SIH_PORTAL.txt`) asks to preserve "the original event data for forensic and compliance purposes", to "preserve complete raw event data without information loss" (a), and to "maintain traceability between normalized and original events" (d). The ledger is ULPF's answer to "prove that the raw events were not changed after they arrived".
- The SIH text does not ask for a tamper-proof ledger or a blockchain by name. `docs/db/DATABASE_SCHEMA.md` called it an "NTRO Blockchain requirement"; no source for that exists in the repo.

## 3. The parts

| Part | File | What it does |
|---|---|---|
| `MerkleTreeCalculator` | `core-engine/src/main/java/com/ulpf/integrity/service/MerkleTreeCalculator.java` | `hashSha256(String)` (line 54): SHA-256 of the UTF-8 bytes, as 64 lowercase hex characters. `calculateMerkleRoot(List<String>)` (line 25): builds the binary tree. |
| `BatchIntegrityService` | `.../integrity/service/BatchIntegrityService.java` | `processRawBatch` (line 67): makes the blocks. `verifyBlockIntegrity` (line 146): checks one block. `verifyAllBlocks` (line 303): checks many. `getRecentBlocks` (line 293): lists blocks. |
| `IntegrityRepository` | `.../integrity/repository/IntegrityRepository.java` | All SQLite access: `saveBlock` (42), `findLatestBlockHash` (73), `findBlockById` (92), `findExistingBlock` (117), `findAllBlocks` (144), `findBlocksBySourceId` (162), `searchBlocks` (182). Holds `GENESIS_BLOCK_HASH` (64 zeros, line 23). |
| `IntegrityController` | `.../integrity/controller/IntegrityController.java` | The REST API under `/v1/integrity` (section 7). |
| The caller | `.../common/db/ClickHouseIngestionRepository.java`, `flush()` (line 252) | Calls `processRawBatch` after a raw batch is stored (lines 295 and 321). The only caller. `BatchIntegrityService` is injected by the setter `setBatchIntegrityService` (line 102). |
| SQLite table | `core-engine/src/main/resources/sqlite/schema.sql`, line 177 | `batch_integrity_blocks` (section 4). |
| ClickHouse table | `infra/clickhouse-init/01_raw_events.sql` | `ulpf_raw.raw_events`: the data that the blocks protect. |
| UI | `frontend/src/pages/IntegrityConsolePage.jsx` (route `/integrity` in `App.jsx`, link in `Navbar.jsx`) | Lists blocks, searches, verifies one block or all. `AdminDashboardPage.jsx` shows the block count. |

## 4. Data model

**SQLite `batch_integrity_blocks`** (`schema.sql` line 177):

| Column | Type | Content |
|---|---|---|
| `block_id` | `INTEGER PRIMARY KEY AUTOINCREMENT` | Global counter over all sources. |
| `source_id` | `TEXT NOT NULL` | The source of every event in the block. No foreign key to `sources`. |
| `event_count` | `INTEGER NOT NULL` | Number of events in the block (before any de-duplication). |
| `first_event_id`, `last_event_id` | `TEXT NOT NULL` | `event_id` of the first and last event after sorting (section 5, step 2). |
| `merkle_root` | `TEXT NOT NULL` | The root, 64 hex characters. |
| `previous_block_hash` | `TEXT NOT NULL` | The **`merkle_root` of the previous block of the same source**, or 64 zeros for the first block of a source. It is not a hash of the previous block's row. |
| `created_at` | `TIMESTAMP` | Set from `LocalDateTime.now()` in `processRawBatch`. sqlite-jdbc stores it as epoch milliseconds (observed in a test database on 2026-11-16). |

Index: `idx_integrity_source_created ON (source_id, created_at)` (line 188). No query filters or sorts on `created_at`, so no query can use the second column of this index.

**ClickHouse `ulpf_raw.raw_events`**: the columns that the ledger uses are `event_id`, `lineage_id`, `vendor_id`, `source_id`, `received_at` (`DateTime64(3)`, milliseconds) and `raw_payload`. `ORDER BY (vendor_id, source_id, received_at, event_id)`. The TTL only recompresses after 7 days; it deletes nothing, so old blocks keep their data.

## 5. Write path: how a block is made

1. **Trigger.** `ClickHouseIngestionRepository.flush()` is `synchronized`. It drains the whole raw queue into one batch and inserts it into `ulpf_raw.raw_events`. Batches come from `POST /v1/events` (`EventIngestionService`, `RawEventRecord` built at line 198) and from bulk file import (`AnalyticsService`, `enqueue` at line 428, then `flush()` at line 432).
   - After a successful insert, `flush()` calls `processRawBatch(batch)` (line 295).
   - If the insert throws, `flush()` asks ClickHouse whether the first event of the batch exists (line 307). If it does, the insert was committed before a timeout, so it calls `processRawBatch(batch)` too (line 321). If not, the batch goes back on the queue and gets its block when a later flush stores it.
   - Any exception from `processRawBatch` is logged at WARN and dropped. The events stay stored, without a block.
   - Because both calls happen inside the `synchronized` flush, blocks are made one batch at a time. The chain (step 5) depends on this.
2. **Group and sort** (`processRawBatch`, lines 75-88). Events with a null `source_id` are skipped. The rest are grouped by `source_id`. Each group is sorted by `receivedAt` (nulls first), then by `eventId`. `receivedAt` here is the Java `LocalDateTime` from the request, with sub-millisecond precision.
3. **Leaf hashes** (lines 91-99). For each event: `SHA-256(eventId + vendorId + sourceId + lineageId + rawPayload)`. The five strings are joined with no separator; a null becomes `""`. `received_at` and `mapping_version` are not part of the hash.
4. **Merkle root** (`calculateMerkleRoot`). Take the list of leaf hashes (hex strings). While more than one is left: if the count is odd, copy the last one; then replace each pair with `SHA-256(left + right)`, where `left + right` is the two 64-character hex strings joined (128 characters), not the raw bytes. An empty list gives `SHA-256("")`.
5. **Chain link** (line 105). `findLatestBlockHash(sourceId)` returns the `merkle_root` of the source's block with the highest `block_id`. If the source has none, or the query fails, it returns `GENESIS_BLOCK_HASH` (64 zeros).
6. **Duplicate check** (line 110). `findExistingBlock(sourceId, firstEventId, lastEventId, merkleRoot)`. If a block with these four values exists, no new block is written. This covers a batch that is processed twice.
7. **Save** (`saveBlock`). `INSERT`, then `SELECT last_insert_rowid()` for the new `block_id`.

Each block costs 4 SQLite statements: 2 lookups, 1 insert and 1 `last_insert_rowid()`. A batch with N sources makes N blocks.

## 6. Read path: how a block is verified

`verifyBlockIntegrity(blockId)` (line 146):

1. `findBlockById`. If there is no row, the result is `NOT_FOUND` (the controller answers 404).
2. `fetchRawEventRecordsFromClickHouse` (line 229) reads the events back from `ulpf_raw.raw_events`:
   - If `first_event_id == last_event_id`: the one event with that `source_id` and `event_id`.
   - Otherwise, the **time window**: all events of the source with `received_at` between the `received_at` of the first event and that of the last event. Each of those two values comes from a sub-query by `event_id` alone, without the source. The query keeps 1 row per `event_id` (`LIMIT 1 BY event_id`), sorts by `received_at, event_id`, and returns at most `event_count` rows.
   - If the window gives no rows: the **fallback**, the oldest `event_count` events of the source.
   - If ClickHouse fails: an empty list.
3. If no rows came back: status `NO_DATA`, with `isTampered = true`.
4. Sort the rows again by `received_at` and `event_id`, drop repeated `event_id`s, compute the leaf hashes and the root exactly as in section 5.
5. Compare with the stored `merkle_root` (case-insensitive): equal gives `VALID`, different gives `TAMPERED_DETECTED`.

The check does **not** read `previous_block_hash`. Nothing in the code checks the chain.

`verifyAllBlocks()` (line 303) loads the newest 100,000 blocks (`findAllBlocks(100000)`), verifies each in a `parallelStream()` (the JVM's common fork-join pool), and returns the totals and the failed blocks. `NO_DATA` counts as tampered. Blocks older than the newest 100,000 are never checked, and the result does not say so.

## 7. API, roles and UI

| Endpoint | Method | What it does |
|---|---|---|
| `/v1/integrity/blocks?limit=50&sourceId=&search=` | GET | `{"blocks": [...]}`. `search` matches `block_id`, `source_id`, `first_event_id` or `last_event_id` exactly; it wins over `sourceId`. `limit` has no upper bound. |
| `/v1/integrity/verify/{blockId}` | POST | One `VerificationResult`, or 404. |
| `/v1/integrity/verify-all` | POST | One `BulkVerificationResult`. |

- **Roles:** the class-level `@PreAuthorize("hasRole('ADMIN') or hasRole('USER')")` applies to all three, `verify-all` included. `SpringSecurity` adds only `authenticated()`. There is no filter by vendor or source: a `USER` sees and verifies the blocks of every source.
- **UI:** `App.jsx` allows the `/integrity` page for `ADMIN`, `USER` and `VENDOR`. A `VENDOR` can open the page, but the API answers 403.
- `AnalyticsPage.jsx` (about line 1586) shows "Integrity Check: VERIFIED (SHA-256)". That text is fixed in the JSX; nothing is verified there.

## 8. What it protects against, and what it does not

**Detected** (by `verify`):
- A change to `raw_payload`, `event_id`, `vendor_id`, `source_id` or `lineage_id` of a stored event in ClickHouse.
- A deleted event, if the remaining rows still fill the window: the root then differs.

**Not detected:**
- **Changes in SQLite.** Whoever can write ClickHouse in the same deployment can usually write SQLite too (both are volumes of the same stack). They can change the events and then the stored `merkle_root`, or delete the block row. The chain does not help, because nothing verifies it.
- **A deleted block,** or events that never got a block (because `processRawBatch` failed, or because a lookup error restarted the chain at genesis).
- **Changes to `received_at` or `mapping_version`,** because they are not in the hash. A changed `received_at` can move an event out of the window, which then shows as tampered or as `NO_DATA`.
- **An extra copy of an event** with the same `event_id`. Verification drops repeated ids, by design: retried flushes can store an event twice.

In short: the ledger detects edits to ClickHouse raw rows by someone who does not also edit SQLite. It is tamper-evident for one store, not a tamper-proof chain.

## 9. Known problems

Each item says how it was established.

**Performance**
1. **`findLatestBlockHash` reads every block of the source.** The index has `created_at` where the query needs `block_id`, so SQLite reads all blocks of the source and sorts them. Measured on 2026-11-16: 0.044 ms with 200 blocks, 1.45 ms with 2,424, and 12.35 ms with 21,339 (linear). The knee of `POST /v1/events` fell from 400 to 300 events/s on that database (`docs/perf/RESULTS.md`).
2. **`findExistingBlock` reads every block of the source too.** It filters on 4 columns, but only `source_id` is indexed, and in the normal case (no duplicate) it finds nothing, so it reads all blocks of the source. Found by reading; not timed.
3. **The work runs inside the flush lock.** Request threads wait behind it (the lock convoy in `docs/perf/RESULTS.md`, "Profile: why ULPF stalls"). About 30% of the time inside the lock was SQLite.
4. **Verification sub-queries search `event_id` across the whole table,** without vendor or source, and `event_id` is not a prefix of the ClickHouse sort key. So each windowed verify reads the whole table twice. `verify-all` runs this for up to 100,000 blocks in parallel. The same pattern is in the timeout probe of `flush()` (line 307). Found by reading; not timed.

**Correctness**
5. **False `TAMPERED_DETECTED` from timestamp precision.** Blocks are sorted by the Java `receivedAt` (microseconds or finer). Verification sorts by `received_at` from ClickHouse, which keeps milliseconds. When two events of one source in one batch fall in the same millisecond, and their `event_id` order differs from their time order, the leaf order differs and the root does not match. Found by reading; not reproduced.
6. **False `TAMPERED_DETECTED` from overlapping windows.** Request threads take `receivedAt` before they enqueue, so a batch can hold an event that is older than the newest event of the previous batch. The previous block's window then also covers that event. With `LIMIT event_count`, a foreign event pushes out one of the block's own events. Found by reading; not reproduced.
7. **The chain is written but never checked,** and `previous_block_hash` links only the previous root, not the previous block's row. Read from the code.
8. **Silent gaps.** A failed `processRawBatch` is only a WARN. A failed `findLatestBlockHash` silently starts a new chain at genesis. No metric counts either. Read from the code.
9. **Bulk import ids are short.** `AnalyticsService` makes `event_id` as `"bulk_evt_"` plus 8 hex characters (32 bits). In a large import, two events can share an id. Verification then drops one of them, and the block shows as tampered. Found by reading; not reproduced.
10. **Hash-format weaknesses** (low risk today): the leaf joins fields with no separator, and the tree copies the last node at odd levels, so a list ending in a repeated leaf has the same root as the list without the repeat. Read from the code.

**Access**
11. Any `USER` can list and verify the blocks of every source, and can start `verify-all`, which is expensive (item 4). Read from the code.

## 10. Tests

| Test class | What it covers |
|---|---|
| `MerkleTreeCalculatorTest` | Root for 1 leaf, an even and an odd count; a changed payload changes the root. |
| `BatchIntegrityServiceTest` | A block is made and chained to the previous root; verification gives `NOT_FOUND`, `NO_DATA`, `VALID`, or `TAMPERED_DETECTED` for changed data; sorting and de-duplication; a repeated batch reuses the existing block; `verifyAllBlocks` totals. |
| `IntegrityControllerTest` | The 3 endpoints, with the service mocked. |
| `ClickHouseIngestionRepositoryTest` | `flush()` calls `processRawBatch` once after a successful insert, and not when the insert fails and the batch is re-enqueued. |

No test covers: the SQLite query plans (problems 1 and 2), events within the same millisecond (5), overlapping windows (6), the chain check (7), or roles per endpoint (11). All tests mock ClickHouse.

## 11. Docs that disagreed with the code (found 2026-11-19)

- `docs/db/DATABASE_SCHEMA.md`: listed `block_id` as a TEXT UUID and a `block_hash` column that does not exist, left out `first_event_id` and `last_event_id`, and claimed an "NTRO Blockchain requirement". Fixed on 2026-11-19.
- `docs/ARCH/API_SPECIFICATION.md` and `README.md`: said that `verify-all` needs `ADMIN`. The code allows `USER` too. The API spec was fixed on 2026-11-19. `README.md` is for users, so the team lead decides on it.
- `README.md` §5: says that tamper audits detect "unauthorized SQL edits". They detect edits to ClickHouse raw rows only (section 8). The team lead decides.
- `docs/ARCH/ARCHITECTURE.md` §3.4: said that blocks "chain block hashes" and called the auditor "high-speed". Fixed on 2026-11-19 to point here.
- `docs/frontend/frontend.md`: lists the `/integrity` page for `VENDOR`. That is true of the route, but the API refuses vendors (section 7). Noted there on 2026-11-19.

## 12. Change log of this file

- 2026-11-19: created from the code at `7f843a5`.
