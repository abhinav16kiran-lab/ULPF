# Ingestion performance: measured results

This file holds the measured numbers for `POST /v1/events` (backlog item 13). Each section says what was measured, on what, how, and how far you can trust it. If the code, the host or the method changes, measure again and add a new section. Do not edit old numbers.

- Plan and work log: `docs/plans/2026-11-13-ingestion-throughput.md`.
- Method and tool choices, with sources: `docs/research/2026-11-13-profiling-ingestion.md`.
- The performance kit that produced these numbers is in `perf/`. It is git-ignored and lives on the team lead's machine; `perf/README.md` there explains how to run it. Raw data for each run is in `perf/results/<run-id>/` on that machine.

## How a step is judged

A run offers load in steps at a fixed rate with k6 (open model: k6 does not slow down when the server does). A step passes when all of these hold, in the judged window (from 30 s after the step start to 5 s before its planned end):

1. k6 dropped no request, and every request got `202`.
2. **P1** (the 1st percentile of the per-second count of stored events, counted by ClickHouse from `received_at`) is at least 98% of the offered rate. Seconds without events count as 0.
3. ClickHouse delayed or rejected no insert.

- **The knee** is the highest rate in the unbroken run of passing steps.
- **Lost** is the number of `202` responses minus the stored raw rows. It must be 0.
- A step is **INVALID**, not failed, if:
  - k6 used more than 80% of its CPUs;
  - the memory guard stopped it (less than 2 GB of RAM free).

## Test host and setup (all results below, unless a section says otherwise)

- **Machine:** Intel i5-12500H (16 CPUs: 8 performance-core threads, 8 efficiency cores), 15 GB RAM, Ubuntu 22.04, kernel 6.8.
- **Power:** governor `performance`, on AC power.
- **Containers:** rootless Podman 3.4.4 (slirp4netns). The core engine reaches ClickHouse through `host.containers.internal`.
- **CPU pinning:** core engine on CPUs 0-7, ClickHouse (and nginx) on 8-13, k6 on 14-15.
- **Images:**
  - core engine: `eclipse-temurin:21-jre`, with no JVM flags except JFR and the GC log (`settings=default`), so a G1 default heap;
  - ClickHouse: 26.3 with `async_insert=1`, `wait_for_async_insert=1` and `async_insert_busy_timeout_ms=200`.
- **Load:** k6 v2.3.0, sending to the core engine on `:8080` (not through nginx).
- **Event mix** (weights): JSON 40, Syslog 20, CEF 15, LEEF 15, JSON with 2 fields unknown to the mapping 10. There are 5 sources, one per format, onboarded and approved through the real API.
  - Average raw payload: **208 bytes per event** (JSON 245, JSON with extra fields 294, Syslog 152, CEF 154, LEEF 181).
  - ULPF stores each event twice: a raw row in `ulpf_raw.raw_events`, and a canonical row in the source's `ulpf_events.events_<source>` table.

## 2026-11-15: baseline on `main` at `8e5b731`

### Run 1: ClickHouse alone (the ceiling of this host)

