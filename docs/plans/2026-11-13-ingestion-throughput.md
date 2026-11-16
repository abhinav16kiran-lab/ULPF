# Plan: Ingestion throughput, Phase 0 (measurement and baseline)

- **Date:** 2026-11-13
- **Status:** Phase 0 done (2026-11-16). Phase 1 is proposed in section 9 and not approved.
- **Approved by / on:** Adarsh (team lead), 2026-11-15, with 2 changes (section 3.1, decisions 7 and 8)
- **File name:** `docs/plans/2026-11-13-ingestion-throughput.md`
- **Backlog:** item 13 in `to-do-list.md`. Research: `docs/research/2026-11-13-profiling-ingestion.md` (the method and the tools, with sources). Decisions from the team lead on 2026-11-13 are in section 3.1.
- **Type:** umbrella plan. This file details Phase 0 only. Each later phase is added to this file, or gets its own plan, and is approved before any code changes.

## 1. Goal

Find what limits the maximum sustained rate of `POST /v1/events`, with numbers we can trust, before we change any code for speed.

Done means:
- A performance kit in the repo (`perf/`) that anyone can run: it onboards test sources, sends load at a fixed rate, counts accepted events in ClickHouse per second, checks for event loss, and collects JFR, async-profiler and `pidstat` data for each run.
- The core engine has the metrics that item 13 needs: Actuator, `/actuator/metrics` behind the monitor password, the queue gauges and the ClickHouse flush, batch and error meters. These are the meters that the Grafana plan (rev 4, row 32) builds on.
- A trusted baseline on the current `main`: the noise band, the knee (the highest rate that passes), and a profile at the knee that names the bottleneck.
- A measured answer to "does rootless Podman networking cost much more than Docker?"
- Documentation that says what was built, why, how to run it, and how later work (the Grafana plan, Phase 1 fixes) builds on it without redoing it.

Phase 0 makes **no change for speed**. Fixes start in Phase 1, from the profile.

## 2. What I verified

From the code map (2026-11-13; paths under `core-engine/src/main/java/com/ulpf/`):
- `ClickHouseIngestionRepository.flush()` (`:206`) and `flushCanonical()` (`:288`) are `synchronized` on the same object. A request thread runs a flush when a queue reaches 500 events (`:175-177`, `:185-187`). Inside the lock: the ClickHouse insert, then `batchIntegrityService.processRawBatch` (`:247`), which hashes rows and writes SQLite.
- ClickHouse runs with `async_insert=1` and `wait_for_async_insert=1`, with a 200 ms busy timeout (`infra/clickhouse-config/users.d/async_inserts.xml`). Each insert can wait up to about 200 ms while the Java lock is held.
- The queues are unbounded `ConcurrentLinkedQueue`s (`:40-41`). `size()` is O(n), and `enqueue` calls it on a list of about 5,000 nodes for every event (`:172`).
- `application.yaml:3` makes the `dev` profile active, and `compose.yaml` does not override it. `application-dev.yaml:5-6` logs `com.ulpf` and `org.springframework.jdbc` at DEBUG.
- No Actuator and no Micrometer in `pom.xml`. `/actuator/**` is `permitAll` (`SpringSecurity.java:44`) and excluded from `JwtFilter` (`JwtFilter.java:42`). Adding Actuator without a security chain would publish the metrics.
- The runtime image runs `java -jar` with no flags (`core-engine/Containerfile:31`). It has no `jcmd`. In the Temurin 21.0.13 JRE image the JVM picks G1 with a 4.11 GB maximum heap (research §1).
- `compose.yaml` publishes `8080`, `8123`, `9000` and `3000`, and sets no CPU or memory limits. core-engine reaches ClickHouse through `host.containers.internal`.
- The old benchmark scripts (`dev-tools/`, git-ignored) used Python threads that wait for each reply, a new connection per request, and nginx on `:3000` with no upstream keep-alive. The recorded results (README §11: 995 events/s; ramp data: a plateau at 650 to 813 events/s) are not a reliable baseline (research §2).

Host (checked 2026-11-13):
- i5-12500H: 16 CPUs, performance cores and efficiency cores. Governor `powersave`. `perf_event_paranoid=4`.
- Rootless Podman 3.4.4 (slirp4netns). The user cgroup delegates only `memory pids`, so CPU pinning does not work yet.
- Rootless Docker 29.9.2 with Docker Compose v5.6.1. `docker ps` works.
- Not installed: `k6`, async-profiler. Installed: `pidstat`, `jfr` (JDK 25 on the host), `sqlite3`.

Not verified yet (research "Open points"): whether `asprof` runs on the Alpine image (agent mode is the fallback), and how clickhouse-jdbc 0.4.6 sends a batch.

## 3. Proposed approach

### 3.1 Decisions (team lead, 2026-11-13)

