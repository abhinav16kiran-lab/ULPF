# Research: Profiling and Load-Testing the Ingestion Path

- **Date:** 2026-11-13
- **For:** to-do item 13 (North Star: 11,600 events/s at P99 of 1-second windows, 12,000 events/s peak, no event loss)
- **Status:** Research only. No code or config is changed. Each change that follows from this document needs its own plan (`AGENTS.md` §3).
- **Sources:** numbered in [Sources](#sources). "Observed" means that I ran a command on the team host on 2026-11-13. "Not verified" means that I did not find a primary source.

## Summary

The limit on throughput is not measured yet. The old benchmark (995.4 events/s, `README.md` §11) used "auto-ramping concurrency threads". That is a closed-model test. A closed-model test hides queueing delay ([Question 2](#2-load-generation-that-measures-correctly)). The new tests must use an open-model, constant-rate generator, and the server side must count the events.

Recommended toolset (4 tools, all offline-installable):

| # | Tool | Version (2026-11-13) | Role | Install |
|---|---|---|---|---|
| 1 | JDK Flight Recorder (JFR) and the `jfr` command | Built into JDK 21. The runtime image has Temurin 21.0.13 (observed) | Always-on recording for the whole run: locks, socket I/O, GC, safepoints, CPU load | Nothing. `jfr` is in the JRE image (observed). |
| 2 | async-profiler | 4.6, released 2026-10-19, Apache-2.0 [11][12] | Wall-clock, lock, allocation and CPU flame graphs at the knee | One tarball |
| 3 | k6 | v2.4.0 (2026-11-12) or v2.3.0 (2026-09-21), AGPL-3.0 [34] | Open-model, constant-rate load with a varied body mix | One static binary |
| 4 | `pidstat` (sysstat) | Installed on the host (observed) | CPU, run-queue wait and context switches per process | Already present |

Built-in data sources (no install): the ClickHouse `system.*` tables [63]-[66], unified JVM logging (`-Xlog`) [4], and Spring Boot Actuator `/actuator/metrics` [53] (the Actuator starter is not in `pom.xml` yet). Add JMH 1.37 [46] only if a profile shows that a pure function costs a large share of CPU.

Main findings that affect the method:

1. The host has a hybrid CPU. CPUs 0-7 are 4 performance cores with 2 threads each (max 4.5 GHz). CPUs 8-15 are 8 efficiency cores (max 3.3 GHz) (observed with `lscpu -e`). Pin each part of the stack to fixed CPUs, or the results will not repeat.
2. Rootless Podman on this host cannot pin containers yet. The user cgroup has only the `memory pids` controllers (observed). Podman documents the fix: delegate `cpu cpuset` with a systemd drop-in [78].
3. This host runs Podman 3.4.4 with slirp4netns 1.0.1 and no pasta (observed). Podman 5.0 changed the rootless default from slirp4netns to pasta "for improved performance" [77]. The current Podman is 6.2.0 [79]. The network path can be a limit, so measure it ([Question 6](#6-container-and-os-level)).
4. ClickHouse 26.2 turned on `async_insert` by default [69]. ULPF also sets `async_insert=1`, `wait_for_async_insert=1` and `async_insert_busy_timeout_ms=200` in `infra/clickhouse-config/users.d/async_inserts.xml`. With `wait_for_async_insert=1`, the client waits until the server flushes its buffer [62]. In 26.3, `async_insert_busy_timeout_ms` is an alias of `async_insert_busy_timeout_max_ms`, and the adaptive timeout moves between 50 ms and that maximum [68].
5. In the code, `ClickHouseIngestionRepository.flush()` is `synchronized`, and a request thread calls it when the queue reaches 500 events. So a request thread can wait for the lock, and the lock owner can wait for ClickHouse. This is a hypothesis. A wall-clock profile with lock events answers it directly ([Question 1](#1-cpu-and-wall-clock-profiling)).
6. The runtime image has no `jcmd` (observed). Start JFR with `-XX:StartFlightRecording` through the `JDK_JAVA_OPTIONS` environment variable [4], and let the JVM write the file on exit.

## Context from the code

Another agent maps the hot path in detail. These facts are enough to fit the method to the code. I read them on 2026-11-13 at commit `608d279`.

- `EventController` returns `202` after `EventIngestionService` builds a `RawEventRecord` with a request-time timestamp (`received_at`) and calls `enqueue()`.
- `enqueue()` adds to a `ConcurrentLinkedQueue`. It calls `flush()` on the request thread when `bufferQueue.size() >= 500`. A `@Scheduled(fixedDelay = 1000)` method also flushes.
- `flush()` and `flushCanonical()` are `synchronized`. Inside the lock, `flush()` runs `JdbcTemplate.batchUpdate`, then `BatchIntegrityService.processRawBatch` (SHA-256, Merkle root, SQLite reads and writes).
- `ConcurrentLinkedQueue.size()` "is NOT a constant-time operation" [86]. `enqueue()` calls it on `bufferQueue` and on `recentRawHistory` (up to 5,000 entries) for each event.
- The ClickHouse driver is `clickhouse-jdbc` 0.4.6 (`pom.xml`). The current release is 0.11.1 (2026-11-09) [88]. I did not verify how 0.4.6 sends a JDBC batch (one HTTP request or more). Measure it in `system.query_log` ([Question 5](#5-clickhouse-side)).
- `compose.yaml` points the core engine at `host.containers.internal:8123`, not at the `clickhouse` alias on `ulpf-net`. In rootless mode, each insert then leaves the container network and comes back in through a published port.
- No container has a CPU or memory limit. The JVM sees all 16 CPUs. It picks G1 and a 4.11 GB maximum heap, which is 1/4 of host memory (observed with `-XX:+PrintFlagsFinal`, and documented in [51]).
- Spring Boot 4.1 defaults: 200 Tomcat worker threads, accept count 100, 8,192 connections [55]. The scheduler pool has 1 thread named `scheduling-*` [55].

Illustration only, not a measurement: if one flush of N rows holds the lock for T seconds, and request threads queue behind it, the path caps near N / T events/s. With N = 500 and T = 0.5 s, the cap is 1,000 events/s, which is close to the old result. The profiler must confirm or reject this.

## 1. CPU and wall-clock profiling

### 1.1 JDK Flight Recorder (JFR) and JDK Mission Control (JMC)

**Overhead.** JEP 328 set the goal "At most 1% performance overhead out-of-the-box on SPECjbb2015" and "No measurable performance overhead when not enabled" [1]. The out-of-the-box set is `default.jfc`. The `profile.jfc` set "provides more data than the default configuration, but can have more overhead" [4]. I found no published overhead number for `profile.jfc`. Measure it: run the same rate with and without the recording and compare.

**Events that matter.** The table gives the JDK 21 defaults [7][8].

| Event | `default.jfc` | `profile.jfc` | What it tells you |
|---|---|---|---|
| `jdk.ExecutionSample` | every 20 ms | every 10 ms | Java methods on CPU. Only threads that run Java code are sampled [2]. |
| `jdk.NativeMethodSample` | every 20 ms | every 20 ms | Threads in native code, for example a socket poll |
| `jdk.ObjectAllocationSample` | 150/s | 300/s | Allocation by stack (throttled sampling) |
| `jdk.JavaMonitorEnter` | threshold 20 ms | 10 ms | Wait to enter a `synchronized` block, with the stack and the monitor class |
| `jdk.JavaMonitorWait` | 20 ms | 10 ms | `Object.wait()` |
| `jdk.ThreadPark` | 20 ms | 10 ms | `java.util.concurrent` locks, futures, idle pool threads |
| `jdk.SocketRead`, `jdk.SocketWrite` | 20 ms | 10 ms | Blocking socket I/O, with host and port |
| `jdk.GarbageCollection`, `jdk.GCPhasePause` | 0 ms | 0 ms | Each GC and its pauses |
| `jdk.SafepointBegin` | 10 ms | 0 ms | Safepoints |
| `jdk.CPULoad` | every 1 s | every 1 s | JVM user, JVM system and machine CPU |

A wait shorter than the threshold makes no event. A 200 ms flush shows up, but a convoy of 5 ms waits does not. For the knee run, set the thresholds to 1 ms on the command line. The JDK 21 syntax is `<event-name>#<setting-name>=<value>` inside `-XX:StartFlightRecording` [4].

**How to start it in a container.** The `java` launcher prepends the content of `JDK_JAVA_OPTIONS` to its command line [4]. So no change to the `ENTRYPOINT` is needed. Example for a profiling override (a later plan):

```bash
JDK_JAVA_OPTIONS="-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints \
 -XX:StartFlightRecording=name=ulpf,settings=profile,filename=/profiles/ulpf-%t.jfr,dumponexit=true,maxage=30m,jdk.JavaMonitorEnter#threshold=1ms,jdk.ThreadPark#threshold=1ms,jdk.SocketRead#threshold=1ms,jdk.SocketWrite#threshold=1ms \
 -Xlog:gc*,safepoint:file=/profiles/gc-%t.log:time,uptime,level,tags:filecount=5,filesize=20M"
```

- `filename` expands `%p` and `%t` [4]. `dumponexit=true` writes the file when the JVM shuts down [4]. Stop the container with a long timeout (for example `podman stop -t 60`) so that the dump can finish.
- `jcmd <pid> JFR.start`, `JFR.dump` and `JFR.stop` do the same at run time [5]. The `eclipse-temurin:21-jre-alpine` image has no `jcmd` (observed). Its `bin/` has only `java`, `jfr`, `jrunscript`, `jwebserver`, `keytool` and `rmiregistry`. I did not verify a `jcmd` attach from the host into a rootless container.

**How to read it without a GUI.** `jfr view <view> <file>` prints an aggregated table [6]. Use `jfr --help view` for the list. Useful JDK 21 views [9]: `hot-methods`, `contention-by-site`, `contention-by-class`, `native-methods`, `socket-reads-by-host`, `gc-pauses`, `safepoints`, `allocation-by-site`, `thread-cpu-load`, `container-cpu-throttling`. `jfr summary <file>` gives the event counts [6].

**JMC.** JMC 9.1.2 GA (build 2026-01-14) is the current release, under the Universal Permissive License 1.0 [10]. It gives a GUI and flame graphs for a `.jfr` file. It is optional. The Linux x64 tarball is about 104 MB [10].

**Safepoint bias.** A sampler that walks stacks only at safepoints risks the "safepoint bias problem": "a frequently-executed span of code might not be anywhere near a safepoint" [3].

- JDK 21: JFR avoids this bias. It suspends the thread and parses the stack "at code locations that are not necessarily safepoints", and it uses heuristics to do so [3].
- JDK 21 limits [2]: the execution sampler samples only threads that run Java code, it selects "only a subset of threads" at each interval, and it does not report missed samples. A short recording (about one minute) can be inaccurate.
- JDK 25 adds cooperative sampling (JEP 518). The sampler records the program counter and stack pointer, and the thread builds the stack at its next safepoint, "adjusting for safepoint bias" [3]. JDK 25 also adds `jdk.CPUTimeSample` (JEP 509), an experimental, Linux-only CPU-time sampler [2]. ULPF must compile for Java 21 (`AGENTS.md` §8). A JDK 25 profiling run would change the runtime under test, so do not compare its numbers with JDK 21 numbers.

### 1.2 async-profiler

**Version and license.** 4.6 is the current stable release (published 2026-10-19 on GitHub, dated 2026-10-20 in the changelog) [12][13]. The license is Apache-2.0 [11]. The Linux x64 download is `async-profiler-4.6-linux-x64.tar.gz` [11]. Since 2.8.2, "The same .so works with glibc and musl" [13], so the library loads in the Alpine image. I did not verify the `asprof` launcher on Alpine.

**Modes.**

| Mode | Flag | What it measures | Needs perf_events? |
|---|---|---|---|
| cpu | `-e cpu` | CPU time, with Java, native, JVM and kernel frames [14] | Yes. If kernel profiling is not available, it falls back to `ctimer` [15]. |
| itimer | `-e itimer` | CPU time through `setitimer(ITIMER_PROF)`. One signal per process at a time, not spread evenly over threads [15]. | No |
| ctimer | `-e ctimer` | CPU time through `timer_create`. No kernel stacks. Resolution is limited by the jiffy (4 ms or 10 ms) [15]. | No |
| wall | `-e wall` | All threads "regardless of thread status: Running, Sleeping or Blocked" [14]. Most useful with `-t` [14]. | No |
| alloc | `--alloc N` | Heap allocation, sampled by TLAB [14] | No |
| lock | `-e lock` or `--lock T` | Nanoseconds spent waiting to enter a lock. The top frame is the lock class [14]. | No |

Defaults in the 4.6 source: the CPU interval is 10 ms, the allocation interval is 512 KiB and the lock interval is 10 µs [23]. Wall mode uses 5 times the CPU interval (50 ms) unless you set `-i`, and it signals up to 8 threads per tick [24].

**Several events in one file.** "The only output format that supports multiple events together is JFR" [14]. Example: `asprof -e wall --alloc 2m --lock 10ms -f profile.jfr <pid>`. The file then has `jdk.ExecutionSample`, `jdk.ObjectAllocationInNewTLAB`, `jdk.JavaMonitorEnter` and `jdk.ThreadPark` events [14]. `jfrconv` converts the file to flame graphs. It has `--wall`, `--lock`, `--alloc`, `--cpu`, `-t` (split by thread), `--state runnable|sleeping`, `--total` (sum time, not samples), and `--from`/`--to` [20].

**Flame graphs.** The x-axis is not time. Frames are sorted and merged, and a flame graph "does not preserve information on _when_ a particular stack trace was taken" [21]. The width is the number of samples [21]. For time slices, use `--from`/`--to` [20] or the heatmap output [20].

**How it avoids safepoint bias.** The README states that it "does not suffer from the Safepoint bias problem" [11]. It walks Java stacks outside safepoints. AsyncGetCallTrace (AGCT) is "a non-standard extension of HotSpot JVM to obtain Java stack traces outside safepoints" [18]. Since 4.2, the default stack walker on HotSpot is "VM Structs", because AGCT can crash or fail in corner cases [18]. If you attach after JVM start, add `-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints`. Without these flags, "there is a high chance that simple inlined methods will not appear in the profile" [17].

**In a container.**

- Docker's default seccomp profile blocks `perf_event_open`. Use `--security-opt seccomp=unconfined`, and "`--cap-add SYS_ADMIN` may be required" [16].
- Podman's current default seccomp profile allows `perf_event_open` only with `CAP_PERFMON` or `CAP_SYS_ADMIN` [80]. The older profile on this host allows it only with `CAP_SYS_ADMIN` (observed in `/usr/share/containers/seccomp.json`).
- The host sysctl also applies. Upstream `perf_event_paranoid` defaults to 2, and level 2 or higher disallows kernel profiling for users without `CAP_PERFMON` [83]. This host has `perf_event_paranoid=4` and `kptr_restrict=1` (observed). Upstream documents only the values -1 to 2 [83], so 4 is a distribution patch. I did not find a primary source for its meaning. For kernel stacks, async-profiler documents `sysctl kernel.perf_event_paranoid=1` and `sysctl kernel.kptr_restrict=0` [22].
- Rootless Podman: added capabilities apply inside the user namespace. I did not verify whether that satisfies the kernel perf check. Expect that `-e cpu` falls back to `ctimer` [15]. That is acceptable for ULPF, because it loses only kernel frames.
- Wall, lock, alloc and ctimer need no perf_events [15]. So the knee profile needs no host change and no extra capability.
- Attach from inside the container with `podman exec`. The profiler "must be run by exactly the same user (and group)" as the JVM, and a different `/tmp` breaks the attach [17]. `podman exec` gives the same user and the same `/tmp`. From the host, use the host PID, run as a privileged user, and make the library path match or use `--libpath`. `--fdtransfer` is another option [16].
- The docs do not list `ptrace` as a requirement [16][17]. I did not verify the attach mechanism.
- If attach fails, load it as an agent at start: `-agentpath:/opt/async-profiler/lib/libasyncProfiler.so=start,event=wall,...,file=...`. `timeout=N` stops it after N seconds, and `loop=60s` writes one file per minute [19].

### 1.3 Which tool answers which question

| Question | Best tool | Why |
|---|---|---|
| Is the request thread blocked on the `synchronized` flush, or on network I/O? | async-profiler `-e wall -t --lock 1ms` in one JFR file. Cross-check with JFR `contention-by-site` and `jdk.SocketRead`. | Wall mode samples every thread in every state [14], so the stack shows where each thread waits. Lock mode gives the wait time per lock site [14]. |
| Which code burns CPU? | async-profiler `-e cpu` (or `ctimer`) | It includes native, JVM and kernel frames [14]. The JDK 21 JFR sampler sees only Java code and a subset of threads [2]. |
| Is GC or allocation the cost? | JFR GC events, `-Xlog:gc*`, async-profiler `--alloc` | GC events have no threshold [7]. |
| What happened over the whole run? | JFR | Low overhead [1], always on, one file per run |
| Are safepoints slow? | `-Xlog:safepoint` [4], JFR `safepoints` view [9], async-profiler `--ttsp` [19] | `--ttsp` records only between the safepoint request and the VM operation [19]. |

**The key question.** "Lock or network" is often "both at once". One thread holds the monitor and waits on ClickHouse. The other threads wait for the monitor. The wall-clock graph shows both stacks, and the parent frames tell you which code owns the wait. Do not rely only on `jdk.SocketRead`. The JDK 21 socket code (`NioSocketImpl`) polls the socket for timed reads [87]. An HTTP client that reads on another thread makes the request thread wait in `park`, not in a socket read. Section [7.4](#74-read-a-wall-clock-flame-graph-for-lock-and-io-waits) gives the reading steps.

## 2. Load generation that measures correctly

### 2.1 Coordinated omission

Gil Tene describes the problem in the wrk2 README [25]. A closed-loop generator measures latency "from the sending of the first byte of the request to the time the complete response was received". Because "each connection will only begin to send a request after receiving a response, high latency responses result in the load generator coordinating with the server to avoid measurement during high latency periods" [25].

The fix in wrk2 is a constant-throughput plan. It measures latency "from the time the transmission *should* have occurred" [25]. HdrHistogram has a related correction, `recordValueWithExpectedInterval()` [26]. Its README gives an example. A system responds in 1 ms for 100 s, then pauses for 100 s. The raw histogram shows about 99.99% of results at 1 ms or less, but the corrected one shows only about 50% [26].

k6 calls the two styles the closed model and the open model. In the closed model, "the execution time of each iteration dictates the number of iterations executed". In the open model, "VUs arrive independently of iteration completion" [27].

What this means for ULPF:

- A closed-model test with 64 threads lowers its own rate when ULPF stalls. The reported throughput is then a result of the stall, not the capacity. The P99 that the test reports is also too low.
- The North Star target is a rate. An open-model test offers a fixed rate. If ULPF cannot keep up, the backlog, the latency and the dropped requests grow, and the failure is visible.

### 2.2 Tool comparison

| Tool | Model | Constant rate | POST, headers, varied bodies | Max rate from one machine (author's claim) | Offline install | Per-second data | Version, license |
|---|---|---|---|---|---|---|---|
| wrk2 | Constant throughput over fixed connections. Latency counts from the planned send time [25]. | `-R` / `--rate` [25] | LuaJIT script [25] | "significant load ... on a single multi-core CPU", no number [25] | Build from source (no releases, last commit 2019-09-24) | Summary and HdrHistogram only | No release, Apache-2.0 |
| k6 | Open with `constant-arrival-rate` and `ramping-arrival-rate`, closed with the other executors [27] | Yes [28][29] | JavaScript | "up to 300,000 HTTP requests per second" in some cases [31] | Static binary [34] | CSV, one row per metric sample, Unix-second timestamps by default [32] | v2.4.0 / v1.8.2, AGPL-3.0 |
| Gatling | Open (`injectOpen`) and closed (`injectClosed`) [35] | `constantUsersPerSec` [35] | Java, Scala, Kotlin DSL | Not verified | Needs a JDK and a build tool. Offline use not verified. | HTML report | 3.16.0, Apache-2.0 [36] |
| vegeta | Open. Workers grow to sustain `-rate`. `-rate=0` with `-max-workers` gives a closed model [37]. | `-rate` [37] | Targets file with headers and `@body` files, or JSON lines with base64 bodies [37] | Not stated | Static binary [37] | Per-request CSV with nanosecond timestamps [37] | v12.13.0, MIT |
| oha | Fixed schedule with `-q`, but a request goes out only when one of the `-c` connections is free. `--latency-correction` measures from the scheduled time [38]. | `-q` [38] | `-H`, and `-Z` reads one body per line from a file [38] | Not stated | Static binaries [38] | Per-request CSV [38] | v1.16.0, MIT |
| hey | Closed. `-q` is a rate limit per worker [39]. | Per worker only | One body (`-d` / `-D`) [39] | Not stated | Binary [39] | Per-request CSV [39] | v0.1.5, Apache-2.0 |

### 2.3 Recommendation: k6

Use k6, for these reasons:

1. It is open-model, and it reports when it cannot keep the rate. `dropped_iterations` counts "iterations that weren't started due to lack of VUs" [30]. A zero value proves that the generator offered the full rate. A non-zero value at a fixed `maxVUs` tells you that responses got slow.
2. `ramping-arrival-rate` gives a step ramp in one run [29].
3. JavaScript makes the body mix easy: Syslog, CEF, LEEF and JSON, each with its own vendor API key.
4. It is one static binary. Turn off the usage report for air-gapped runs with `--no-usage-report` or `K6_NO_USAGE_REPORT` [33].

Costs: JavaScript costs more CPU per request than C or Rust tools. I found no number, so measure the generator's CPU with `pidstat`. The k6 docs say to keep the generator under 80% CPU [31]. Each VU uses about 1-5 MB in simple tests [31]. Set `maxVUs` to bound memory. If k6 needs too much CPU, vegeta is the fallback. It is also open-model, and it writes per-request CSV [37].

Version: v2.4.0 is one day old. v2.3.0 (2026-09-21) is the settled choice [34]. Pin one version and record it. k6 2.0 removed some old commands and the `externally-controlled` executor [34]. The arrival-rate executors remain [28][29].

Example script (`bench/ingest.js`, a later plan). It assumes a JSON-lines file, one object per event, with `key`, `contentType` and `body`:

```javascript
import http from 'k6/http';
import { SharedArray } from 'k6/data';

const payloads = new SharedArray('payloads', () =>
  open('./payloads.jsonl').split('\n').filter((l) => l).map((l) => JSON.parse(l)));

const steps = (__ENV.STEPS || '1000,2000,4000').split(',').map(Number);
const hold = __ENV.HOLD || '120s';
const stages = [];
for (const r of steps) {
  stages.push({ target: r, duration: '5s' });   // short ramp to the step
  stages.push({ target: r, duration: hold });   // hold the step
}

export const options = {
  discardResponseBodies: true,
  scenarios: {
    ramp: {
      executor: 'ramping-arrival-rate',
      startRate: steps[0],
      timeUnit: '1s',
      preAllocatedVUs: Number(__ENV.VUS || 200),
      maxVUs: Number(__ENV.MAX_VUS || 2000),
      stages: stages,
    },
  },
  thresholds: {
    dropped_iterations: ['count==0'],
    http_req_failed: ['rate==0'],
  },
};

let i = 0;
export default function () {
  const p = payloads[(__VU * 7919 + i++) % payloads.length];
  http.post(__ENV.TARGET, p.body, {
    headers: { 'X-API-Key': p.key, 'Content-Type': p.contentType },
  });
}
```

Commands. Run the coarse ramp first, then a finer ramp around the knee, then a soak at the target:

```bash
# Coarse ramp. Generator pinned to CPUs 14-15.
taskset -c 14,15 k6 run --no-usage-report \
  -e TARGET=http://127.0.0.1:8080/v1/events \
  -e STEPS=500,1000,1500,2000,3000,4000,6000,8000,10000,12000,14000,16000 -e HOLD=120s \
  --out csv=results/$RUN_ID-k6.csv.gz bench/ingest.js

# Fine ramp around the knee K (example: K is near 2000).
taskset -c 14,15 k6 run --no-usage-report -e TARGET=... \
  -e STEPS=1600,1800,2000,2200,2400 -e HOLD=180s \
  --out csv=results/$RUN_ID-k6.csv.gz bench/ingest.js

# Soak at the target, then a burst at the peak.
taskset -c 14,15 k6 run --no-usage-report -e TARGET=... \
  -e STEPS=11600 -e HOLD=900s --out csv=results/$RUN_ID-k6.csv.gz bench/ingest.js
taskset -c 14,15 k6 run --no-usage-report -e TARGET=... \
  -e STEPS=12000 -e HOLD=120s --out csv=results/$RUN_ID-k6.csv.gz bench/ingest.js
```

Little's law sets the VU count: VUs needed = rate × response time. At 12,000/s and 20 ms, that is 240 VUs. If ULPF stalls for 0.5 s, it needs 6,000. A fixed `maxVUs` turns a stall into `dropped_iterations`, which is the signal that you want.

The item 13 baseline (plain ClickHouse, same tables) can use the same script. Point `TARGET` at the ClickHouse HTTP interface with one `INSERT ... FORMAT JSONEachRow` row per request. Use the same `async_insert` settings as ULPF, because 26.3 enables `async_insert` by default [69].

### 2.4 Keep the generator off the CPUs of the system under test

- `taskset -c <cpus> <command>` runs a host process on fixed CPUs. `-a` applies to all threads of an existing PID [85].
- For containers, use `--cpuset-cpus` [74], or `cpuset:` in a compose override. podman-compose maps `cpuset` to `--cpuset-cpus` (in the 1.6.0 source on this host [81]).
- Rootless Podman needs cgroup delegation first. Otherwise crun fails with "the requested cgroup controller `cpuset` is not available" [78]. Check `cat /sys/fs/cgroup/user.slice/user-$(id -u).slice/user@$(id -u).service/cgroup.controllers`. On this host it shows `memory pids` (observed). The fix is a root-owned file `/etc/systemd/system/user@.service.d/delegate.conf` with `[Service]` and `Delegate=memory pids cpu cpuset`, then log out and in [78].
- `--cpus` sets a CFS quota, which can throttle [74][82]. Prefer `cpuset` for isolation, and check `cpu.stat` if you use `--cpus` ([Question 6](#6-container-and-os-level)).

A starting layout for this host (an assumption to test, not a measured optimum):

| Part | CPUs | Reason |
|---|---|---|
| core-engine | 0-7 (4 P-cores, 8 threads) | The service under test gets the fastest cores. |
| ClickHouse | 8-13 (6 E-cores) | Fixed and separate from the JVM |
| k6 | 14-15 (2 E-cores) | Keep it under 80% CPU [31]. If it goes higher, give it more and record the change. |

The same-host layout understates ULPF's capacity, because ULPF gets 8 of 16 CPUs. If a second machine is available, run k6 there and record it. To-do item 13 already asks to record where the generator runs.

## 3. Microbenchmarks with JMH

**When it is worth it.** Use JMH only after a CPU profile at the knee shows that one pure function, for example `LogFormatDetector`, a parser or `MappingTransformer`, takes a large share of CPU. JEP 509 gives the reason with an example: a 10 times faster method that takes 0.1% of the time "will only reduce the program's execution time by 0.09%" [2]. JMH compares versions of one function. It does not measure end-to-end throughput, locks or I/O.

**Pitfalls.**

| Pitfall | What happens | Fix |
|---|---|---|
| Dead-code elimination | "compilers are smart enough to deduce some computations are redundant and eliminate them completely" [41] | Return the result, or consume it with a `Blackhole` [41]. |
| Constant folding | The JIT computes a constant result once, outside the loop [42]. | Read inputs "from non-final instance fields of @State objects" [42]. |
| Loops in the benchmark | Unrolling and hoisting make the "perceived" cost fall below what the hardware can do [43]. | Let JMH do the loop [43]. |
| IDE runs | "Running benchmarks from the IDE is generally not recommended" [40]. | Run the JAR from the command line [40]. |
| Unrealistic input | A constant toy string trains the JIT on one branch. | Feed the same payload mix as the k6 test, from a `@State`. |

Useful profilers: `-prof gc` reports `gc.alloc.rate.norm` in bytes per operation [44]. `-prof async:libPath=<path>` runs async-profiler inside JMH (event `cpu` by default) [45]. `-lprof` lists all profilers [44].

**How to add it without changing the app build.** JMH needs annotation processing. "Simply adding the `jmh-core` jar file to your build is not enough" [40]. The recommended way is "a standalone project" made from the `jmh-java-benchmark-archetype` [40].

| Option | How | Problem |
|---|---|---|
| A. Standalone Maven project that depends on `core-engine` | Archetype project, with `core-engine` as a dependency | By default, "the main artifact will be replaced by the repackaged archive" [90]. Classes then sit in `BOOT-INF/classes`, "so that the dependent module cannot load a repackaged jar's classes" [90]. A fix needs a classifier [90], which changes the app JAR that `core-engine/Containerfile` copies. |
| B. Maven profile `jmh` in `core-engine/pom.xml` (recommended) | Add `src/jmh/java` as a source folder, add `jmh-core` and `jmh-generator-annprocess` 1.37 [46], and run with `mvn -Pjmh ...` | It edits `pom.xml`, so it needs an approved plan. You activate a profile with `-P` [91], and `activeByDefault` is `false` by default [91]. The guide does not say directly that an inactive profile has no effect. Verify that `mvn package` without `-Pjmh` builds the same JAR: compare the JAR file lists before and after. |

From JDK 23, javac no longer runs annotation processors only because they are on the class path [47][48]. Local JDKs here can be newer than 21 (`AGENTS.md` §8). So list `jmh-generator-annprocess` explicitly in `annotationProcessorPaths` in the profile. JMH 1.37 is the current release on Maven Central (last updated 2023-08-03) [46].

## 4. JVM logs and metrics

### 4.1 Unified GC logging

Unified logging came in JDK 9 (JEP 158 for the framework, JEP 271 for GC) [49][50]. `-Xlog:gc*` replaces `PrintGCDetails`, and `-Xlog:safepoint` replaces `PrintGCApplicationStoppedTime` [4]. File output takes `filecount` and `filesize`. The default rotation is 5 files of 20 MB [4]. The G1 guide suggests `-Xlog:gc*=debug` as "a good start" [52].

What to look for:

| Signal | Where | Meaning for ULPF |
|---|---|---|
| Pause time and pause frequency | `gc` lines, JFR `gc-pauses` view [9] | Sum the pauses per second. That share of time is lost for all request threads. |
| `Pause Full (G1 Compaction Pause)` | `gc*` [52] | Old generation full. Often follows an evacuation failure [52]. An unbounded queue (to-do item 12) can cause it. |
| `(Evacuation Failure)` | `gc*` [52] | Too little free space for copying |
| Humongous regions (`Humongous regions: X->Y`) | `gc+heap=info` [52] | Large objects, for example big batch lists or large payloads. These can cause a Full GC [52]. |
| Heap after GC rising over the run | `gc` lines | Backlog in the in-memory queues |
| Long time to safepoint | `-Xlog:safepoint`, JFR `jdk.SafepointBegin` [7] | All threads stop while one thread is slow to reach a safepoint. |

The default collector is G1 when the JVM sees 2 or more CPUs and at least 1,792 MB [51]. The default maximum heap is 1/4 of physical memory [51]. Record the heap size for every run, because a change in container limits changes it.

### 4.2 JIT logging

JIT logging is rarely needed for this work. Use it in two cases: interpreted frames still show in a CPU profile after warm-up, or JMH results jump between forks. `-XX:+PrintCompilation` exists in JDK 21 [4]. The JFR views `compiler-statistics` and `deoptimizations-by-reason` give the same data with less noise [9]. Discard the first 30-60 s of each step so that the JIT is warm.

### 4.3 Micrometer and Spring Boot Actuator

Current state: `core-engine/pom.xml` has no `spring-boot-starter-actuator` (checked). Spring Boot 4.1.0 manages Micrometer 1.17.0 and Tomcat 11.0.22 [89].

**Without Prometheus.** Micrometer has an in-memory "simple" registry that "is automatically used as a fallback if no other registry is configured", and it "lets you see what metrics are collected in the metrics endpoint" [53]. It turns itself off when another backend is present [53]. So the team can read metrics with `curl`, with no dependency on the Prometheus and Grafana work (to-do item 7). I did not verify in the source that `/actuator/metrics` still returns all meters when a Prometheus registry is also present.

- Only `health` is exposed over HTTP by default. Add `metrics` with `management.endpoints.web.exposure.include=health,metrics` [54].
- `/actuator/metrics/<name>` shows one meter, and `?tag=KEY:VALUE` filters by tag. The values are "the _sum_ of the statistics of all meters that match" [53].
- The simple registry runs in `cumulative` mode with a `1m` step by default [55]. A timer shows `COUNT`, `TOTAL_TIME` and `MAX`. Poll once per second and use the differences to get rates.
- Client-side percentiles: `management.metrics.distribution.percentiles.<meter>=0.5,0.99` [53]. Micrometer publishes each percentile as a gauge named `<meter>.percentile` with a `phi` tag [57]. Example: `/actuator/metrics/ulpf.flush.duration.percentile?tag=phi:0.99`. Percentiles decay over a ring buffer. The defaults are a 2-minute expiry and a buffer length of 3 [58]. You cannot aggregate these percentiles across tags [56].
- `http.server.requests` times each Spring MVC request [53].
- Tomcat thread metrics need `server.tomcat.mbeanregistry.enabled=true` [53]. The default is `false` [55]. The meters are `tomcat.threads.busy`, `tomcat.threads.current` and `tomcat.threads.config.max`, plus `tomcat.connections.current` [59].
- Executor metrics cover `ThreadPoolTaskExecutor` and `ThreadPoolTaskScheduler` beans [53]. The meters include `executor.active`, `executor.queued`, `executor.queue.remaining` and `executor.pool.size` [60].

Custom meters that the profiling work needs (for a later plan). Keep each one cheap:

| Meter | Type | Note |
|---|---|---|
| `ulpf.ingest.accepted` | Counter | Events that got `202` |
| `ulpf.queue.raw.depth`, `ulpf.queue.canonical.depth` | Gauge | Back it with an `AtomicLong` that `enqueue` and `flush` update. Do not use `ConcurrentLinkedQueue.size()`, because it walks the queue [86]. |
| `ulpf.flush.duration` (tag `table=raw|canonical`) | Timer with percentiles | Time inside the lock |
| `ulpf.flush.rows` | Distribution summary | Rows per batch |
| `ulpf.flush.errors` | Counter | Failed inserts that were re-queued |

Security: `/actuator/**` is `permitAll()` today (to-do item 7). Exposing `metrics` is acceptable on a closed test host. Item 7 must restrict it before any shared deployment.

## 5. ClickHouse side

### 5.1 System tables

All of these logs are on in the default `config.xml` of ClickHouse 26.3.9.8: `query_log`, `part_log`, `asynchronous_insert_log`, `metric_log`, `trace_log` and others [72]. They flush every 7.5 s by default [72]. Run `SYSTEM FLUSH LOGS` before you query [63].

| Table | What it tells you about insert cost |
|---|---|
| `system.query_log` | One row per query with `query_kind`, `query_duration_ms`, `written_rows`, `written_bytes`, `memory_usage`, `ProfileEvents` and `exception` [63]. For async inserts, the written rows are "recorded on the `query_kind` = `AsyncInsertFlush` entry" [63]. With `wait_for_async_insert=1`, the `Insert` row's duration includes the wait for the flush. |
| `system.asynchronous_insert_log` | One row per buffered insert, with `status` (`Ok`, `ParsingError`, `FlushError`), `flush_time`, `flush_query_id`, `rows`, `bytes` and `timeout_milliseconds` (the adaptive timeout) [65] |
| `system.part_log` | `NewPart` rows give parts created, with `rows`, `size_in_bytes` and `duration_ms`. `MergeParts` rows give merges. `query_id` links a part to its insert [64]. |
| `system.parts` | Current parts. `active`, `rows`, `partition`. `level` 0 means that an insert created the part, not a merge [66]. |
| `system.events` | Cumulative counters [70]: `InsertQuery`, `AsyncInsertQuery`, `AsyncInsertRows`, `FailedAsyncInsertQuery`, `InsertedRows`, `InsertQueryTimeMicroseconds`, `DelayedInserts`, `DelayedInsertsMilliseconds`, `RejectedInserts`, `Merge`, `MergedRows`. Read before and after a run and subtract. |
| `system.metrics` | Current gauges [71]: `PendingAsyncInsert`, `DelayedInserts`, `PartsActive`, `HTTPConnection`, `Merge` |
| `system.metric_log` | Per-second history of events and metrics (enabled [72]). I did not verify its column names in 26.3. Check with `DESCRIBE system.metric_log`. |

Queries for each run. Replace the time bounds with the steady-state window:

```sql
SYSTEM FLUSH LOGS;

-- Client inserts per second, rows per flush, and client-visible insert time.
SELECT toStartOfSecond(event_time) AS s,
       countIf(query_kind = 'Insert')                       AS client_inserts,
       quantileIf(0.99)(query_duration_ms, query_kind = 'Insert') AS insert_p99_ms,
       countIf(query_kind = 'AsyncInsertFlush')             AS flushes,
       sumIf(written_rows, query_kind = 'AsyncInsertFlush') AS rows_flushed
FROM system.query_log
WHERE type = 'QueryFinish'
  AND event_time BETWEEN '2026-11-13 10:00:30' AND '2026-11-13 10:02:00'
  AND has(databases, 'ulpf_raw')
GROUP BY s ORDER BY s;

-- Parts created and merges per second.
SELECT toStartOfSecond(event_time) AS s,
       countIf(event_type = 'NewPart')       AS new_parts,
       sumIf(rows, event_type = 'NewPart')   AS rows_in_new_parts,
       countIf(event_type = 'MergeParts')    AS merges
FROM system.part_log
WHERE database IN ('ulpf_raw', 'ulpf_events')
  AND event_time BETWEEN '...' AND '...'
GROUP BY s ORDER BY s;

-- Async insert outcome and adaptive timeout.
SELECT table, status, count() AS n, avg(rows) AS avg_rows,
       quantile(0.5)(timeout_milliseconds) AS timeout_p50_ms
FROM system.asynchronous_insert_log
WHERE event_time BETWEEN '...' AND '...'
GROUP BY table, status;

-- Highest active part count per partition (poll every 10 s during the run).
SELECT database, table, partition, count() AS active_parts
FROM system.parts WHERE active
GROUP BY database, table, partition ORDER BY active_parts DESC LIMIT 5;
```

I did not verify the exact content of the `databases` column for `AsyncInsertFlush` rows. Check one row first, and filter on `query` text if needed.

### 5.2 Rows per insert, parts per second, "too many parts"

- "Each insert query immediately creates a storage part on disk" [61]. Many small inserts make many parts, which "can cause 'too many parts' errors" [61].
- The MergeTree limits in 26.3 are per partition [67]. Above `parts_to_delay_insert` = 1000 active parts, ClickHouse slows each insert (up to `max_delay_to_insert` = 1 s). Above `parts_to_throw_insert` = 3000, it rejects the insert with "Too many parts". `max_parts_in_total` = 100000 applies to the whole table [67].
- `ulpf_raw.raw_events` is partitioned by `toYYYYMM(received_at)` (`infra/clickhouse-init/01_raw_events.sql`). So all of a month's inserts go to one partition, and merges must keep up.
- Watch `DelayedInserts` and `RejectedInserts` in `system.events` [70]. Any non-zero value during a run means that ClickHouse is the limit at that rate.

### 5.3 Batch size and async inserts

ClickHouse guidance [61]:

- "We recommend inserting data in batches of at least 1,000 rows, and ideally between 10,000-100,000 rows."
- Keep "the number of insert queries around one insert query per second".
- Async inserts "shift batching to the server" when the client cannot batch [61].
- The most efficient input format is Native. RowBinary is a good row format. JSONEachRow is "expensive to parse" [61]. LZ4 compression is the default advice [61]. ULPF sends `compress=0` in its JDBC URL (`compose.yaml`).

Async inserts [62][68]:

- The server buffers data and flushes it when the first of three limits is reached: data size, time, or query count [62]. In 26.3, the defaults are `async_insert_max_data_size` = 10 MiB, `async_insert_max_query_number` = 450, and an adaptive timeout from 50 ms to `async_insert_busy_timeout_max_ms` = 200 ms [68]. ULPF sets the size limit to 1 MiB.
- `wait_for_async_insert=1` (the default): "ClickHouse only acknowledges the insert after the data is successfully flushed to disk", and errors go back to the client [62].
- `wait_for_async_insert=0`: the server acknowledges when the data is in the buffer. "Use this mode only if your workload can tolerate data loss" [62]. ClickHouse calls this mode "very risky" [62].
- The ClickHouse recommendation is `async_insert=1,wait_for_async_insert=1` when you use async inserts [61][62].

Trade-off for "no event loss":

| Option | When is the data safe? | Effect on ULPF |
|---|---|---|
| Sync insert, large client batches | When the insert returns, the part is written. `fsync_after_insert` is `false` by default [67], so an OS crash can still lose the newest part (my inference). | The best fit for the ClickHouse guidance [61]. ULPF must build the batch and handle retries. |
| Async insert, `wait=1` | When the server flush is done [62] | Each client insert also waits for the server buffer (up to the 200 ms maximum here [68]). If the wait happens inside a lock on a request thread, it can block other requests. |
| Async insert, `wait=0` | Not guaranteed [62] | Does not meet "no event loss". |

ULPF itself also returns `202` before the event reaches ClickHouse. The event waits in an unbounded heap queue. A JVM crash loses that queue whatever the ClickHouse mode is (to-do items 12 and 13 already note the missing limit). Every run must check the count: the number of `202` responses equals the number of rows in `ulpf_raw.raw_events` after the queues drain.

## 6. Container and OS level

| Tool | Use | Notes |
|---|---|---|
| `podman stats` | CPU %, memory, block I/O and PIDs per container [75] | The default interval is 5 s. Use `--interval 1` or `--no-stream` [75]. "Rootless environments are not able to report statistics about their networking usage" [75]. |
| cgroup `cpu.stat` | `usage_usec`, `user_usec`, `system_usec` always. `nr_periods`, `nr_throttled`, `throttled_usec` when the controller is on [82]. | Throttling happens only with a quota (`cpu.max`, set by `--cpus`) [82]. Today no container has a quota, so expect 0. Find the cgroup path with `cat /proc/<pid>/cgroup` [82]. `cpu.pressure` gives CPU pressure stall data [82]. The JFR view `container-cpu-throttling` reads the same data [9]. |
| `pidstat -u -w 1` | `%CPU`, `%wait`, `cswch/s`, `nvcswch/s` per process. `-t` adds threads [84]. | `%wait` is "CPU spent by the task while waiting to run" [84]: high values mean CPU contention. `cswch/s` counts blocks on a resource, and `nvcswch/s` counts forced switches at the end of a time slice [84]. |
| `perf` | Hardware counters, scheduler events, native stacks | Optional. This host has `perf_event_paranoid=4` (observed), so `perf` needs a host sysctl change or root ([Question 1](#12-async-profiler)). For Java frames, `jcmd <pid> Compiler.perfmap` writes a map file (Linux only) [5]. `pidstat` and async-profiler cover what ULPF needs. |

### 6.1 Can rootless networking limit throughput?

Yes, it can. Podman says that the move from slirp4netns to pasta in 5.0 was "for improved performance" [77]. For rootless bridge networks, port forwarding "uses rootlessport by default", which is a userspace proxy [74][76]. An experimental `rootless_port_forwarder="pasta"` option exists [76][77]. I found no Podman number for the throughput of either path, so measure it.

This host: Podman 3.4.4, slirp4netns 1.0.1, CNI network backend, no pasta (observed). The stack uses a user-defined bridge network (`ulpf-net`) and published ports. Two paths matter:

1. Generator to core engine: host `127.0.0.1:8080` to the container, through the port forwarder.
2. Core engine to ClickHouse: `host.containers.internal:8123`. This leaves the container network and comes back through ClickHouse's published port.

How to measure:

1. Pick an endpoint with almost no work. Check what `/v1/health` does first. If it touches a database, use another trivial endpoint.
2. Run the same k6 step ramp from the host to `127.0.0.1:8080` (path A).
3. Run the same k6 binary inside a container on the same network, to `http://core-engine:8080` (path B). A static binary runs in any small image. Mount it read-only.
4. During path A, record the forwarder processes: `pidstat -u -w -p $(pgrep -d, -f 'rootlessport|slirp4netns|pasta') 1`.
5. Compare the knee of A and B. If A has a lower knee, or one forwarder process sits near 100% of one CPU, the network path is a limit.
6. For path 2, compare the ULPF flush timer (client side) with `query_duration_ms` in `system.query_log` (server side) at the same rate. The gap is network plus driver time. Then repeat with `CLICKHOUSE_URL` set to `clickhouse:8123` on `ulpf-net` in a profiling override.

Under Docker (rootful), the bridge network does not use these user-mode proxies. A Docker result is not proof for Podman rootless.

## 7. Recommended method for ULPF

### 7.0 Setup (with internet, once)

Download and record the SHA-256 of each file:

1. Download `async-profiler-4.6-linux-x64.tar.gz` [11] and extract it to a tools folder.
2. Download the k6 Linux amd64 tarball for the pinned version [34].
3. Install a Temurin 21 JDK on the host for `jfr`, and for `jfrconv`, which is a shell script that runs Java [13]. Or use `jfr-converter.jar` [11].
4. Optional: download JMC 9.1.2 [10].
5. If JMH becomes necessary, run `mvn -Pjmh dependency:go-offline` once so that JMH 1.37 is in the local Maven repository.
6. As root, add the cgroup delegation drop-in [78]. Log out and in. Check `cgroup.controllers`.

After this, everything runs offline.

### 7.1 Baseline

1. Write down the host state: CPU model, the P-core and E-core map, kernel, Podman, podman-compose, image IDs, the git SHA and `free -g`.
2. Put the laptop on AC power. Close other applications. Record `scaling_governor` (this host: `powersave`, `intel_pstate` active) and `energy_performance_preference` (this host: `balance_performance`) (observed). Use the same values for every run.
3. Record the thermal counters in `/sys/devices/system/cpu/cpu*/thermal_throttle/` before and after each run. These files exist on this host (observed). I did not verify their kernel documentation.
4. Start the stack with the profiling override: CPU pinning, `JDK_JAVA_OPTIONS` with JFR and GC logging (section 1.1), and the async-profiler folder mounted read-only.
5. Truncate `ulpf_raw.raw_events` and the `ulpf_events` tables. The part count must start from the same state.
6. Run the item 13 plain-ClickHouse baseline with the same k6 script, the same body mix and the same CPU layout.
7. Run the coarse ULPF ramp (section 2.3).

### 7.2 Ramp to the knee

A step passes at rate R when all of these are true for its steady window (the step minus its first 30 s):

1. `dropped_iterations` = 0 and `http_req_failed` = 0 for the step [30].
2. The P1 of the per-second accepted count is at least 0.98 × R. Count from ClickHouse, and fill empty seconds with zero. A `GROUP BY` drops a second that has no rows, which makes P1 look better than it is. `WITH FILL` adds the missing rows [73].
3. The queue depth and the heap after GC do not trend up during the step.
4. The k6 P99 latency in the last minute is not much higher than in the first minute.
5. After the run, the rows in `raw_events` equal the `202` count from k6.
6. ClickHouse `DelayedInserts` and `RejectedInserts` stay at 0 [70].

The knee is the highest passing step. Then run the fine ramp between the last pass and the first fail.

Per-second accepted count (`received_at` is set at request time in `EventIngestionService`):

```sql
SELECT quantileExact(0.01)(c) AS p1_window, min(c) AS min_window,
       median(c) AS median_window, count() AS windows
FROM (
  SELECT toDateTime(received_at) AS s, count() AS c
  FROM ulpf_raw.raw_events
  WHERE received_at >= '2026-11-13 10:00:30' AND received_at < '2026-11-13 10:02:05'
  GROUP BY s
  ORDER BY s WITH FILL FROM toDateTime('2026-11-13 10:00:30')
                       TO   toDateTime('2026-11-13 10:02:05') STEP 1
);
```

The North Star check uses the same query on the 11,600/s soak: `p1_window` must be at least 11,600. A 15-minute soak gives 900 windows, so at most 9 windows can fall below. The k6 CSV gives a client-side cross-check: count `http_reqs` rows per Unix second [32]. I did not verify whether k6 stamps a sample at the start or the end of a request, so allow one second of offset.

### 7.3 Profiler mode at the knee

Run at the knee rate K, and again at about 1.1 × K, where the bottleneck dominates. Pick the mode from `pidstat` first:

| Observation at the knee | Bottleneck type | Profile |
|---|---|---|
| core-engine `%CPU` is near the capacity of its CPU set (for example above 85% of 8 CPUs) | CPU | `asprof -e cpu -d 60 -o flamegraph -f /profiles/cpu-%t.html 1`, plus the JFR allocation data. If one pure function dominates, use JMH. |
| core-engine `%CPU` is well below capacity, the rate is flat and latency rises | Waiting (locks or I/O) | `asprof -e wall -i 10ms -t --lock 1ms --alloc 2m -d 60 -f /profiles/wall-%t.jfr 1` |
| High `%wait` for core-engine | CPU contention with other processes | Fix the CPU layout first. |
| ClickHouse CPU is high, or `DelayedInserts` > 0 | ClickHouse | Section 5 queries |
| A forwarder process is near 100% of one CPU | Network path | Section 6.1 |
| GC pauses take more than a few percent of wall time | Allocation or heap | JFR `allocation-by-site`, `--alloc` flame graph |

Run `asprof` with `podman exec ulpf-core-engine /opt/async-profiler/bin/asprof ...`. With the exec-form `ENTRYPOINT`, `java` is usually PID 1 in the container. Check it with `ps` in the container. To profile only the request threads, add `--ithread 'http-nio-*'`. This filter applies only to wall-clock mode [19]. The default Tomcat thread name pattern is not verified, so check the names in the profile first.

Convert on the host [20]:

```bash
jfrconv --wall -t            wall.jfr wall-by-thread.html
jfrconv --wall --state sleeping wall.jfr wall-waiting.html
jfrconv --wall --state runnable wall.jfr wall-running.html
jfrconv --lock --total       wall.jfr lock-time.html
jfrconv --alloc --total      wall.jfr alloc-bytes.html
```

### 7.4 Read a wall-clock flame graph for lock and I/O waits

1. Open `wall-by-thread.html`. Each tower is one thread, because `-t` splits stacks by thread [20].
2. Ignore idle threads. A Tomcat worker that waits for work, or the scheduler between runs, parks in its pool code. If most request threads are idle at the knee, ULPF is not the limit. Look at the generator or the network path.
3. Width is time. In wall mode, each thread is sampled at a fixed period whatever its state [14], so the share of a frame's width is the share of that thread's time.
4. In each busy request-thread tower, find the deepest `com.ulpf` frame. Then look at the frames above it:
   - Frames of the JVM monitor code above `ClickHouseIngestionRepository.flush` mean that the thread waits to enter the `synchronized` method. I did not verify the exact native frame names for JDK 21. They are C++ frames, which wall mode shows by default [19].
   - Frames of the ClickHouse driver and the socket layer above `flush` → `batchUpdate` mean that the thread holds the lock and waits for ClickHouse. In JDK 21, timed socket reads poll the socket [87]. Expect `NioSocketImpl` and poll frames, or `park` if the HTTP client reads on another thread.
   - SHA-256 or SQLite frames under `processRawBatch` mean integrity work inside the lock.
5. The convoy signature is many threads in the monitor wait plus one thread in socket I/O or SQLite, all under `flush`. The fix is to move the slow work out of the lock and off the request thread. A faster network alone helps only a little.
6. Confirm with `lock-time.html`. The top frame is the lock class, and `--total` sums nanoseconds of waiting [14][20]. Then confirm with the JFR file from the same run: `jfr view contention-by-site` (based on `jdk.JavaMonitorEnter` [9]) and `jfr view socket-reads-by-host`.
7. A flame graph merges time [21]. To see a periodic stall (for example the 1-second scheduled flush), make graphs for short slices with `--from` and `--to` [20].

### 7.5 What to record for each run

Use one run ID, for example `2026-11-13T1030-base-r1`. Keep all files under `results/<run-id>/`.

| Group | Items |
|---|---|
| Identity | Git SHA, image IDs, JVM version and flags (`JDK_JAVA_OPTIONS`), heap size, ClickHouse version and the `users.d` settings, k6 version, SHA-256 of the script and of the payload file |
| Host | CPU layout (cpusets), governor, EPP, AC power, thermal counters before and after, other load (`uptime`), Podman version, network path (A or B) |
| Offered load | Steps, hold time, VUs, `maxVUs` |
| Client results (k6) | Per step: `dropped_iterations`, error rate, P50, P99 and max latency |
| Server results | Per step: P1, minimum and median of the per-second accepted count. Rows persisted against the `202` count. Peak queue depth. |
| JVM | GC pause total per second, Full GC count, safepoint time, top 5 `contention-by-site` entries, the JFR file, the flame graphs |
| ClickHouse | Client inserts/s, rows per flush, insert P99, parts/s, peak active parts, `DelayedInserts`, `RejectedInserts`, async insert status counts |
| OS | `pidstat` for core-engine, ClickHouse, k6 and the forwarders (`%CPU`, `%wait`, `cswch/s`) |
| Verdict | Knee rate, pass or fail for each step, one line on the bottleneck |

### 7.6 Repeat runs to control noise

1. Measure the noise first. Run the unchanged baseline ramp 5 times. Use the spread of the knee as the noise band.
2. Restart the stack and truncate the tables before each run. The part count, the heap and the JIT state must start the same.
3. Run each configuration at least 3 times. Alternate the order (A, B, A, B), so that heat and background drift affect both sides.
4. Report the median and the minimum-maximum range. Accept an improvement only if the ranges do not overlap, or if the difference is larger than the noise band from step 1.
5. Change one thing at a time (to-do item 13). Record the before and after numbers in the plan for that change.
6. Discard runs where thermal throttle counters rose, the generator went above 80% CPU [31], or `dropped_iterations` was not 0 below the knee.

## Open points

- `asprof` on Alpine (musl): the library works [13]. The launcher is not verified. If it fails, use agent mode [19].
- How `clickhouse-jdbc` 0.4.6 sends a batch, and which HTTP client it uses: not verified. `system.query_log` shows the first. The wall-clock stack shows the second.
- Whether `--cap-add PERFMON` helps inside rootless Podman: not verified.
- The meaning of `perf_event_paranoid=4` on Ubuntu: not verified from a primary source.
- Whether `/actuator/metrics` shows all meters when the Prometheus registry is also present: not verified in source.

## Sources

1. JEP 328: Flight Recorder. https://openjdk.org/jeps/328
2. JEP 509: JFR CPU-Time Profiling (Experimental). https://openjdk.org/jeps/509
3. JEP 518: JFR Cooperative Sampling. https://openjdk.org/jeps/518
4. JDK 21 `java` command manual. https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html
5. JDK 21 `jcmd` manual. https://docs.oracle.com/en/java/javase/21/docs/specs/man/jcmd.html
6. JDK 21 `jfr` manual. https://docs.oracle.com/en/java/javase/21/docs/specs/man/jfr.html
7. JDK 21 `default.jfc`. https://github.com/openjdk/jdk21u/blob/master/src/jdk.jfr/share/conf/jfr/default.jfc
8. JDK 21 `profile.jfc`. https://github.com/openjdk/jdk21u/blob/master/src/jdk.jfr/share/conf/jfr/profile.jfc
9. JDK 21 JFR view definitions (`view.ini`). https://github.com/openjdk/jdk21u/blob/master/src/jdk.jfr/share/classes/jdk/jfr/internal/query/view.ini
10. JDK Mission Control 9.1.2 GA. https://jdk.java.net/jmc/9/
11. async-profiler README. https://github.com/async-profiler/async-profiler
12. async-profiler 4.6 release. https://github.com/async-profiler/async-profiler/releases/tag/v4.6
13. async-profiler CHANGELOG. https://github.com/async-profiler/async-profiler/blob/master/CHANGELOG.md
14. async-profiler, Profiling Modes. https://github.com/async-profiler/async-profiler/blob/master/docs/ProfilingModes.md
15. async-profiler, CPU Sampling Engines. https://github.com/async-profiler/async-profiler/blob/master/docs/CpuSamplingEngines.md
16. async-profiler, Profiling In Container. https://github.com/async-profiler/async-profiler/blob/master/docs/ProfilingInContainer.md
17. async-profiler, Troubleshooting. https://github.com/async-profiler/async-profiler/blob/master/docs/Troubleshooting.md
18. async-profiler, Stack Walking Modes. https://github.com/async-profiler/async-profiler/blob/master/docs/StackWalkingModes.md
19. async-profiler, Profiler Options. https://github.com/async-profiler/async-profiler/blob/master/docs/ProfilerOptions.md
20. async-profiler, Converter Usage. https://github.com/async-profiler/async-profiler/blob/master/docs/ConverterUsage.md
21. async-profiler, FlameGraph Interpretation. https://github.com/async-profiler/async-profiler/blob/master/docs/FlamegraphInterpretation.md
22. async-profiler, Getting Started. https://github.com/async-profiler/async-profiler/blob/master/docs/GettingStarted.md
23. async-profiler 4.6 source, `arguments.h`. https://github.com/async-profiler/async-profiler/blob/v4.6/src/arguments.h
24. async-profiler 4.6 source, `wallClock.cpp`. https://github.com/async-profiler/async-profiler/blob/v4.6/src/wallClock.cpp
25. wrk2 README (Gil Tene). https://github.com/giltene/wrk2
26. HdrHistogram README. https://github.com/HdrHistogram/HdrHistogram
27. k6, Open and closed models. https://grafana.com/docs/k6/latest/using-k6/scenarios/concepts/open-vs-closed/
28. k6, Constant arrival rate. https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/constant-arrival-rate/
29. k6, Ramping arrival rate. https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/ramping-arrival-rate/
30. k6, Built-in metrics. https://grafana.com/docs/k6/latest/using-k6/metrics/reference/
31. k6, Running large tests. https://grafana.com/docs/k6/latest/testing-guides/running-large-tests/
32. k6, CSV output. https://grafana.com/docs/k6/latest/results-output/real-time/csv/
33. k6, Usage collection. https://grafana.com/docs/k6/latest/set-up/usage-collection/
34. k6 releases (v2.4.0, v2.3.0, v2.0.0 notes). https://github.com/grafana/k6/releases
35. Gatling, Injection. https://docs.gatling.io/concepts/injection/
36. Gatling releases. https://github.com/gatling/gatling/releases
37. vegeta README. https://github.com/tsenart/vegeta
38. oha README. https://github.com/hatoo/oha
39. hey README. https://github.com/rakyll/hey
40. JMH README. https://github.com/openjdk/jmh
41. JMH sample 08, Dead Code. https://github.com/openjdk/jmh/blob/master/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_08_DeadCode.java
42. JMH sample 10, Constant Fold. https://github.com/openjdk/jmh/blob/master/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_10_ConstantFold.java
43. JMH sample 11, Loops. https://github.com/openjdk/jmh/blob/master/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_11_Loops.java
44. JMH sample 35, Profilers. https://github.com/openjdk/jmh/blob/master/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_35_Profilers.java
45. JMH async-profiler integration source. https://github.com/openjdk/jmh/blob/master/jmh-core/src/main/java/org/openjdk/jmh/profile/AsyncProfiler.java
46. Maven Central metadata for `jmh-core`. https://repo1.maven.org/maven2/org/openjdk/jmh/jmh-core/maven-metadata.xml
47. JDK-8321314: annotation processing disabled by default. https://bugs.openjdk.org/browse/JDK-8321314
48. JDK 23 release notes. https://www.oracle.com/java/technologies/javase/23-relnote-issues.html
49. JEP 158: Unified JVM Logging. https://openjdk.org/jeps/158
50. JEP 271: Unified GC Logging. https://openjdk.org/jeps/271
51. JDK 21 GC Tuning Guide, Ergonomics. https://docs.oracle.com/en/java/javase/21/gctuning/ergonomics1.html
52. JDK 21 GC Tuning Guide, G1 tuning. https://docs.oracle.com/en/java/javase/21/gctuning/garbage-first-garbage-collector-tuning.html
53. Spring Boot 4.1 reference, Metrics. https://docs.spring.io/spring-boot/reference/actuator/metrics.html
54. Spring Boot 4.1 reference, Endpoints. https://docs.spring.io/spring-boot/reference/actuator/endpoints.html
55. Spring Boot application properties. https://docs.spring.io/spring-boot/appendix/application-properties/index.html
56. Micrometer, Histograms and percentiles. https://docs.micrometer.io/micrometer/reference/concepts/histogram-quantiles.html
57. Micrometer source, `HistogramGauges.java`. https://github.com/micrometer-metrics/micrometer/blob/main/micrometer-core/src/main/java/io/micrometer/core/instrument/distribution/HistogramGauges.java
58. Micrometer source, `DistributionStatisticConfig.java`. https://github.com/micrometer-metrics/micrometer/blob/main/micrometer-core/src/main/java/io/micrometer/core/instrument/distribution/DistributionStatisticConfig.java
59. Micrometer source, `TomcatMetrics.java`. https://github.com/micrometer-metrics/micrometer/blob/main/micrometer-core/src/main/java/io/micrometer/core/instrument/binder/tomcat/TomcatMetrics.java
60. Micrometer source, `ExecutorServiceMetrics.java`. https://github.com/micrometer-metrics/micrometer/blob/main/micrometer-core/src/main/java/io/micrometer/core/instrument/binder/jvm/ExecutorServiceMetrics.java
61. ClickHouse, Selecting an insert strategy. https://clickhouse.com/docs/best-practices/selecting-an-insert-strategy
62. ClickHouse, Asynchronous inserts. https://clickhouse.com/docs/optimize/asynchronous-inserts
63. ClickHouse, `system.query_log`. https://clickhouse.com/docs/operations/system-tables/query_log
64. ClickHouse, `system.part_log`. https://clickhouse.com/docs/operations/system-tables/part_log
65. ClickHouse, `system.asynchronous_insert_log`. https://clickhouse.com/docs/operations/system-tables/asynchronous_insert_log
66. ClickHouse, `system.parts`. https://clickhouse.com/docs/operations/system-tables/parts
67. ClickHouse 26.3.9.8 source, `MergeTreeSettings.cpp`. https://github.com/ClickHouse/ClickHouse/blob/v26.3.9.8-lts/src/Storages/MergeTree/MergeTreeSettings.cpp
68. ClickHouse 26.3.9.8 source, `Settings.cpp`. https://github.com/ClickHouse/ClickHouse/blob/v26.3.9.8-lts/src/Core/Settings.cpp
69. ClickHouse 26.3.9.8 source, `SettingsChangesHistory.cpp`. https://github.com/ClickHouse/ClickHouse/blob/v26.3.9.8-lts/src/Core/SettingsChangesHistory.cpp
70. ClickHouse 26.3.9.8 source, `ProfileEvents.cpp`. https://github.com/ClickHouse/ClickHouse/blob/v26.3.9.8-lts/src/Common/ProfileEvents.cpp
71. ClickHouse 26.3.9.8 source, `CurrentMetrics.cpp`. https://github.com/ClickHouse/ClickHouse/blob/v26.3.9.8-lts/src/Common/CurrentMetrics.cpp
72. ClickHouse 26.3.9.8 default server `config.xml`. https://github.com/ClickHouse/ClickHouse/blob/v26.3.9.8-lts/programs/server/config.xml
73. ClickHouse, `ORDER BY ... WITH FILL`. https://clickhouse.com/docs/sql-reference/statements/select/order-by
74. Podman, `podman-run`. https://docs.podman.io/en/latest/markdown/podman-run.1.html
75. Podman, `podman-stats`. https://docs.podman.io/en/latest/markdown/podman-stats.1.html
76. Podman, Basic networking tutorial. https://github.com/containers/podman/blob/main/docs/tutorials/basic_networking.md
77. Podman release notes. https://github.com/containers/podman/blob/main/RELEASE_NOTES.md
78. Podman troubleshooting (rootless CPU and cpuset delegation). https://github.com/containers/podman/blob/main/troubleshooting.md
79. Podman releases (v6.2.0). https://github.com/containers/podman/releases
80. containers/common default seccomp profile. https://github.com/containers/common/blob/main/pkg/seccomp/seccomp.json
81. podman-compose source. https://github.com/containers/podman-compose/blob/main/podman_compose.py
82. Linux kernel, Control Group v2. https://docs.kernel.org/admin-guide/cgroup-v2.html
83. Linux kernel, sysctl `kernel.*`. https://docs.kernel.org/admin-guide/sysctl/kernel.html
84. sysstat, `pidstat(1)`. https://github.com/sysstat/sysstat/blob/master/man/pidstat.1
85. util-linux, `taskset(1)`. https://man7.org/linux/man-pages/man1/taskset.1.html
86. JDK 21 API, `ConcurrentLinkedQueue`. https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ConcurrentLinkedQueue.html
87. JEP 353: Reimplement the Legacy Socket API. https://openjdk.org/jeps/353
88. clickhouse-java releases (0.11.1). https://github.com/ClickHouse/clickhouse-java/releases
89. Spring Boot 4.1.0 dependency versions. https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.0/spring-boot-dependencies-4.1.0.pom
90. Spring Boot Maven plugin 4.1.2, Packaging. https://docs.spring.io/spring-boot/maven-plugin/packaging.html
91. Apache Maven, Introduction to build profiles. https://maven.apache.org/guides/introduction/introduction-to-profiles.html