Run `2026-11-15T215555-ch-ceiling`. k6 inserts straight into `ulpf_raw.raw_events`, with no ULPF involved. Each request is one `INSERT` of 500 rows (ULPF's batch size). `received_at` is spread over the interval that each batch covers. Each step holds its rate for 120 s.

| Events/s | Verdict | P1 | Payload | On the wire | ClickHouse writes (uncompressed) | On disk (before merges) |
|---|---|---|---|---|---|---|
| 4,000 | PASS | 3,996 | 0.83 MB/s (6.7 Mbps) | 1.5 MB/s | 1.5 MB/s | 0.3 MB/s |
| 8,000 | PASS | 7,992 | 1.67 MB/s (13.3 Mbps) | 3.0 MB/s | 3.0 MB/s | 0.6 MB/s |
| 12,000 | PASS | 11,988 | 2.50 MB/s (20.0 Mbps) | 4.6 MB/s | 4.6 MB/s | 0.6 MB/s |
| 16,000 | PASS | 15,984 | 3.33 MB/s (26.7 Mbps) | 6.1 MB/s | 6.1 MB/s | 0.7 MB/s |
| 24,000 | PASS | 23,976 | 5.00 MB/s (40.0 Mbps) | 9.1 MB/s | 9.1 MB/s | 1.1 MB/s |
| 32,000 | PASS | 31,968 | 6.67 MB/s (53.4 Mbps) | 12.2 MB/s (97 Mbps) | 12.1 MB/s | 1.5 MB/s |

- **Overall:** 0 dropped, 0 refused, 0 lost (11,522,500 rows sent and stored). ClickHouse never delayed or rejected an insert. At 32,000/s, insert P99 was 94 ms, with about 10.7 new parts per second and at most 7 active parts.
- **What this means:**
  - ClickHouse on this host takes more than 32,000 raw rows/s. That is well above the North Star (11,600 events/s), so ClickHouse is not ULPF's limit today.
  - On the wire, each row costs about 380 bytes: the ids, the timestamp, the JSON field names and the HTTP framing.
- **Limits:**
  - The run wrote only the raw row. ULPF writes 2 rows per event, the second into a per-source table. If a canonical row costs about as much as a raw row, the ceiling for ULPF's write pattern is at least 16,000 events/s, but that is an estimate, not a measurement.
  - The real ceiling is above 32,000, because no higher step was run.

### Run 2: ULPF, first ramp

Run `2026-11-15T221449-base-coarse`. Each step holds its rate for 120 s, after a 60 s warm-up at 300/s. The run stops after 2 steps in a row in which k6 cannot keep the rate.

| Offered/s | Verdict | P1 | Median | k6 P50 | k6 P99 | k6 max | Dropped |
|---|---|---|---|---|---|---|---|
| 250 | PASS | 249 | 250 | 1 ms | 4.9 ms | - | 0 |
| 500 | FAIL | 364 | 500 | 0.8 ms | 12.2 s | 24.8 s | 205 |
| 750 | FAIL | 649 | 750 | 1.4 ms | 11.4 s | 39.7 s | 295 |
| 1,000 | FAIL | 808 | 1,000 | 16 ms | 9.6 s | 39.6 s | 513 |
| 1,500 | FAIL | 930 | 1,487 | 507 ms | 6.2 s | 35.1 s | 9,746 |
| 2,000 | FAIL | 923 | 1,483 | 1.1 s | 6.2 s | 38.0 s | 63,767 |

- **Overall:** 0 lost (645,479 accepted; 645,479 raw rows and 645,479 canonical rows).
- **Results:**
  - **Knee: 250 events/s.** Throughput saturates at a median of about **1,485 events/s** (about 0.31 MB/s, 2.5 Mbps, of payload).
  - From 500/s on, most requests are fast, but the engine **freezes for 10-40 s at a time**.
  - This is less than 1/20 of what ClickHouse takes on the same host (run 1).
- **The old figure:** the 995 events/s in `README.md` §11 came from a closed-model Python test through nginx without keep-alive. It is not comparable with these numbers.
- **Limits:**
  - The noise band below shows that the knee is 400/s with finer steps, and that saturation varies by about ±15%.
  - The engine's CPUs ran at about 2.2-2.6 GHz, not at full turbo.

### Runs 3-7: the noise band (5 identical ramps)

Runs `2026-11-15T223745-base-noise-r1` to `2026-11-15T234134-base-noise-r5`, one after the other. Each run restarts the stack, warms up for 60 s at 300/s, then holds each step for 120 s. The engine ran at a median of 2.2-2.5 GHz.

| Offered/s | Passes | P1 (min / median / max) | Median per second | k6 P99 (min to max) | Dropped (min to max) |
|---|---|---|---|---|---|
| 200 | 5 of 5 | 200 / 200 / 200 | 200 | 0 s | 0 |
| 300 | 5 of 5 | 299 / 300 / 300 | 300 | 0 s | 0 |
| **400** | **5 of 5** | 398 / 400 / 400 | 400 | 0 s | 0 |
| 500 | 0 of 5 | 385 / 428 / 483 | 500 | 0.3 to 16.7 s | 203 to 380 |
| 750 | 0 of 5 | 499 / 573 / 604 | 750 | 12.2 to 12.8 s | 442 to 559 |
| 1,000 | 0 of 5 | 502 / 639 / 728 | 1,000 | 8.5 to 9.5 s | 652 to 3,031 |
| 1,500 | 0 of 5 | 565 / 675 / 782 | 1,065 to 1,239 | 3.8 to 6.3 s | 25,949 to 46,201 |

- **Overall:** 0 lost in every run (between 508,079 and 530,760 events per run, all stored as raw and canonical rows).
- **The knee is 400 events/s in 5 of 5 runs.** It lies between 400 and 500: 400 passed every time, and 500 failed every time. The coarse run's 250 only reflected its step size.
- **Saturation is noisier than the knee.** At 1,500/s offered, the stored median per second ranged from 1,065 to 1,239 (the coarse run reached 1,487), a spread of about ±15%.
- **How to judge a change against this baseline:**
  - Count it as real only if the knee moves by more than one step (100/s), or the saturation throughput by more than about 15%.
  - Run each change at least 3 times, in A/B/A/B order.

### Profile: why ULPF stalls

Sources:
- the JFR recording of run 2 (`settings=default`, 20 ms thresholds, 819 s);
- an async-profiler wall-clock profile at 500 events/s (run `2026-11-15T223317-base-wall-500`: 60 s, `-e wall -i 10ms -t --lock 1ms`).

1. **A lock convoy on the flush.** `ClickHouseIngestionRepository.flush()` and `flushCanonical()` are `synchronized` on the same object. A request thread that fills a queue to 500 events runs the flush itself.
   - JFR: 12,780 waits to enter these methods, **8 s on average, 22.4 s at most**.
   - Wall clock: **79% of all thread time** is request threads waiting to enter the lock. There are 1,158,787 waiting samples against 5,876 holding samples, so about 200 threads are queued behind each holder on average.
2. **What the holder spends its time on** (wall clock, inside the lock):
   - **68%: the ClickHouse insert.** This is mostly the `wait_for_async_insert=1` wait of up to about 200 ms.
   - **30%: SQLite,** for the Merkle integrity block. The block is read and written in the same lock.
   - **0.2%: SHA-256 hashing.** The hashing itself is cheap.
3. **`ConcurrentLinkedQueue.size()` takes 64% of all CPU samples** (JFR). Nearly all of it is in `enqueue()`: the trim loop of `recentRawHistory` and the batch-size check. `size()` walks the whole queue, and the queues grow while the flush is blocked. So a stall makes every enqueue slower, which makes the stall longer.
4. **GC is not a cause:** 5.26 s of pauses in 819 s, median 3.35 ms.

**What this means for the fix (Phase 1, not yet planned or approved):**
- The flush must leave the request threads and the shared lock.
- The integrity work must not hold up the ClickHouse insert.
- The queue-size checks must be O(1).

A faster network alone would help little: the network wait is 68% of a section that is serial by design.

## 2026-11-16: what the metrics cost (instrumented build against `8e5b731`)

Runs `2026-11-16T000328-ab-base`, `2026-11-16T001924-ab-instr`, `2026-11-16T003526-ab-base` and `2026-11-16T005123-ab-instr`, in that order (A, B, A, B), on the same host and setup as above. Same steps as the noise band.

- **A (base):** image `45ab1cb15a43`, built from `8e5b731`.
- **B (instr):** image `7b0fb542f1b1`, the same code plus the Phase 0 meters and the actuator (branch `perf/phase-0-measurement`). During each step, `run.sh` read the queue gauges every 5 s and the flush timer before and after the step.

| Offered/s | Median per second (A1 / A2 · B1 / B2) | P1 (A1 / A2 · B1 / B2) | Dropped (A1 / A2 · B1 / B2) |
|---|---|---|---|
| 200 to 400 | as offered in all 4 runs (all PASS) | at least 398 at 400 | 0 |
| 500 | 500 / 500 · 500 / 500 | 428 / 387 · 325 / 249 | 309 / 356 · 312 / 431 |
| 750 | 750 / 750 · 750 / 750 | 501 / 500 · 500 / 474 | 639 / 664 · 642 / 1,324 |
| 1,000 | 1,000 / 987 · 999 / 997 | 500 / 501 · 503 / 500 | 4,098 / 8,464 · 6,920 / 14,064 |
| 1,500 | 1,132 / 1,010 · 998 / not run | 700 / 500 · 502 / not run | 41,477 / 54,456 · 58,598 / not run |

B2 stopped after 1,000, because k6 dropped more than 1% of the iterations in 2 steps in a row (`--stop-after 2`). The engine ran at a median of 2.2-2.6 GHz in all runs.

- **Result: the metrics have no cost that the agreed rule can detect.**
  - The knee is 400 in 4 of 4 runs.
  - Saturation at 1,500/s: B1 is 998, against 1,071 on average for A. That is −7%, inside the ±15% band.
- **One signal is outside the noise band:** P1 at 500/s was 325 and 249 with the metrics, against 428 and 387 without them, and 385 to 483 in the noise band. P1 in a failing step shows how deep the stalls go. Two runs per side cannot tell whether the meters, chance or the growing integrity ledger caused it. B ran later than A on average, on a larger ledger (see "the effect of a growing database" below).
  - **So:** Phase 1 measures against the instrumented build (B), not against `8e5b731`. Then both sides carry the same meters.
- **All 4 runs lost no event.** The ledger shows "lost −1" or "−2" in 3 runs. That is the stored count minus the `202` count: k6 stopped waiting after 60 s (k6 max 60,001 ms) and counted the request as not accepted, but the server stored the event. A real client would retry such a request and store the event twice.
- This A session ran slightly lower than the noise band session for both images. For example, P1 at 1,000/s was about 500 in all 4 runs, against 502 to 728. So compare runs only within one session, in A/B order.

### What the new meters show (B1 / B2)

| Offered/s | Raw queue max | Canonical queue max | Raw flush, mean | Canonical flush, mean |
|---|---|---|---|---|
| 200 | 211 / 228 | 222 / 226 | 124 / 145 ms | 8.4 / 8.9 ms |
| 300 | 340 / 336 | 349 / 356 | 131 / 157 ms | 9.1 / 9.8 ms |
| 400 | 383 / 455 | 368 / 477 | 137 / 165 ms | 10.0 / 13.1 ms |
| 500 | 397 / 322 | 504 / 506 | 212 / 256 ms | 57.0 / 57.7 ms |
| 750 | 508 / 512 | 504 / 506 | 228 / 265 ms | 58.6 / 58.5 ms |
| 1,000 | 508 / 494 | 504 / 507 | 251 / 277 ms | 59.0 / 57.9 ms |

The queue gauges are samples every 5 s, so short peaks can be missed. A flush time is the time inside the `synchronized` method, with the SQLite and integrity work included.

- **A raw flush takes at least 124 ms, even at 200 events/s.** With `wait_for_async_insert=1`, each insert waits for ClickHouse's next async flush (`async_insert_busy_timeout_ms=200`). That wait does not depend on the batch size.
- **The raw flush is the long one:** 4 to 15 times as long as the canonical flush. In the profile, the raw flush also holds the SQLite Merkle work.
- **The queues reach the batch size (500) from 500/s on.** Then every request that fills a queue runs a flush itself, and the lock convoy starts (see "Profile: why ULPF stalls").

## 2026-11-16: rootless Podman against rootless Docker, and the effect of a growing database

Runs, in this order:
- `2026-11-16T011445-rt-docker` (D1, Docker, fresh volumes);
- `2026-11-16T013049-rt-podman` (P-old, Podman, the volumes of all earlier runs);
- `2026-11-16T014449-rt-docker` (D2, Docker, the D1 volumes);
- `2026-11-16T020238-rt-podman-fresh` (P-fresh, Podman, fresh volumes in a separate compose project, `ulpffresh`).

Same steps as the noise band, and the instrumented image (`7b0fb542f1b1`) on both runtimes. The image was copied with `podman save` and `docker load`, so both ran the same bits.

**Setup differences**, all needed to run at all:
- **Docker 29.9.2** (rootless, Compose v5.6.1, cgroup v2, the systemd driver; port driver `builtin`, network slirp4netns):
  - The engine reaches ClickHouse by its service name, `clickhouse:8123`. Rootless Docker runs with `--disable-host-loopback`, so `host.containers.internal` (through `host-gateway`) cannot reach the port that ClickHouse publishes.
  - The ClickHouse health check in `compose.yaml` never passes under Docker (backlog item 19), so the test override checks `127.0.0.1` instead.
- **Podman 3.4.4:**
  - The containers run on Podman's default network (`podman`, 10.88.0.0/16), not on `ulpf_ulpf-net`. Podman 3.4.4 cannot use that network's config (CNI version 1.0.0), and the default network has no DNS.
  - So the engine reaches ClickHouse through the bridge gateway, `host.containers.internal` (10.88.0.1), and the port mapping.

| | D1 (Docker, fresh) | P-fresh (Podman, fresh) | D2 (Docker, after D1) | P-old (Podman, old data) |
|---|---|---|---|---|
| Knee | 400 | 400 | 400 | **300** |
| Raw flush, mean, at 200 / 300 / 400 per s | 34.5 / 34.8 / 37.0 ms | 34.9 / 35.3 / 37.3 ms | 49.8 / 51.6 / 54.6 ms | 143 / 154 / 235 ms |
| Canonical flush, mean, at 200 per s | 8.1 ms | 8.1 ms | 8.2 ms | 8.7 ms |
| P1 at 500 / 750 / 1,000 per s | 450 / 666 / 651 | 450 / 655 / 845 | 392 / 648 / 766 | 318 / 500 / 498 |
| Dropped at 1,000 per s | 543 | 544 | 930 | 13,410 |
| Median per second at 1,500 per s | 1,251 | invalid (see below) | 1,225 | not run |
| Lost | 0 | 0 in steps 200-1,000 | 0 | 0 (−3: k6 timeouts that were stored) |

- The engine ran at a median of 2.1-2.7 GHz in all 4 runs.
- **P-fresh, 1,500/s step:** the watchdog stopped k6 during this step, because free disk fell below 3 GB. The step is not valid, and the run's total ("lost −1,085") counts requests that k6 abandoned while the server stored them.

**Result 1: rootless Podman has no measurable disadvantage against rootless Docker on this host.**
- On the same starting state (fresh volumes), Podman and Docker match within the noise band in every step:
  - the raw flush differs by less than 0.5 ms;
  - P1 at 500/s is the same (450);
  - Podman is ahead at 1,000/s (845 against 651).
- The network forwarders use little CPU on both runtimes. Mean per step, from `pidstat`:
  - Podman: rootlessport 0-3%, slirp4netns about 0%;
  - Docker: rootlesskit 2-31%, docker-proxy 0-4%. Docker's built-in port driver carries the k6 traffic.
- So the runtime decision (switch to Docker only on a large Podman disadvantage) gives: **stay on Podman.** The team lead decides; this is the measured input.
- **Limit:** this is Podman 3.4.4 (CNI, slirp4netns). The Grafana plan requires Podman 4.9 or Docker for service names (its row 28). Podman 4.9 uses a different network stack (netavark, and pasta or slirp4netns), so measure it again when the stack moves to 4.9.
- The differences that showed up are configuration problems, not runtime costs:
  - the health check (item 19);
  - `host.containers.internal` under rootless Docker;
  - no service-name DNS on Podman 3.4.4.

**Result 2: ULPF slows down as the integrity ledger grows.** The raw flush at 200/s took:
- 35 ms on fresh volumes;
- 50 ms after one run (about 11,000 blocks, at most 2,424 per source);
- 143 ms on the old volumes (102,369 blocks, 21,339 for the largest source).

The knee fell from 400 to 300.
- **The measured cause:** before each block, `IntegrityRepository.findLatestBlockHash` runs `WHERE source_id = ? ORDER BY block_id DESC LIMIT 1`. The only index is `(source_id, created_at)`, so SQLite reads all blocks of the source and sorts them (`USE TEMP B-TREE FOR ORDER BY`).
  - Timed on copies of the databases: 0.044 ms for 200 blocks, 1.45 ms for 2,424, and 12.35 ms for 21,339. That is linear, about 0.58 µs per block.
  - A raw flush writes 1 block per source in the batch (5 here). So the query takes about 60 ms per flush on the old data. That explains about 60 ms of the 108 ms gap. The rest is not measured yet. Candidates: the block insert into a larger table and its index, and the `delete` journal mode, in which every insert is a synced transaction.
- **What this means:**
  - Every number in this file depends on how many integrity blocks the database holds. The old Podman volumes gained blocks with every run, so later runs ran slower. This is why the A/B session ran lower than the noise band session. It is also a bias in the A/B cost test: B ran later than A on average, on a larger ledger, so the lower P1 at 500/s for B can come from this alone.
  - **From now on, start each comparison on fresh volumes, or record the number of blocks** (`SELECT count(*) FROM batch_integrity_blocks`).
  - At the North Star rate a source gets about 23 blocks per second, about 2 million per day. In production, this query alone would make ingestion slower every day. The fix is item 0 of the Phase 1 proposal in `docs/plans/2026-11-13-ingestion-throughput.md`.
- **Update 2026-11-19:** the integrity ledger was removed (`docs/plans/2026-11-19-remove-integrity-ledger.md`). Builds after the removal write no blocks, so the block count no longer affects their runs. The numbers above stay as measured. Runs on builds that still have the ledger (up to `8412f87`) still depend on the block count.

## 2026-11-16: through nginx (`:3000`) against straight to the engine (`:8080`)

Run `2026-11-16T021812-nginx-podman-fresh`. Podman, the instrumented image, and the `ulpffresh` volumes after one run (so a ledger like D2's above: the raw flush took 50.8 ms at 200/s here and 49.8 ms in D2). k6 sent to `http://127.0.0.1:3000/v1/events`, through the frontend's nginx. nginx sends `/v1/` to `host.containers.internal:8080`, without an upstream `keepalive`, so it opens a new connection to the engine for each request.

| | Knee | k6 P50 at 200 / 400 per s | P1 at 750 / 1,000 per s | Median per second at 1,500 per s | Lost |
|---|---|---|---|---|---|
| Through nginx | 400 | 1.09 / 1.04 ms | 552 / 677 | 1,135 | 0 |
| Straight to the engine (D2, Docker) | 400 | 0.83 / 0.80 ms | 648 / 766 | 1,225 | 0 |

- **Result: today, nginx costs about 0.25 ms per request and no throughput that the agreed rule can detect.**
  - The knee is the same.
  - Saturation is 7% lower, inside the ±15% band.
- **Limits:**
  - The comparison point is a Docker run. On the same starting state, Docker and Podman matched (see the section above), but this is not an A/B pair.
  - The engine stalls long before nginx can be the limit. A new connection per request costs more at higher rates, so measure this path again after Phase 1.