1. The pass mark is the North Star: 11,600 events/s at P99 of 1-second windows, sustained, with no event loss; 12,000 events/s peak. That is about 12x the old figure. The baseline is measured again.
2. Host setup with `sudo` is done by the team lead, once (section 3.4).
3. Measure rootless Podman against Docker. If Podman shows a large disadvantage that Docker does not have, ULPF moves fully to Docker.
4. Item 13 delivers the shared instrumentation itself (Grafana plan rev 4, row 32). The Grafana team is not active now. If its work later conflicts with ours, the merge favours item 13.
5. k6 (AGPL-3.0) is acceptable. It is an internal test tool and is never shipped with ULPF.
6. Document what is built, so later work builds on it.
7. **The performance kit stays out of git (2026-11-15).** `.gitignore` lists `perf/`, so the scripts, `perf/README.md` and `perf/RESULTS.md` live only on the team lead's machine. The core-engine instrumentation and the docs in `docs/` are committed as usual. `ARCHITECTURE.md` §3.11 describes the method and the metrics, and says that the kit is local.
8. **`README.md` does not change in Phase 0 (2026-11-15).** It is for users and contributors, so what goes there is decided later. The note on the old 995 events/s figure waits for that decision.

### 3.2 The performance kit (`perf/`)

A new top-level folder, **git-ignored** (decision 7). It is tooling for the team lead, not an agent script.

| Path | What it does |
|---|---|
| `perf/README.md` | How to run everything, in numbered steps; what each file is; how to read the results |
| `perf/tools.lock` | Pinned versions, download URLs and SHA-256 of k6 and async-profiler |
| `perf/install-tools.sh` | Downloads the pinned tools into `perf/tools/` (git-ignored) and checks the SHA-256. Run once, with internet |
| `perf/compose/perf.override.yaml` | Compose override for test runs: CPU sets for each service, `JDK_JAVA_OPTIONS` (JFR with lock and socket thresholds at 1 ms, GC log), the async-profiler folder mounted read-only, `MONITOR_PASSWORD`, and a folder for the results. `compose.yaml` itself does not change |
| `perf/compose/docker.override.yaml` | Only for the Docker run: `extra_hosts: host.containers.internal:host-gateway`. Docker does not provide that name, and the stack needs it until the Grafana plan's Task 0 |
| `perf/setup/onboard.sh` | Uses the public API to complete the setup, sign up a test vendor, onboard N sources (one per payload format), approve them, and create their API keys with the item 16 flow. It writes the keys to a git-ignored file for k6 |
| `perf/k6/ingest.js` | `constant-arrival-rate` scenario (open model). Rate, duration and payload mix come from environment variables. It spreads events over the sources and API keys and sends JSON, Syslog, CEF and LEEF bodies |
| `perf/k6/payloads/` | Sample bodies for each format, including one JSON body with fields the mapping does not know, to drive the drift path |
| `perf/run.sh` | One run: records the host and stack state, truncates the ClickHouse tables, runs k6 for each step of a rate list, runs `pidstat`, then queries ClickHouse. Writes everything to `perf/results/<run-id>/` (git-ignored) and prints a one-screen summary |
| `perf/profile.sh` | At a given rate: runs `asprof` (wall clock, `--lock 1ms`, `--alloc 2m`, per thread) for 60 s, then converts the output to the flame graphs listed in research §7.3 |
| `perf/sql/` | The ClickHouse queries: accepted per second with `WITH FILL` and `quantileExact(0.01)`, stored rows compared with the k6 `202` count, rows per insert and parts per second (`query_log`, `part_log`), and async insert status |
| `perf/tests/test-sql.sh` | Loads synthetic rows with known per-second counts, including empty seconds, into a throwaway ClickHouse, and checks that the queries return the expected P1, minimum and loss figures |
| `perf/RESULTS.md` | The ledger (local, decision 7): one row for each run that counts (run ID, git SHA, runtime, rates, knee, P1, loss, bottleneck in one line) |

Why k6 targets core-engine on `:8080` directly: it measures the engine's capacity. nginx is a second hop with a known problem (no upstream keep-alive). Phase 0 measures the nginx path once, as a separate number, after the baseline.

Why the passing rule and the per-second count come from ClickHouse: the server counts what it accepted and stored, so a slow client cannot hide a slow server. The rules are in research §7.2.

### 3.3 Instrumentation (core engine)

Test first, as AGENTS.md §4 requires. Names, tags and buckets as in the Grafana plan's metric table, so that plan can use them as they are.

- `pom.xml`: `spring-boot-starter-actuator`. No Prometheus registry; that stays in the Grafana plan.
- `SpringSecurity.java`: a new `@Order(1)` chain with `securityMatcher("/actuator/**")`. `/actuator/health` is open. Every other actuator endpoint needs the monitor user (HTTP Basic, BCrypt-hashed, stateless). Remove `/actuator/**` from the main chain's `permitAll`. `JwtFilter` keeps `/actuator/**` excluded (Grafana plan decision 3).
- Monitor password: `spring.config.import=optional:configtree:/run/secrets/` maps to `ulpf.monitor.password`, with `MONITOR_PASSWORD` as an override. **Fail closed:** with no password, no monitor user exists and the endpoints are denied. One `WARN` is logged at startup. The secret file and its compose mount stay in the Grafana plan. For test runs, `perf.override.yaml` sets `MONITOR_PASSWORD` from a value that `run.sh` generates.
- `application.yaml`: `management.endpoints.web.exposure.include=health,metrics`. Turn on Tomcat's MBean registry, so the Tomcat thread metrics exist.
- New `common/MetricsConfig.java`: binds `ulpf.queue.raw.size` and `ulpf.queue.canonical.size` to the repository getters. It also holds the explicit bucket sets.
- `ClickHouseIngestionRepository`: a second constructor that takes a `MeterRegistry`. The 1-argument constructor stays and uses a `SimpleMeterRegistry`. Add `ulpf.clickhouse.flush.duration` (Timer, tag `stage=raw|canonical`), `ulpf.clickhouse.batch.size` (DistributionSummary, same tag), `ulpf.clickhouse.insert.errors`, and `ulpf.records.degraded`, `.dropped` and `.reenqueued` (Counters, tag `table`, created on the first failure).
- Built-in meters we get for free and use: `http.server.requests`, the JVM memory, GC and thread meters, the HikariCP pool meters, and the Tomcat thread meters.

The instrumentation is added **after** the first baseline, so we can measure its own cost with an A/B run.

### 3.4 Host setup (the team lead runs these once; exact commands go in `perf/README.md`)

1. **Allow rootless containers to use fixed CPUs:** a systemd drop-in, `Delegate=memory pids cpu cpuset`, from Podman's docs. Then log out and in.
2. **During test runs only,** set the CPU governor to `performance`, and set it back afterwards. `run.sh` records the value and refuses to start if it is not `performance`, unless you pass `--allow-powersave`.
3. **During profiling only,** set `perf_event_paranoid` to `1`, and set it back to `4` afterwards. If you skip this step, `profile.sh` uses async-profiler's `itimer` mode, which is less exact.

CPU layout (from research, to confirm on the host): core-engine on CPUs 0-7 (performance cores), ClickHouse on 8-13, k6 on 14-15.

### 3.5 The runs, in order

1. **Plain ClickHouse baseline** (item 13 requirement 1): k6 sends straight into ClickHouse through its HTTP interface, with the same tables and body mix. This gives the ceiling that ClickHouse sets on this host.
2. **Noise band:** 5 runs of the same ULPF ramp on the current `main`. The spread of the knee is the noise band.
3. **Knee:** a coarse ramp, then a fine ramp between the last pass and the first fail.
4. **Profile at the knee** and at 1.1 × the knee, using the table in research §7.3 to pick the mode. Write down the bottleneck with its evidence.
5. **Instrumentation cost:** A/B/A/B runs, the baseline against the instrumented build. If the difference is larger than the noise band, record it and decide whether to keep `http.server.requests`.
6. **Podman against Docker:** the same stack and the same CPU layout, A/B/A/B. Also measure the path cost on its own: k6 → core-engine, and core-engine → ClickHouse (research §6.1).
7. **The nginx path:** one ramp through `:3000/v1/events`, compared with `:8080`.

Each run follows research §7.5 (what to record) and §7.6 (noise rules): restart the stack and truncate the tables before each run, change one thing at a time, and discard runs with thermal throttling or `dropped_iterations` above 0 below the knee.

### 3.6 Documentation that lasts

- `perf/README.md`: how to run, what each file does, how to read the output.
- `docs/ARCH/ARCHITECTURE.md`, new §3.11 "Metrics and performance measurement":
  - the metrics that exist, and where each is measured;
  - the actuator security chain;
  - how the Grafana plan adds Prometheus (one dependency and one exposure entry);
  - the measuring method;
  - each decision with its reason and the rejected alternative (for example: k6 rather than the old Python scripts; server-side counting; `:8080` rather than nginx for engine capacity; metrics behind the monitor password rather than open).
- `docs/ARCH/API_SPECIFICATION.md`: `/actuator/health` and `/actuator/metrics`, and their security.
- `docs/db/COMMON_DB_GUIDE.md`: the meters on `ClickHouseIngestionRepository`.
- `docs/perf/RESULTS.md` (committed): every measured result, with the host, the method and the limits, so that later sessions can trust it without measuring again. `perf/RESULTS.md` is only the local ledger.
- `to-do-list.md` item 13: progress, and a link to this plan.
- `README.md`: no change in Phase 0 (decision 8).
- `AGENTS.md` §5: one line saying that a local performance kit exists on the team lead's machine, and that `ARCHITECTURE.md` §3.11 describes it. No commands, because the kit is not in git.

## 4. Test plan (written first)

Backend (JUnit 5):

| Test | Class | Expected to fail before because… |
|---|---|---|
| `/actuator/health` → 200 with no auth | `ActuatorSecurityTest` | Actuator not on the classpath |
| `/actuator/metrics` → 401 with no auth and with wrong creds; a valid user JWT does not grant access; 200 with the monitor creds | `ActuatorSecurityTest` | no actuator chain |
| No monitor password → `/actuator/metrics` denied even with a blank password; no monitor user exists | `ActuatorSecurityTest` | no actuator chain |
| Password from a config-tree folder (file ends with a newline) authenticates | `ActuatorSecurityTest` | no secret loading |
| `/v1/events` and `/v1/login` behave as before (the main chain is unchanged except for `/actuator/**`) | `ActuatorSecurityTest` | guards against a regression |
| Queue gauges follow the buffer depth | `QueueMetricsTest` | no gauges |
| A flush records the duration and the batch size with `stage=raw` and `stage=canonical` | `ClickHousePipelineMetricsTest` | no meters |
| The failure paths increment `insert.errors`, `degraded`, `dropped` and `reenqueued` | `ClickHousePipelineMetricsTest` | no meters |
| No `Timer` or `DistributionSummary` has a `source_id`, `vendor_id` or `table` tag; each exports at most 16 series | `MetricsCardinalityTest` | no meters |
| `ClickHouseIngestionRepositoryTest` still passes with the 1-argument constructor | existing | guards compatibility |

Performance kit:

| Test | How |
|---|---|
| The per-second query returns the right P1 and minimum, and counts empty seconds as 0 | `perf/tests/test-sql.sh` against a throwaway ClickHouse container with synthetic rows |
| The loss check reports a gap when stored rows < `202` count | the same script, with rows removed on purpose |
| `install-tools.sh` refuses a file with a wrong SHA-256 | run it with a corrupted checksum in a copy of `tools.lock` |
| The kit works end to end | a smoke run at 200 events/s for 30 s: k6 summary, ClickHouse counts, `pidstat` file and summary all present; loss = 0 |

The shell and k6 files have no unit tests beyond these. They are checked by the smoke run, and the SQL is checked by `test-sql.sh`.

## 5. Files to change

New:
- `perf/` as in section 3.2 (local only, not committed).
- `core-engine/src/main/java/com/ulpf/common/MetricsConfig.java`.
- Tests: `ActuatorSecurityTest`, `QueueMetricsTest`, `ClickHousePipelineMetricsTest`, `MetricsCardinalityTest`.

Changed:
- `core-engine/pom.xml`, `core-engine/src/main/resources/application.yaml`, `common/SpringSecurity.java`, `common/db/ClickHouseIngestionRepository.java`.
- `.gitignore`: `perf/` (decision 7).
- Docs as in section 3.6.

Not changed: `compose.yaml`, `Containerfile`s, the ingestion logic.

## 6. Steps

Branch `perf/phase-0-measurement`, one PR for the kit and the instrumentation, then a PR with the results and docs.

- [x] 1. Host setup (section 3.4, team lead): cgroup delegation done and checked on 2026-11-15 (Podman pinned a test container to CPUs 0-1). The governor and `perf_event_paranoid` steps are per session. Tools installed: k6 v2.3.0 and async-profiler 4.6, SHA-256 checked.
- [x] 2. The performance kit (2026-11-15). Built and tested: `test_report.py` (14 tests: pass rules, knee, loss, clock speed) and `test-sql.sh` (13 checks against ClickHouse 26.3). A smoke run in ClickHouse mode passed (200 and 400 events/s, P1 equal to the rate, 0 lost). Smoke run against the real stack (Podman 3.4.4, after the team lead's first install): 200 and 500 events/s, all 5 formats accepted, 0 lost (31,323 accepted and 31,323 stored, raw and canonical). `profile.sh` wrote a wall-clock JFR file and 5 flame graphs. Changes found while testing:
  - ClickHouse treats HTTP GET as read-only, so the query helper uses POST.
  - `system.part_log` and `system.asynchronous_insert_log` exist only after the first log flush, so the run calls `SYSTEM FLUSH LOGS` first.
  - This laptop's thermal-throttle counters rise even when idle (about 9 package events per second), so "discard the run if the counter rose" (research §7.6) would discard every run. Instead, the run samples each CPU's clock speed every second and reports the engine's median MHz per step. It warns only when the throttle rate during the run is far above the idle rate.
  - k6 shares the payloads across VUs (`SharedArray`) and pre-allocates VUs for 250 ms responses, because `wait_for_async_insert=1` makes a request take about 200 ms.
  - In ClickHouse mode, k6 sends `received_at`, because the column default is the async flush time.
  - `asprof` 4.6 runs inside the core-engine container (confirmed 2026-11-15).
  - On Podman 3.4.4, `podman restart` can stop the engine and fail to start it again (a systemd timer error). `run.sh` stops and then starts the engine to write the JFR file, and judges the result by the health check.
  - The first smoke run found 2 product bugs, which blocked Phase 0. Both are fixed on `main` through their own plans: onboarding failed in the container because ONNX Runtime could not load on Alpine (PR #26, `docs/plans/2026-11-15-onnx-native-runtime.md`), and plain-text lines got 415 (PR #27, `docs/plans/2026-11-15-plain-text-ingest.md`). The baseline is measured on `main` with both fixes, on the glibc runtime image.
- [x] 3. Plain ClickHouse baseline (run 1, 2026-11-15, `perf/results/2026-11-15T215555-ch-ceiling`). ClickHouse alone passes every step up to 32,000 events/s on this laptop: P1 within 0.1% of the rate, 0 dropped, 0 lost (11,522,500 rows sent and stored). The ceiling is above 32,000, so this host can take the North Star (11,600) with room to spare, and ClickHouse is not the limit. Setup: 500 rows per INSERT, `wait_for_async_insert=1`, ClickHouse on CPUs 8-13, k6 on 14-15 (k6 at 78% of one CPU at 32,000). Findings while getting there:
  - **In bytes:** the k6 mix averages 208 bytes of raw payload per event (JSON 245, JSON with extra fields 294, Syslog 152, CEF 154, LEEF 181). At 32,000 events/s that is 6.7 MB/s (53 Mbps) of payload, and 12.2 MB/s (97 Mbps) on the wire to ClickHouse: 380 bytes per event, with the ids, `received_at`, the JSON field names and the HTTP framing. ClickHouse wrote 12.1 MB/s uncompressed and about 1.5 MB/s to disk (ZSTD, about 8 to 1, before merges). The North Star, 11,600 events/s of this mix, is about 2.4 MB/s (19 Mbps) of payload. Real vendor events can be larger.
  - **This run wrote the raw row only.** ULPF writes each event twice: the raw row, and the canonical row in the source's `events_<source>` table. So it needs about 2 rows per event, in more tables, with more inserts and parts. If a canonical row costs about as much as a raw row, the ceiling is at least 16,000 ULPF events/s. That is an estimate, not a measurement; a dual-write ClickHouse mode would measure it.
  - **One INSERT per event is the wrong test.** It measures ClickHouse's cost per query, and it needs one waiting query per event. The first attempt (2026-11-15) ran the laptop to 62% memory pressure at 4,000 events/s, and `systemd-oomd` killed VS Code. ClickHouse mode now sends batches (`--batch`, default 500, as ULPF does), with `received_at` spread over the interval of each batch.
  - **A memory guard** in `run.sh` stops a step below 2 GB free (the step shows as INVALID) and refuses to start below 4 GB. A user process cannot be exempted from `systemd-oomd`, which ignores exemptions for cgroups that root does not own.
  - **Two bugs in the per-second count, both fixed:**
    - The judged window ended 2 s before `t_end`, which is taken after k6 exits. Shutdown adds 2-3 s, so the window included a half-empty last second. It now ends 5 s before the planned end.
    - With `DateTime` bounds on the `DateTime64(3)` column, ClickHouse 26.3 dropped whole parts near the bound (300 of 1,000 rows in the reproduction), probably in part pruning; I did not verify the cause in the source. The bounds are now `DateTime64(3)`. `tests/test-sql.sh` has a regression check, red before the fix. The old test data had no fractional timestamps (`+ number / 1000` gave whole seconds), so it could not catch this.
- [x] 4. ULPF baseline on `main`: noise band, knee, profile (runs 2 to 4). Write the bottleneck in this plan.
  - **First coarse ramp (2026-11-15, `perf/results/2026-11-15T221449-base-coarse`, `8e5b731`, Podman 3.4.4, governor `performance`, engine on CPUs 0-7 at about 2.2-2.5 GHz):**

    | Offered/s | Verdict | P1 | Median | k6 P50 / P99 / max | Dropped |
    |---|---|---|---|---|---|
    | 250 | PASS | 249 | 250 | 1 ms / 4.9 ms / - | 0 |
    | 500 | FAIL | 364 | 500 | 0.8 ms / 12.2 s / 24.8 s | 205 |
    | 750 | FAIL | 649 | 750 | 1.4 ms / 11.4 s / 39.7 s | 295 |
    | 1,000 | FAIL | 808 | 1,000 | 16 ms / 9.6 s / 39.6 s | 513 |
    | 1,500 | FAIL | 930 | 1,487 | 507 ms / 6.2 s / 35.1 s | 9,746 |
    | 2,000 | FAIL | 923 | 1,483 | 1.1 s / 6.2 s / 38.0 s | 63,767 |

    0 lost (645,479 accepted, 645,479 raw rows and 645,479 canonical rows). The strict knee is 250/s. The engine's sustained median caps at about 1,485/s (about 0.31 MB/s, 2.5 Mbps, of 208-byte events). From 500/s on, most requests are fast (P50 under 2 ms), but stalls of 10-40 s appear. So the engine does not degrade gently: it freezes.
  - **What the JFR recording of that run shows (default settings, 20 ms thresholds):**
    - **Lock convoy on the flush.** 12,780 waits to enter `ClickHouseIngestionRepository.flush()` / `flushCanonical()` (both `synchronized` on one object), average about 8 s, maximum 22.4 s. This is the stall.
    - **`ConcurrentLinkedQueue.size()` takes 64% of all CPU samples** (12,764 of about 20,000). 11,960 of them are in `enqueue()` (the raw queue and the `recentRawHistory` trim loop) and 608 in `enqueueCanonical()`. `size()` walks the whole queue, and the queues grow while the flush is blocked. So a stall makes each enqueue slower, which makes the stall longer.
    - **GC is not the cause:** 5.26 s of pauses in 819 s, median 3.35 ms.
    - Reads from ClickHouse: 5,748 socket reads, 1.8 MB in total, all to `host.containers.internal`.
  - **Wall-clock profile at 500/s (`perf/results/2026-11-15T223317-base-wall-500`):** 79% of all thread time is request threads waiting to enter the flush lock, about 200 queued behind each holder. Inside the lock: 68% ClickHouse insert (mostly the async wait), 30% SQLite (the Merkle block), 0.2% SHA-256.
  - **The results, with the method, the host and the limits, are in `docs/perf/RESULTS.md`.** That file is the lasting record; this plan is the work log.
  - **Noise band (5 runs, 2026-11-15, 22:37-23:57):** the knee is 400/s in 5 of 5 runs (400 passed every time, 500 failed every time). Saturation varies by about ±15% (median 1,065-1,239 at 1,500 offered). 0 lost in every run. The watchdog took no action; the CPU stayed at 58-60 C. Details: `docs/perf/RESULTS.md`. Step 4 is done.
- [x] 5. Instrumentation, test first. Full backend suite. A/B cost run (run 5). Done 2026-11-16.
  - Meters (names, tags and buckets from the Grafana plan's metric table): `ulpf.clickhouse.flush.duration` and `ulpf.clickhouse.batch.size` (tag `stage`), `ulpf.clickhouse.insert.errors`, `ulpf.records.reenqueued`, `.degraded` and `.dropped` (tag `table`), and the gauges `ulpf.queue.raw.size` and `ulpf.queue.canonical.size` (`MetricsConfig`). Tests: `ClickHousePipelineMetricsTest` (8) and `QueueMetricsTest` (1), red first.
  - The actuator: `/actuator/health` is open; `/actuator/metrics` needs HTTP Basic as the `monitor` user (`ActuatorSecurity`, its own filter chain). Without a monitor password, everything except health is denied. Tests: `ActuatorSecurityTest` (5), `ActuatorSecurityNoPasswordTest` (1), `ActuatorSecurityConfigTreeTest` (1). Red check: without `ActuatorSecurity`, health answered 403.
  - Full backend suite: 430 tests, 0 failures.
  - Fix after the PR (2026-11-16): `ActuatorSecurityTest.healthIsOpen` and `ActuatorSecurityNoPasswordTest` failed in CI with 503. `/actuator/health` includes Spring's database health check, which is DOWN without ClickHouse, and CI has no ClickHouse. Locally they passed only because a ClickHouse was running. The tests now set `management.health.db.enabled=false`, because they check the security chain, not the databases. Reproduced locally with ClickHouse stopped (red), then green; full suite without ClickHouse: 430 tests, 0 failures. The product keeps the database health check: health must report DOWN when ClickHouse is down.
  - A/B/A/B cost run: no cost by the agreed rule (knee 400 in 4 of 4 runs; saturation −7%, inside ±15%). P1 at 500/s was lower with the meters (325 and 249, against 428 and 387). Two runs cannot tell the cause (the meters, chance, or the integrity ledger, which grew with each run), so Phase 1 measures against the instrumented build, on fresh volumes. Numbers: `docs/perf/RESULTS.md`, "what the metrics cost".
  - Deviation: `run.sh` now reads the queue gauges and the flush timer during each step (`queue.csv`, `flush-before/after.json`), and `report.py` shows them.
- [x] 6. Podman against Docker (run 6). If Docker wins by more than the noise band, write it up for the team lead's decision (section 3.1, decision 3). Done 2026-11-16: **Docker does not win.** On fresh volumes, Podman and Docker match within the noise band (raw flush 34.9 against 34.5 ms at 200/s; knee 400 on both). Recommendation: stay on Podman. Numbers: `docs/perf/RESULTS.md`, "rootless Podman against rootless Docker".
  - The first comparison (Docker on fresh volumes, Podman on the old ones) showed Docker 4 times faster in the raw flush. The cause was the growing integrity ledger (an O(n) SQLite query per block), not the runtime. So P-fresh was added: Podman in a separate compose project (`PERF_PROJECT=ulpffresh`, a new kit option), so the team lead's volumes stayed untouched.
  - Deviations: the image was copied into Docker (`podman save`, `docker load`) instead of being built again. That way both runtimes ran the same bits, and the build did not need Maven Central (item 18). Under Docker, the engine reaches ClickHouse by service name (`perf/stack.sh` sets `CLICKHOUSE_URL`), and the ClickHouse health check uses `127.0.0.1` (item 19, new).
  - Found on the way: under Docker, the ClickHouse health check in `compose.yaml` never passes (item 19). Podman 3.4.4 cannot use the `ulpf_ulpf-net` network (podman-compose writes it with CNI version 1.0.0, and the `firewall` plugin of 3.4.4 does not support that version), so the containers run on the default network without DNS. The Grafana plan already requires Podman 4.9 or Docker for service names (row 28).
- [x] 7. The nginx path (run 7). Done 2026-11-16: knee 400, the same as straight to the engine; k6 P50 about 0.25 ms higher; saturation −7% (inside the band); 0 lost. nginx has no upstream `keepalive`, so measure it again after Phase 1, when the engine takes more. Numbers: `docs/perf/RESULTS.md`.
- [x] 8. Docs (section 3.6). Outcome. Propose Phase 1 from the profile, for approval. Done 2026-11-16: `docs/perf/RESULTS.md`; `ARCHITECTURE.md` §3.4 (where the integrity work runs, and its known limit) and §3.11 (2 new decision rows); `API_SPECIFICATION.md` §1 and `AGENTS.md` (claims of "zero overhead" and "out-of-band" hashing, which the profile contradicts, corrected); `to-do-list.md` items 13 and 19. Phase 1: section 9.

## 7. Risks and open questions

- **Wrong conclusions from a noisy host.** The laptop has mixed core types, a power governor and thermal limits. Mitigations: the CPU layout, the governor rule, the thermal counters, and the noise band (research §7.6). If the noise band is wider than about 10%, the results are reported as ranges, and we need a quieter machine for Phase 1 decisions.
- **One host runs everything.** k6, ULPF and ClickHouse share 16 CPUs and 15 GB of RAM. The North Star may be out of reach on this laptop even with good code. The plain ClickHouse run (step 3) shows the ceiling of this host. If that ceiling is below 11,600 events/s, the target needs a bigger machine, and we report that rather than chase it.
- **async-profiler on Alpine (musl)** is not verified. Fallback: agent mode through `JDK_JAVA_OPTIONS` (research §1).
- **The Docker run needs `extra_hosts`** until the Grafana plan's Task 0. The override file covers it, and it is used only for test runs.
- **The instrumentation can cost throughput.** Step 5 measures it.
- **Test data:** onboarding runs the current mapping engine on the sample payloads, so the mapping changes with the samples. The samples are committed and their SHA-256 is recorded for each run, so runs compare.
- **Downloads during setup:** k6, async-profiler, the Actuator artifacts (through Maven), and the container base images. All happen once, with internet. The stack still runs offline.
- **Open question for the team lead:** the old `dev-tools/` scripts are git-ignored. Should `perf/README.md` say that they are replaced, and should they be deleted later?

## 8. Outcome

Phase 0 is done (2026-11-16). All numbers, with their method and limits, are in `docs/perf/RESULTS.md`.

- **The knee of `POST /v1/events` is 400 events/s** on `8e5b731` (5 of 5 runs), with fresh or young volumes. Above it, the engine freezes for 10-40 s at a time and saturates at about 1,000-1,500 events/s. No event was lost in any run.
- **ClickHouse alone takes more than 32,000 rows/s** on the same laptop. So the North Star (11,600 events/s) is a design problem of the engine, not a hardware limit.
- **Causes, measured:**
  - a lock convoy on the `synchronized` flush that request threads run;
  - the `wait_for_async_insert` wait inside that lock;
  - the SQLite integrity work inside that lock, with an O(n) lookup that makes ingestion slower as the ledger grows (knee 300 on the old test database);
  - O(n) `ConcurrentLinkedQueue.size()` on every event;
  - a SQLite query per event for the mapping versions.
- **The meters** (Actuator, `/actuator/metrics` behind the monitor password, the queue, flush, batch and failure meters) are in, tested, and have no cost that the agreed rule can detect. The Grafana plan builds on them.
- **Rootless Podman matches rootless Docker** on the same starting state. The team lead's rule therefore keeps Podman. Found on the way: the ClickHouse health check fails under Docker (item 19, new). Podman 3.4.4 cannot use the `ulpf_ulpf-net` network, so the containers run on the default network without DNS. This is already known: the Grafana plan (row 28) requires Podman 4.9 or Docker for service names.
- **nginx** costs about 0.25 ms per request today, and no measurable throughput.
- **Not done as planned:**
  - the kit stays out of git (decision 7);
  - the fine ramp around the knee (`base-fine`) was replaced by the noise band, which already brackets the knee between 400 and 500 in 5 of 5 runs.


## 9. Proposal: Phase 1 (not approved; for the team lead)

Phase 1 changes code for speed. It starts only after the team lead approves it. The numbers behind each item are in `docs/perf/RESULTS.md`.

**Target.** The knee is 400 events/s today. The North Star (11,600 sustained) is 29 times that, and ClickHouse alone takes more than 32,000 rows/s on this host. So the limit is the engine's design, not the hardware. Phase 1 does not promise a number. Each change is measured alone, and we stop when the next limit is ClickHouse or the CPU.

**Method.** One change at a time. Each change gets a failing test first, then 3 runs in A/B/A/B order against the instrumented build (see step 5: the meters stay on both sides). Each run starts on fresh volumes, because the integrity ledger slows ingestion as it grows (`docs/perf/RESULTS.md`, 2026-11-16). A change counts only if the knee moves by more than 1 step, or saturation moves by more than 15%.

**Changes, in the proposed order** (the cheapest large gain first):

0. **Make the latest-block lookup O(1).** Each raw flush writes one integrity block per source in the batch (up to 5 here). Before each block, `IntegrityRepository.findLatestBlockHash` runs `WHERE source_id = ? ORDER BY block_id DESC LIMIT 1`. The only index is `(source_id, created_at)`, so SQLite reads every block of the source and sorts them (`USE TEMP B-TREE FOR ORDER BY`). Measured on 2026-11-16, on a copy of the test database (102,369 blocks): **12.35 ms per call with 21,339 blocks for the source, 0.044 ms with 200 blocks.** So a raw flush spends about 60 ms in this query today, and the cost grows with every block ever written, also while the system is idle. At the North Star rate, a source gets about 23 blocks per second, so after one day (about 2 million blocks) the query would take more than 1 s, if the cost stays linear.
   - Proposal: an index on `(source_id, block_id)`. Rejected: an in-memory cache of the latest hash only. It is wrong after a restart or with a second writer, unless it is also loaded from the table; the index is the fix, and a cache can come later if the profile still shows the query.
   - Test first: `EXPLAIN QUERY PLAN` shows no temp B-tree; the chain is the same as before.
   - Also measure the SQLite journal mode. The database runs in `delete` mode (not WAL), so each block insert is a synced transaction.
1. **Take the flush off the request threads.** Today a request thread that fills a queue to 500 runs the `synchronized` flush itself, and up to about 200 request threads wait behind it (79% of all thread time, wall-clock profile). Proposal: `enqueue()` only adds to the queue. One flusher thread per stage (raw, canonical) drains it when it holds a batch, or after a fixed time.
   - Decision needed: what happens when the queue is full. Proposal: a bounded queue, and `503` with `Retry-After` when it is full. An unbounded queue grows until the JVM runs out of memory, and a `202` that is then lost breaks the 0-loss rule. Rejected: blocking the request thread until there is room. That brings the convoy back.
   - Tests first: a request returns while a mocked ClickHouse insert blocks; a full queue answers `503`; a failed insert still re-enqueues its batch (the existing loss tests stay green).
2. **Separate the raw and canonical flushes, and let inserts overlap.** Both methods lock the same object, so a raw flush (124-277 ms) also stops the canonical flush. Most of a raw flush is the `wait_for_async_insert=1` wait (68% of the time inside the lock). That wait is latency, not work. Proposal: 2 to 4 inserts in flight per stage. Rejected: `wait_for_async_insert=0`. Then a `202` no longer means that ClickHouse stored the event.
3. **Take the integrity work off the insert path.** 30% of the time inside the lock is the SQLite Merkle block. The blocks are chained, so they must stay in order. Proposal: one integrity thread takes each stored batch in order, after the insert succeeds. Test first: the chain is the same as today for the same batches, and a failed insert creates no block.
4. **O(1) queue checks.** `ConcurrentLinkedQueue.size()` walks the whole queue and takes 64% of the CPU samples. `enqueue()` calls it on `recentRawHistory` (5,000 entries, so every event walks 5,000 nodes) and on the batch queue. Proposal: an atomic counter for the batch queue, and a fixed ring buffer for the history. The history's only reader is the lineage lookup when ClickHouse is offline.
5. **Cache the mapping versions per source.** For every event, `EventIngestionService.loadHistoricalVersions` runs `MappingRepository.findAllValidVersionsBySourceId` (a SQLite query, no cache) and parses each version's JSON. The DEBUG log during the 300/s warm-up showed about 400 of these queries per second, so at least 1 per event. Proposal: cache the list next to `activeMappingCache`, and clear it in `invalidateSource` when a mapping version changes. Test first: 2 events cause 1 query; a new active version clears the cache.
6. **Logging (a team lead decision, then config only).** The container runs the `dev` profile (`application.yaml`: `spring.profiles.active: dev`; `compose.yaml` sets no profile). `dev` logs `com.ulpf` and `org.springframework.jdbc` at DEBUG: about 10 lines and 1 KB of log per event at 300 events/s. Decide which profile the container runs by default. Measure the cost first, as its own A/B run.

**Measure again after these:** the noise band, the knee, a wall-clock profile at the new knee, and the nginx path.

### 9.1 Handoff: how to start Phase 1 (written 2026-11-16, for the next session)

**State.**
- Phase 0 is merged (PR #28, `8412f87`).
- Branch `perf/phase-1-ingestion` was created from that commit. It has no commits yet, and only exists locally.
- The Phase 1 proposal above is **not approved**. Do not change code until the team lead approves it.

**Open decisions for the team lead.** Ask about these first; do not assume them:
1. Which Phase 1 changes are approved (items 0-6 above), and in what order.
2. What a full queue does (item 1). Proposed: a bounded queue, and `503` with `Retry-After`.
3. Stay on Podman (plan step 6). The measurement says yes, but the team lead has not confirmed it.
4. Which profile the container runs by default (item 6). Today it is `dev`, with DEBUG logs.

**Then:**
1. Write the Phase 1 plan as its own file from `docs/plans/TEMPLATE.md` (for example `docs/plans/YYYY-MM-DD-ingestion-phase-1.md`). Give it the files to change, the tests to write first, and the risks for each approved item. Link it from here, and get it approved.
2. Implement one item at a time, test first (`AGENTS.md` §4). Each item gets its own commit, and its own measurement before the next item starts.

**Code pointers** (checked on `8412f87`):
- `ClickHouseIngestionRepository`:
  - `enqueue()` and `enqueueCanonical()` run the flush on the request thread at 500 events;
  - `flush()` and `flushCanonical()` are `synchronized` on the same object;
  - `flush()` calls `BatchIntegrityService.processRawBatch()` after the insert, inside the lock;
  - `recentRawHistory` (5,000 entries) has only one reader: the lineage lookup when ClickHouse is offline.
- `IntegrityRepository.findLatestBlockHash` runs the O(n) lookup; the index is in `sqlite/schema.sql` (`idx_integrity_source_created`).
- `EventIngestionService.loadHistoricalVersions` calls `MappingRepository.findAllValidVersionsBySourceId` for every event, without a cache. `MappingRepository.invalidateSource` clears `activeMappingCache` only.
- `application-dev.yaml` sets `com.ulpf` and `org.springframework.jdbc` to DEBUG. `application.yaml` makes `dev` the active profile.

**How to measure** (the kit is in `perf/`, local to the team lead's machine; read `perf/README.md` first):
- **Baseline:** the instrumented build, which is `main` at `8412f87`, not `8e5b731`.
- **Start every run on fresh volumes** (`PERF_PROJECT=<name>` gives a separate compose project), or record the integrity block count. The ledger slows ingestion as it grows.
- Run 3 times in A/B/A/B order, on the same steps: `--steps 200,300,400,500,750,1000,1500 --hold 120`. As the knee rises, add higher steps.
- **Disk:** the ClickHouse system logs grow by about 1 GB per hour of load. Truncate them between runs, and keep more than 3 GB free (`perf/watchdog.sh` stops the runs at 3 GB).
- No builds or tests while a measurement runs.
- Before each test session, the team lead sets the CPU governor to `performance` (needs root).
