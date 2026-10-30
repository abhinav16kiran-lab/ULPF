# Plan: Production Observability — Prometheus + Grafana Stack

- **Date:** 2026-10-28
- **Status:** Draft (rev 2 — incorporates codebase review findings)
- **Approved by / on:** (awaiting approval)
- **File name:** `docs/plans/2026-10-28-prometheus-grafana-observability.md`

## 0. Review changes (rev 2)

This revision applies the findings of the codebase review against rev 1. Summary of what changed:

| # | Change | Why |
|---|---|---|
| 1 | ClickHouse `grafana` user, grants, profile and network restriction are all defined **in XML**; `02_grafana_grants.sql` is dropped; profile adds `readonly=1` | Users defined in XML are read-only access entities, so a SQL `GRANT` on them fails and can abort first-boot init. XML reloads on every start, which also removes the existing-volume problem |
| 2 | nginx `proxy_pass http://grafana:3000;` (**no trailing slash**) + WebSocket upgrade headers | `serve_from_sub_path=true` requires Grafana to receive the `/grafana/` prefix; Grafana Live needs WebSockets |
| 3 | Grafana session cookie TTL raised (default 1h) with sliding refresh via `auth_request_set` + `add_header` | `auth_request` runs for every sub-resource; a 120s cookie would log admins out every 2 minutes |
| 4 | `purpose=grafana-session` claim enforced: required by `validate-grafana`, **rejected** by `JwtFilter` for normal API calls | Prevents a privilege-confusion hole between the two token types |
| 5 | `CredentialRecord` keeps the 6-arg constructor as an overload; new `sourceType` field | Avoids breaking ~16 call sites across 7 files |
| 6 | `ClickHouseIngestionRepository` keeps its 1-arg constructor; metrics via a second constructor / `MetricsConfig` binding | Existing `ClickHouseIngestionRepositoryTest` constructs it directly |
| 7 | Log Explorer search uses `hasToken()` / token-delimited `LIKE` with the time filter first; `OR match()` removed | `tokenbf_v1` does not accelerate `positionCaseInsensitive` or `match` |
| 8 | Webhook timer recorded in `whenComplete` of the async send | The send is asynchronous; timing the dispatch call measures nothing |
| 9 | Port 8080 exposure decision made explicit (bind to `127.0.0.1` unless vendors ingest directly) | Public 8080 exposes `/actuator/prometheus` and `/v1/auth/validate-grafana` to direct access |
| 10 | Actuator security: single `@Order(1)` chain with `securityMatcher("/actuator/**")`; `JwtFilter` keeps `/actuator/**` excluded | Removing the exclusion would make `JwtFilter` choke on Prometheus's `Authorization: Basic` header |
| 11 | Prometheus uses `password_file` (mounted secret), not env expansion | `prometheus.yml` does not expand environment variables |
| 12 | Health checks and start.sh readiness fixed (`wget`; Grafana health check not behind `auth_request`) | `/grafana/api/health` through nginx would return 401 |
| 13 | Docs task adds `docs/to-do-list.md` update; Task 14 test command reconciled with repo Hard Rule 2 | AGENTS.md §6 and Hard Rule 2 |
| 14 | Spring Boot 4.x dependency/test notes added | Starter/module names and test auto-config differ in 4.x |
| 15 | **Q1 decided (2026-10-29, team lead):** vendors ingest through nginx on `:3000/v1/events`; core-engine `8080` becomes internal-only. Added Task 12a to make nginx fit for ingest traffic | One public entry point; `8080` would otherwise expose `/actuator/prometheus` and `/v1/auth/validate-grafana`. Today's `/v1/` proxy has a 1 MB body limit and no upstream keep-alive |
| 16 | **Q3 decided (2026-10-29, team lead):** support both HTTP and HTTPS. Cookie `Secure` is set per request from `X-Forwarded-Proto` (override `ULPF_COOKIE_SECURE`); Grafana `root_url` comes from `ULPF_PUBLIC_URL` | ULPF is a product installed by others; we can't know whether a deployment uses HTTPS. A fixed config value breaks Grafana login on HTTP if `Secure` is on |
| 17 | **Task 13a approved (2026-10-29, team lead):** Vitest + React Testing Library + jsdom, pinned, dev dependencies only, `npm test` added to CI in the same change | Lets the Grafana button be built test-first (AGENTS.md §4); also unblocks the LLM mapping agent plan (Phase 5). Runtime image is unaffected (multi-stage build) |
| 18 | **Task 0 added (prerequisite):** switch nginx → core-engine and core-engine → ClickHouse from `host.containers.internal` to service names, as its own small change before any Grafana work | Every new route in this plan uses service names and Task 14 closes the host ports; name resolution must be proven on Podman 3.4 first. Also faster: container-to-container traffic skips the host port forwarder |
| 19 | **Grafana session revocation (A2, corrected 2026-10-29):** cookie TTL default 1h → 15 min; hard 8 h session cap that sliding refresh cannot extend; token bound to a password fingerprint so rotating `ULPF_ADMIN_PASSWORD` ends Grafana sessions; `validate-grafana` reads the user through a 60 s cache | Sliding refresh could keep a stolen cookie alive forever, and nothing ended Grafana sessions when the admin password was rotated. (An earlier draft framed this as demoted/deleted admins; in practice the only `ADMIN` is the account seeded from `.env`, so password rotation is the main trigger and the role check is defence in depth. Depends on backlog item 11 landing first) |
| 20 | **Grafana logout (A3, 2026-10-30):** new `POST /v1/auth/grafana-logout` that expires the `HttpOnly` cookie; no auth, no DB, excluded from `JwtFilter`; both frontend logout paths call it for `ADMIN` | Frontend JavaScript cannot delete an `HttpOnly` cookie, and the 401 auto-logout runs with an expired ULPF token, which `JwtFilter` would otherwise reject before the endpoint runs |
| 21 | **Monitor secret handling (A5, 2026-10-30):** one generated file `secrets/monitor_password` (created by `start.sh`), mounted into core-engine and Prometheus through compose `secrets:`; no default password (fails closed); `secrets/` `0700`, file `0644`; `MONITOR_PASSWORD` no longer in `.env` | Prometheus cannot expand env vars, so the same secret lived in two places with nothing creating or syncing the file; a known default would leave the metrics open |
| 22 | **Prometheus retention and cardinality budget (A6, 2026-10-30):** retention defaults 30 days / 5 GB, overridable in `.env` (`PROMETHEUS_RETENTION_TIME`, `PROMETHEUS_RETENTION_SIZE`); timers and summaries lose their per-table and per-vendor tags and use explicit buckets; tag cap `ULPF_METRICS_MAX_TAG_VALUES` and Prometheus `sample_limit: 50000` | Measured: the first-draft metrics produced 171,568 series at 1,000 sources (16.8 MB per scrape, ~0.5-1 GB Prometheus RAM, and a 5 GB cap that would have trimmed history to a few days); the revised design produces 1,388 |

Corrections to rev 1's "What I verified": `source_type` is on the `sources` table but is **not** in `CredentialRecord` or the credential SQL select (it must be added); `openIncidents` lives in `TableIngestionFailureNotificationService` (as rev 1 already stated, not `AlertDispatcher`).

## 1. Goal

Stand up a real, air-gapped-friendly Prometheus + Grafana observability stack for ULPF. The legacy pseudo-Grafana UI is already stripped. "Done" means: the core-engine exports production metrics via Micrometer/Actuator at a secured `/actuator/prometheus`; Prometheus scrapes them over the internal network with basic auth; Grafana runs internal-only, fronted by the existing nginx at `/grafana/` with admin-only access enforced via a JWT-validated reverse-proxy auth flow; two provisioned dashboards surface pipeline health and replace the removed log search + timeseries features; a dedicated read-only ClickHouse user backs Grafana's log panels; and all of this works with no runtime internet access.

## 2. What I verified

**Greenfield — nothing observability-related exists yet:**
- `core-engine/pom.xml` — no `spring-boot-starter-actuator`, no `micrometer-registry-prometheus`. Spring Boot 4.1.0, Java 21. The artifacts are not in the local `~/.m2`; they must be resolved online once before offline builds (see Risks).
- `core-engine/src/main/resources/application.yaml` — no `management.*` config.
- `compose.yaml` — no prometheus/grafana services. Three services (clickhouse, core-engine, frontend) on `ulpf-net` (plain bridge, dynamic IPs); all ports currently public (8123/9000, 8080, 3000).
- `infra/` — no grafana/ or prometheus/ dir. Only clickhouse-init + clickhouse-config.

**Security gap (confirmed present, security-sensitive per AGENTS.md §7):**
- `SpringSecurity.java:39-40` — `/actuator/**` is `permitAll()` (the endpoint doesn't exist yet → would 404).
- `JwtFilter.java:30-33` — `/actuator/**` in `EXCLUDED_PATHS`, skips JWT entirely. `JwtFilter` is a `@Component` (auto-registered as a servlet filter).
- `JwtUtil.java:40` — token expiry hard-coded to 24h; no custom claims/TTL support.

**Ingestion hot path (instrumentation points):**
- `EventIngestionService.ingest()` — `cred.vendorId()` and `cred.sourceId()` in scope; `source_type` is NOT. It exists as `sources.source_type` (`schema.sql:29`) but `CredentialRepository` (lines ~39-46, 80) selects only `c.credential_id, c.source_id, s.vendor_id, c.key_hash, c.status, c.created_at`. Credential lookup is cached (`activeCredentialCache`).
- `CredentialRecord` is constructed in ~16 places across 7 files (`CredentialRepository`, `OnboardingService`, and tests) — its canonical constructor must stay compatible.
- `ClickHouseIngestionRepository.java` — two `ConcurrentLinkedQueue` buffers; `@Scheduled(fixedDelay=1000)` flush; inline flush at `size() >= batchSize` (500); `flush()` raw batch insert + Merkle; `flushCanonical()` groups by table; `insertWithIsolation()` recursive halving; drops on degraded failure; `getQueueSize()`/`getCanonicalQueueSize()` exist (gauge hooks). Constructor takes 1 arg and is called directly by `ClickHouseIngestionRepositoryTest`. **No separate ingestion worker thread pool** — backpressure signal is buffer depth.
- `AlertDispatcher.java:66-74` — `webhookExecutor` is `ThreadPoolExecutor(2,2, queue100, DiscardPolicy)` (drop point). `sendWebhook()` runs asynchronously (~lines 108-205).
- `SchemaDriftNotificationService.checkAndNotifyDrift()` — drift trigger. `TableIngestionFailureNotificationService.notify*()` — incident triggers; `openIncidents` (`ConcurrentHashMap`) and `getOpenIncidents()` live here.

**Infra patterns:**
- `infra/Containerfile` — COPYs individual clickhouse-init SQL + users.d XML files (new files have no effect unless added to the COPY list).
- `infra/clickhouse-config/users.d/async_inserts.xml` — profiles-only pattern.
- `frontend/nginx.conf` — reverse-proxies `/v1/` to core-engine; only public HTTP entry (port 3000).
- `.env.example` — grouped `KEY=value`; compose reads via `${VAR:-default}`.
- `startup/start.sh` — creates `.env` from `.env.example` only if missing (won't merge new vars into an existing `.env`); readiness checks engine + frontend only.
- `infra/clickhouse-init/01_raw_events.sql` — databases `ulpf_raw`, `ulpf_events`; `raw_payload` has `INDEX idx_raw_token ... TYPE tokenbf_v1(...)`.

**Research confirmed:**
- Spring Boot Actuator exposes `/actuator/prometheus` once `micrometer-registry-prometheus` is on the classpath + `management.endpoints.web.exposure.include=prometheus`.
- Grafana `auth.proxy` reads `X-WEBAUTH-USER`, supports `whitelist` (IP/CIDR restriction), `auto_sign_up`, `enable_login_token`.
- ClickHouse: users defined in XML (`users.d`) are config-managed and **cannot be modified via SQL `GRANT`**; permissions for them are declared in XML (`<grants><query>GRANT ...</query></grants>`). SQL-defined users are persisted in the access storage and are created by init SQL (first boot only). Do not mix the two for one user.
- `tokenbf_v1` supports `hasToken`, `=`, `IN` and `LIKE` patterns aligned to token boundaries; it does **not** accelerate `positionCaseInsensitive` or `match`.
- Grafana runtime plugin install (`GF_PLUGINS_PREINSTALL`) hits the internet → unsuitable for air-gapped. Bake the plugin at **build** time via `grafana-cli plugins install`. `grafana/grafana-oss` is deprecated after 12.4.0 → use `grafana/grafana`.
- nginx `ngx_http_auth_request_module` + `auth_request_set` enable the validation-subrequest pattern (verify `nginx -V` on the image at implementation time).
- With Grafana `serve_from_sub_path=true`, the proxy must forward the `/grafana/` prefix unchanged.

## 3. Proposed approach

```mermaid
flowchart TD
    subgraph "Public entry (host :3000)"
        N[nginx in frontend container]
    end
    subgraph "ulpf-net (internal only, fixed subnet)"
        CE[core-engine :8080<br/>/actuator/prometheus basic-auth<br/>/v1/auth/grafana-session + validate-grafana]
        PROM[Prometheus<br/>scrapes CE with basic auth via password_file]
        GRAF[Grafana<br/>auth.proxy + anon disabled]
        CH[ClickHouse<br/>read-only grafana user from XML]
    end
    ADMIN((Admin browser)) -->|1 JWT in localStorage| N
    N -->|2 /grafana/ + cookie| N
    N -->|3 auth_request subrequest| CE
    CE -->|4 200 + X-Webauth-User (+ refreshed Set-Cookie)| N
    N -->|5 proxy + X-WEBAUTH-USER| GRAF
    GRAF -->|queries| CH
    GRAF -->|queries| PROM
    PROM -->|scrape /actuator/prometheus basic auth| CE
```

**Key design decisions:**
1. **Grafana RBAC** = nginx reverse-proxy auth: nginx validates a ULPF admin session cookie via an `auth_request` subrequest to core-engine, then sets `X-WEBAUTH-USER` for Grafana's auth.proxy. Anonymous disabled. Because the browser can't send localStorage JWTs on navigation, the admin's "Launch Grafana" action POSTs the JWT to a session endpoint, receives an HttpOnly cookie, then opens `/grafana/`.
   - **Token type separation:** the cookie token is minted with a `purpose=grafana-session` claim and a configurable TTL (default 15 min, `GRAFANA_SESSION_TTL_MINUTES`) and `sessionStart` and `pwv` claims. `validate-grafana` *requires* that claim and checks the user row (cached) rather than trusting the token's baked-in role (see **Revocation** below); `JwtFilter` *rejects* tokens carrying it on all normal API routes.
   - **Revocation (password rotation ends Grafana sessions):** there is exactly one way to be `ADMIN` (the account seeded from `ULPF_ADMIN_USERNAME` / `ULPF_ADMIN_PASSWORD` by `AdminUserSeeder`; users are never deleted, and backlog item 11 makes the seeder demote any stale admin and stops onboarding approval from changing an admin's role), so the realistic revocation event is rotating that password after a suspected leak. The role check below is defence in depth. The token carries a `pwv` claim = first 16 hex chars of SHA-256(`password_hash`) (never the hash itself). `validate-grafana` runs for every Grafana request, so it must cost a map lookup, not a DB query: it reads the user row (`UserRepository.findById`) through a small in-memory cache (user id → role + current `pwv`, 60 s lifetime; same idea as `activeCredentialCache`). Missing user, role not `ADMIN`, or `pwv` mismatch → 401. `updatePasswordAndRole` evicts the entry, and a restart (how `.env` changes are applied) empties the cache anyway.
   - **Hard session cap:** refresh copies `sessionStart` from the old token and never resets it; once `now - sessionStart > GRAFANA_SESSION_MAX_HOURS` (default 8) `validate-grafana` returns 401 and the admin must launch Grafana again from ULPF. Short TTL (15 min) costs active users nothing, since refresh renews it, and limits how long a stolen cookie works.
   - **Sliding refresh:** on a valid request, `validate-grafana` may return a refreshed `Set-Cookie` (only when the remaining TTL is below half). nginx forwards it with `auth_request_set $new_cookie $upstream_http_set_cookie;` + `add_header Set-Cookie $new_cookie always;`. Cookie is `HttpOnly; SameSite=Lax; Path=/grafana` (and `Secure` when the request arrived over HTTPS, decided per request from `X-Forwarded-Proto`; `ULPF_COOKIE_SECURE=auto|true|false`, default `auto`); it is cleared on logout (see **Logout** below).
   - **Logout:** the cookie is `HttpOnly`, so frontend JavaScript cannot delete it; only a server response can. `POST /v1/auth/grafana-logout` returns `204` with the same cookie name and path set to expire now (`Max-Age=0`; same `Secure` rule as creation, because a browser ignores a `Secure` cookie change that arrives over plain HTTP). It does no DB work and keeps no server state: the token itself is not revoked. It dies at its 15 min expiry, the 8 h cap, or a password change (see **Revocation**), which is acceptable because the cookie is `HttpOnly` and `Path=/grafana`. Skipping logout would leave a live admin Grafana session in the browser after logging out of ULPF (e.g. on a shared machine).
   - **It must work without a valid login.** The 401 auto-logout in `client.js` fires exactly when the ULPF token has expired, and `JwtFilter` answers 401 to any request carrying an invalid Bearer token before security rules run. So the path is added to `JwtFilter.EXCLUDED_PATHS` and to `permitAll` in `SpringSecurity`. `EXCLUDED_PATHS` matches on path only (no HTTP method), which is why logout is a separate path and not `DELETE /v1/auth/grafana-session` (`POST` on that path needs the JWT principal). Both frontend logout paths (`LogoutButton.jsx`, and the 401 handler in `client.js`) call it fire-and-forget, only when the stored role is `ADMIN`, through nginx on the same origin.
2. **Prometheus scrape auth** = HTTP Basic with a dedicated monitor credential. One `SecurityFilterChain` at `@Order(1)` with `securityMatcher("/actuator/**")`: `/actuator/health` open, `/actuator/prometheus` requires the monitor user (`httpBasic`). The password is one generated secret file shared by both containers (see **Monitor secret** below); the app hashes it with the existing `BCryptPasswordEncoder` at startup.
   - **Monitor secret (one file, nothing to sync):** `start.sh` creates `secrets/monitor_password` once, if missing: 64 random hex characters (`openssl rand -hex 32`, falling back to `/dev/urandom`), written without a trailing newline. `compose.yaml` declares it as a top-level `secrets:` entry and mounts it read-only at `/run/secrets/monitor_password` in `core-engine` and `prometheus`. Prometheus uses `basic_auth.password_file: /run/secrets/monitor_password` (it cannot expand env vars in `prometheus.yml`). core-engine reads the same file with `spring.config.import=optional:configtree:/run/secrets/`, mapped to `ulpf.monitor.password` (Spring Boot 4.1.0's config-tree loader trims a trailing newline: its `ConfigTreeConfigDataLoader` uses `AUTO_TRIM_TRAILING_NEW_LINE`, checked in the jar). The `MONITOR_PASSWORD` env var stays only as an override for local runs and tests. Nothing about the secret lives in `.env`, and unlike an env var it does not show in `podman inspect` or the process environment.
   - **Permissions:** compose mounts the host file as-is (podman-compose 1.6.0 turns file secrets into plain read-only bind mounts and ignores `mode`/`uid`/`gid`), and the Prometheus image runs as a non-root user (`nobody`; confirm when pinning the image). So `secrets/` is `0700` (other host users cannot enter) and the file is `0644` (readable by the container user). `0600` would make the scrape fail with a permission error. core-engine's image has no `USER` line, so it reads the file as root. `secrets/` is git-ignored.
   - **Fail closed, no default password:** unlike `JWT_SECRET` and the other dev defaults in `compose.yaml`, there is no known default. If no password is configured, the monitor user is not created, `/actuator/prometheus` is `denyAll`, and the app logs one `WARN` at startup: nothing is exposed and the app still starts. Declaring it under `secrets:` (not a plain `volumes:` bind mount) also makes a missing file stop compose with an error, where Docker would silently create an empty directory in its place (from the podman-compose source and the Compose docs; confirm in Task 8).
   - **Cost and rotation:** BCrypt (cost 10) measured **52.5 ms per check** on the reference machine, about 0.35% of one core at a 15 s scrape. A slow hash adds nothing against a 256-bit random secret, but it is cheap and avoids custom auth code, so it stays. Each *failed* attempt also costs about 50 ms of CPU, another reason `/actuator/**` must stay unreachable from outside (Q1). Rotation: delete `secrets/monitor_password`, rerun `start.sh`, restart `core-engine` and `prometheus`.
3. **JwtFilter on the actuator path:** keep `/actuator/**` in `JwtFilter.EXCLUDED_PATHS` so the JWT filter never sees `Authorization: Basic`. The Actuator chain's own `httpBasic` is the only gate. Only if testing shows `JwtFilter` executing twice (due to `@Component` auto-registration) add a `FilterRegistrationBean<JwtFilter>` with `setEnabled(false)` and keep it wired solely via `addFilterBefore` in the main chain.
4. **Grafana exposure** = nginx sub-path `/grafana/` on port 3000; no public Grafana/Prometheus port. `proxy_pass` has **no trailing slash**; WebSocket upgrade headers are set.
5. **Dashboards** = two: "Pipeline Health & Throughput" (Prometheus) + "Log Explorer" (ClickHouse read-only user, replaces removed search/timeseries).
6. **source_type tagging** = add `sourceType` to `CredentialRecord` and the credential SQL select (`s.source_type`). Keep the 6-arg constructor as an overload delegating to the new canonical one with `"unknown"`. Counters are cached per (vendor, source, type) key (e.g. `ConcurrentHashMap`) to avoid per-event meter lookup allocation.
7. **Port exposure (Q1 decided):** nginx on `:3000` is the only public entry point, for the UI **and** for vendor ingestion (`:3000/v1/events`). Core-engine `8080` is bound to `127.0.0.1:8080:8080` (host-local debugging only) and reached by other containers by service name on `ulpf-net`. ClickHouse ports bind to `127.0.0.1`. nginx is made fit for ingest traffic first (Task 12a); `8080` is closed only after that and after vendors have moved (Task 14).
8. **Air-gapped:** ClickHouse Grafana plugin baked into a custom Grafana image at build time; dashboards/datasources provisioned as files; all inter-service traffic internal; pinned images (no `latest`); pre-pull to the local registry. `ulpf-net` gets a fixed subnet so Grafana's `auth.proxy` `whitelist` can be a CIDR (container IPs are otherwise dynamic).
9. **ClickHouse read-only user** is defined entirely in XML (user, password hash, `<networks>`, `<profile>`, `<grants>`). No SQL grants file.

**Metric families (exact meters):**

| Family | Meter | Type | Tags | Instrumentation point |
|---|---|---|---|---|
| Ingestion throughput | `ulpf.events.ingested` | Counter (cached per tag set) | vendor_id, source_id, source_type | `EventIngestionService.ingest()` |
| Queue depth | `ulpf.queue.raw.size`, `ulpf.queue.canonical.size` | Gauge | — | bound in `MetricsConfig` to `ClickHouseIngestionRepository` getters |
| Webhook pool | `ulpf.webhook.executor.active`, `.queue.size`, `.pool.utilization` | Gauge | — | `AlertDispatcher.webhookExecutor` |
| Webhook drops | `ulpf.webhook.dropped` | Counter | — | counted rejection handler wrapping `DiscardPolicy` |
| Batch flush latency | `ulpf.clickhouse.flush.duration` | Timer (explicit buckets) | stage=raw\|canonical | `flush()`, `flushCanonical()` |
| Batch size | `ulpf.clickhouse.batch.size` | DistributionSummary (explicit buckets) | stage=raw\|canonical | `flush()`, `flushCanonical()` |
| Insert errors | `ulpf.clickhouse.insert.errors` | Counter | table (created on first failure) | `insertWithIsolation` systemic failure |
| Degraded/dropped/re-enqueued | `ulpf.records.degraded`, `.dropped`, `.reenqueued` | Counter | table (created on first failure) | `isolateAndDegradeSingleRecord`, `flush()` re-enqueue |
| Webhook delivery | `ulpf.webhook.delivery.duration` | Timer (explicit buckets) | alert_type | `AlertDispatcher.sendWebhook` (`whenComplete`) |
| Webhook outcome | `ulpf.webhook.delivery.total` | Counter | alert_type, success | `AlertDispatcher.sendWebhook` (`whenComplete`) |
| Drift rate | `ulpf.drift.events` | Counter | type=SCHEMA_DRIFT\|TOTAL_DRIFT | `SchemaDriftNotificationService.checkAndNotifyDrift` |
| Open incidents | `ulpf.incidents.open` | Gauge | — | bound to `TableIngestionFailureNotificationService.getOpenIncidents().size()` |
| Failure events | `ulpf.ingestion.failures` | Counter | type | each `notify*()` method |

Note: ingestion has no worker thread pool; "worker pool utilization" applies to the `webhookExecutor`.

**Cardinality and memory budget (measured 2026-10-30).** Memory in Prometheus and in the app grows with the number of *series*, not with retention, and every timer or summary multiplies its tag combinations by its bucket count. Measured with Micrometer 1.17.0 (the version Spring Boot 4.1.0 pins) exporting the Prometheus text format, at 1,000 sources / 333 vendors:

| Design | Series | Scrape size / time | Est. Prometheus RAM | Est. disk per day |
|---|---|---|---|---|
| First draft: `publishPercentileHistogram()` on timers tagged per table and per vendor | 171,568 | 16.8 MB / 151 ms | ~0.5-1 GB | ~1-2 GB (the 5 GB cap would trim history to a few days) |
| This plan: explicit buckets, no per-table or per-vendor tags on timers | 1,388 | 112 KB / 15 ms | ~4-8 MB | ~8-16 MB |
| This plan, worst case under the caps (5,000 sources, every table failing in all four error families) | 25,191 | 1.5 MB / 50 ms | ~76-151 MB | ~145-290 MB |

Series and scrape figures are measured (JVM binders included; Actuator's own Tomcat/HTTP metrics excluded, a few hundred more). RAM (3-6 KB per active series) and disk (1-2 bytes per sample at a 15 s scrape) are rules of thumb, not measured on this stack. One timer exports 3 series with no histogram, 72 with `publishPercentileHistogram()`, and 12 with 8 explicit buckets (measured).

Rules that keep it there:
- **Per-source, per-vendor and per-table tags only on Counters and Gauges** (one series each), never on a Timer or DistributionSummary. Timers and summaries use explicit buckets (`serviceLevelObjectives`) and only low-cardinality tags (`stage`, `alert_type`, `success`, `type`). Per-vendor webhook state is already visible in the app (`FAILING` status and in-app alerts), so the webhook meters carry no `vendor_id`.
- **Tag values come from the database** (source, vendor and table names), never from request content, so cardinality is bounded by what admins onboarded. `table` appears only on the error/degraded/dropped/re-enqueued counters, which are created on the first failure.
- **Hard cap:** `MeterFilter.maximumAllowableTags` on `source_id` and `table` at `ULPF_METRICS_MAX_TAG_VALUES` (default 5000). Values beyond the cap get no meter (checked: 2,500 distinct ids against a cap of 1,000 exported exactly 1,000 series), and one `WARN` is logged when the cap is first reached.
- **Prometheus seatbelt:** `sample_limit: 50000` in the scrape config (above the 25k worst case). An over-limit scrape fails and the target shows as down with a "sample limit exceeded" error, instead of memory growing unnoticed. `prometheus.yml` cannot read env vars, so raising it means editing the file.
- **Hot path:** counters are cached per tag set: 9 ns per event versus 72 ns for a builder lookup on every event (measured), i.e. 0.09% of a core at 100k events/s. Timers and summaries are touched once per batch.

**Backward-compatibility rule for instrumentation:** existing constructors stay intact. Classes that need a `MeterRegistry` get an additional constructor (the old one defaults to `new SimpleMeterRegistry()`), or the meters are bound externally in `MetricsConfig`. All 320 existing tests must continue to compile and pass unmodified except where a test is intentionally extended.

## 4. Test plan (written first)

| Test | Class | Expected to fail before because… |
|---|---|---|
| `/actuator/health` → 200, no auth | `ActuatorSecurityTest` | actuator dep not added yet |
| `/actuator/prometheus` → 401 without auth | `ActuatorSecurityTest` | no actuator chain yet |
| `/actuator/prometheus` → 200 + Prometheus format with monitor creds | `ActuatorSecurityTest` | registry not on classpath |
| `/actuator/prometheus` → 401 wrong creds; JWT bearer token does **not** grant access | `ActuatorSecurityTest` | — |
| no monitor password configured → `/actuator/prometheus` is denied even with a blank or guessed password, and no monitor user exists | `ActuatorSecurityTest` | no actuator chain yet |
| password supplied through a config-tree directory (file ending in a newline) authenticates the scrape | `ActuatorSecurityTest` | no secret loading yet |
| `POST /v1/auth/grafana-session` admin → 200 + Set-Cookie (HttpOnly, Path=/grafana, SameSite=Lax) | `GrafanaAuthSessionTest` | endpoint doesn't exist |
| same as non-admin → 403 | `GrafanaAuthSessionTest` | — |
| `GET /v1/auth/validate-grafana` valid cookie → 200 + `X-Webauth-User` | `GrafanaAuthSessionTest` | endpoint doesn't exist |
| validate-grafana missing/expired/invalid cookie → 401; normal login JWT (no purpose claim) → 401 | `GrafanaAuthSessionTest` | — |
| validate-grafana near expiry → refreshed Set-Cookie | `GrafanaAuthSessionTest` | no refresh logic |
| admin password changed (`updatePasswordAndRole`) after the cookie was issued → next validate-grafana → 401 (`pwv` mismatch; cache evicted, not waiting 60 s) | `GrafanaAuthSessionTest` | token never compared to current credentials |
| user not found or role not `ADMIN` → 401; user lookup served from cache on repeat calls (repository hit once within 60 s) | `GrafanaAuthSessionTest` | no live check, no cache |
| session older than `GRAFANA_SESSION_MAX_HOURS` → 401 even when the cookie itself is still valid; refreshed cookie keeps the original `sessionStart` | `GrafanaAuthSessionTest` | refresh can extend forever |
| `auto`: `X-Forwarded-Proto: https` → cookie has `Secure`; `http` or header missing → no `Secure`; `true`/`false` override the header (session and refreshed cookie alike) | `GrafanaAuthSessionTest` | `Secure` not request-aware |
| `POST /v1/auth/grafana-logout` with no credentials → 204 and `Set-Cookie: ulpf_grafana=; Max-Age=0; Path=/grafana; HttpOnly; SameSite=Lax`; `Secure` follows the same `auto`/`true`/`false` rule | `GrafanaAuthSessionTest` | endpoint doesn't exist |
| same call carrying an **expired** Bearer token → still 204 with the expiring cookie, not 401 (real security chain) | `GrafanaAuthSessionTest` | endpoint missing; once it exists this row stays red until the path is in `JwtFilter.EXCLUDED_PATHS` (the trap this row exists to catch) |
| `LogoutButton`: for role `ADMIN` calls the logout endpoint, and still clears localStorage and navigates if the call fails; for other roles makes no call | `LogoutButton.test.jsx` (Vitest) | no Vitest yet (approved, added in Task 13a) |
| `client.js` 401 handler: for role `ADMIN` calls the logout endpoint once and does not loop | `client.test.js` (Vitest) | no Vitest yet (approved, added in Task 13a) |
| grafana-session token presented to a normal API route → 401 | `JwtFilterPurposeTest` | filter ignores purpose claim |
| events-ingested counter increments with vendor/source/type tags | `EventIngestionMetricsTest` | no meter registered |
| `CredentialRecord` 6-arg constructor still works; `sourceType` loaded from SQL | `CredentialRepositoryTest` (extend) | field missing |
| queue gauges reflect buffer depth | `QueueMetricsTest` | no gauges bound |
| flush duration timer + batch size recorded on flush | `ClickHousePipelineMetricsTest` | no timers |
| insert-error/degraded/dropped/re-enqueued counters increment on failure path | `ClickHousePipelineMetricsTest` | no counters |
| webhook timer + outcome counter recorded on async completion (success and failure) | `AlertDispatcherMetricsTest` | no meters |
| webhook drop counter + executor gauges | `AlertDispatcherMetricsTest` | no meters |
| drift counter increments by type | `SchemaDriftMetricsTest` | no counter |
| incident gauge tracks open incidents; failure counters per `notify*()` | `IncidentMetricsTest` | no meters |
| after exercising each instrumented path, no `Timer` or `DistributionSummary` carries a `source_id`, `vendor_id` or `table` tag (guards the tag policy) | `MetricsCardinalityTest` | meters not defined yet |
| each timer/summary exports at most 16 series (explicit buckets, no `publishPercentileHistogram()`) | `MetricsCardinalityTest` | meters not defined yet |
| 2,500 distinct source ids with `ULPF_METRICS_MAX_TAG_VALUES=1000` → exactly 1,000 `ulpf_events_ingested` series, one `WARN` logged | `MetricsCardinalityTest` | no cap yet |
| ClickHouse grafana user: SELECT ok, INSERT denied, DDL denied, limits enforced | `ReadOnlyUserTest` (integration, `@EnabledIfSystemProperty`) | user not provisioned |
| Frontend: Grafana launch button visible to admin only; click calls session endpoint | `Navbar.test.jsx` (Vitest) | no Vitest yet (approved, added in Task 13a) |

Spring Boot 4.x note: Prometheus export is typically disabled in `@SpringBootTest` unless metrics export is opted in (`@AutoConfigureObservability` or its 4.x equivalent, or `management.prometheus.metrics.export.enabled=true` in test properties). Confirm the exact names after resolving the dependencies (Task 1) so the `/actuator/prometheus` → 200 test does not 404.

## 5. Files to change

**Backend (core-engine):**
- `pom.xml` — add `spring-boot-starter-actuator` + `micrometer-registry-prometheus` (coordinates confirmed online against Boot 4.1.0 BOM)
- `application.yaml` — `management.endpoints.web.exposure.include=health,prometheus`; `MONITOR_USERNAME` (default `monitor`) / `GRAFANA_SESSION_TTL_MINUTES` (15) / `GRAFANA_SESSION_MAX_HOURS` (8) via env with safe defaults; `spring.config.import=optional:configtree:/run/secrets/` and `ulpf.monitor.password` from the `monitor_password` secret (no default; empty means the endpoint is denied); `ulpf.metrics.max-tag-values: ${ULPF_METRICS_MAX_TAG_VALUES:5000}`
- `SpringSecurity.java` — new `@Order(1)` actuator chain (`securityMatcher("/actuator/**")`, health open, prometheus basic-auth with BCrypt-hashed monitor user); remove `/actuator/**` from main-chain `permitAll`; allow `/v1/auth/validate-grafana` and `/v1/auth/grafana-session` according to their own auth rules; `permitAll` for `POST /v1/auth/grafana-logout`
- `JwtFilter.java` — keep `/actuator/**` excluded; reject tokens with a `purpose` claim on normal routes; add `/v1/auth/grafana-logout` to `EXCLUDED_PATHS` (an expired Bearer token would otherwise get 401 before the endpoint runs)
- `JwtUtil.java` — overload `generateToken` with custom TTL + `purpose` + `sessionStart` + `pwv` claims; add purpose-aware validation helper
- `controlplane/service` (new small `GrafanaAccessService` or inside `GrafanaAuthController`) — 60 s cached user lookup (role + password fingerprint) for `validate-grafana`; `common/db/UserRepository.updatePasswordAndRole` evicts the entry
- New `common/MetricsConfig.java` — gauge bindings (queue sizes, incident count) via `MeterBinder`/`MeterRegistry`; no constructor changes to existing classes; registers the tag-cap `MeterFilter`s (`ULPF_METRICS_MAX_TAG_VALUES`) and the explicit bucket sets
- New `controlplane/controller/GrafanaAuthController.java` — `POST /v1/auth/grafana-session`, `GET /v1/auth/validate-grafana`, `POST /v1/auth/grafana-logout` (expires the cookie; no auth, no DB)
- `common/db/CredentialRepository.java` + `CredentialRecord` — add `sourceType` (`s.source_type` in SQL); keep 6-arg overload (`"unknown"`)
- `common/db/ClickHouseIngestionRepository.java` — add `MeterRegistry` constructor (keep 1-arg), flush timers, batch-size summary, error/drop/degraded/re-enqueue counters
- `dataplane/service/EventIngestionService.java` — cached ingestion counters
- `dataplane/service/AlertDispatcher.java` — executor gauges, counted rejection handler, delivery timer/counter in `whenComplete`
- `dataplane/service/SchemaDriftNotificationService.java` — drift counter
- `dataplane/service/TableIngestionFailureNotificationService.java` — failure counters; expose `getOpenIncidents()` size for the gauge

**Infrastructure:**
- `compose.yaml` — add `prometheus` + `grafana` services (internal-only, `ulpf-net`); fixed subnet on `ulpf-net`; bind ClickHouse ports and (per Q1) core-engine `8080` to `127.0.0.1`; named volumes; `depends_on`; healthchecks using `wget`
- `infra/prometheus/prometheus.yml` — scrape config with `basic_auth.password_file: /run/secrets/monitor_password` and `sample_limit: 50000`
- `compose.yaml` (retention, A6) — `prometheus` `command` flags with `${PROMETHEUS_RETENTION_TIME:-30d}` / `${PROMETHEUS_RETENTION_SIZE:-5GB}`; `core-engine` gets `ULPF_METRICS_MAX_TAG_VALUES`
- `.gitignore` — add `secrets/`
- `infra/grafana/Containerfile` — `grafana/grafana:<pinned>`, bake `grafana-clickhouse-datasource@<pinned>` at build
- `infra/grafana/grafana.ini` — `auth.proxy` (header `X-WEBAUTH-USER`, `whitelist=<ulpf-net CIDR>`, `auto_sign_up`), anonymous disabled, `root_url` from `GF_SERVER_ROOT_URL=${ULPF_PUBLIC_URL}/grafana/` (not `%(protocol)s`, which is always `http` inside the container and would produce `http://` redirects on HTTPS sites), `serve_from_sub_path=true`
- `infra/grafana/provisioning/datasources/` — Prometheus + ClickHouse (read-only `grafana` user)
- `infra/grafana/provisioning/dashboards/` — provider config + two dashboard JSONs
- `infra/clickhouse-config/users.d/grafana_user.xml` — `grafana` user (sha256 password hash), `<networks>`, `grafana_profile` (`readonly=1` + limits), `<grants>` for `ulpf_raw.*`, `ulpf_events.*`
- `infra/Containerfile` — COPY the new `users.d` XML (**no** grants SQL)
- `frontend/nginx.conf` — `/grafana/` location with `auth_request`, header overwrite, WebSocket upgrade, cookie refresh; internal-only auth location; unauthenticated-from-inside health path if needed
- `frontend/nginx.conf` (Task 12a) — upstream `backend_service` moves from `host.containers.internal:8080` to `core-engine:8080` with `keepalive`; new dedicated `location = /v1/events` for vendor ingestion (body size limit, keep-alive headers, timeouts)
- `frontend/nginx.conf`, `compose.yaml`, `.env.example` (Task 0) — nginx upstream and `CLICKHOUSE_URL` move to service names (`core-engine:8080`, `clickhouse:8123`); no `resolver` directive; ports unchanged
- `README.md` — minimum runtime note (Podman 4+ / Docker Compose v2, or Podman 3.x with `dnsname`); vendor ingest example uses `:3000/v1/events` instead of `:8080`; service table notes `8080` is internal-only

**Frontend:**
- `frontend/Containerfile` — `VITE_GRAFANA_URL` build arg
- `frontend/src/components/Navbar.jsx` — admin-only "Grafana Dashboard" action
- New `frontend/src/api/grafana.js` — `createGrafanaSession()`, `clearGrafanaSession()`
- `frontend/src/components/LogoutButton.jsx` and `frontend/src/api/client.js` (401 handler) — for role `ADMIN`, call `clearGrafanaSession()` fire-and-forget before clearing localStorage
- `frontend/package.json` + `vitest.config.js` — Vitest + RTL + jsdom as pinned dev dependencies, `test` script (approved 2026-10-29, Task 13a)
- `.github/workflows/ci-cd.yml` — run `npm test` after `npm ci` in the frontend job (Task 13a)
- `.env.example` — observability section (`MONITOR_USERNAME`; the monitor password is generated into `secrets/monitor_password`, not `.env`; `GRAFANA_CLICKHOUSE_PASSWORD`, `GRAFANA_ADMIN_PASSWORD`, `GRAFANA_SESSION_TTL_MINUTES` (default 15), `GRAFANA_SESSION_MAX_HOURS` (default 8), `VITE_GRAFANA_URL`, `ULPF_PUBLIC_URL` (default `http://localhost:3000`), `ULPF_COOKIE_SECURE` (default `auto`), `PROMETHEUS_RETENTION_TIME` (default `30d`), `PROMETHEUS_RETENTION_SIZE` (default `5GB`), `ULPF_METRICS_MAX_TAG_VALUES` (default `5000`))
- `startup/start.sh` — readiness + banner for Prometheus and Grafana; note on existing `.env`; create `secrets/` (`0700`) and `secrets/monitor_password` (`0644`, only if missing, no trailing newline)

**Docs:**
- New plan file `docs/plans/2026-10-28-prometheus-grafana-observability.md`
- `docs/ARCH/ARCHITECTURE.md`, `API_SPECIFICATION.md`, `FILE_GUIDE.md`
- `docs/db/DATABASE_SCHEMA.md`, `COMMON_DB_GUIDE.md`
- `docs/frontend/frontend.md`, `README.md`
- `docs/to-do-list.md` — update the observability item (AGENTS.md §6)

## 6. Steps (task breakdown)

**Prerequisite:** backlog item 11 (single-admin enforcement, in `to-do-list.md`) must be completed before Task 0. Grafana access is granted to `ADMIN` only, so this plan relies on `ADMIN` meaning the single account defined in `.env`.

Each task is TDD, builds on the previous, and produces a demoable increment.

**Task 0: Prerequisite — switch internal routes to service names (own small change, lands first).**
Objective: containers reach each other by name (`core-engine:8080`, `clickhouse:8123`) instead of `host.containers.internal`, with **no behaviour change** and all host ports still open. Every later task (Prometheus scraping `core-engine:8080`, Grafana querying `clickhouse:8123`/`prometheus:9090`, nginx proxying to `grafana:3000`, and the loopback port binds in Task 14) depends on name resolution working, so it is proven first, in isolation. Why it also helps performance: with rootless Podman, `host.containers.internal` traffic leaves the container, passes the host's user-space port forwarder and re-enters; container-to-container over the bridge stays in the kernel. The core-engine → ClickHouse insert path is the hottest path in the system.
Background: commit `fe2c3dd` replaced `proxy_pass http://core-engine:8080` with `host.containers.internal` after name lookup failed. The removed config used `resolver 127.0.0.11 10.88.0.1 10.89.0.1 8.8.8.8`; `127.0.0.11` is Docker's embedded DNS and does not exist on Podman, so that `resolver` line, not name lookup itself, is the likely cause. Reference environment: Podman 3.4.4 rootless on Ubuntu 22.04 (`podman-compose` 1.6.0, CNI `dnsname` plugin installed).
Steps (test first):
1. **Verify (red):** on the reference environment, bring the stack up and check from the frontend container that `core-engine` and `clickhouse` resolve and answer (`podman exec ulpf-frontend wget -qO- http://core-engine:8080/v1/health`, same for `http://clickhouse:8123/ping`). Record the result in this plan's Outcome. If this fails, stop and switch to the fallback below before continuing.
2. **Change:** `frontend/nginx.conf` upstream → `server core-engine:8080;` with **no** `resolver` directive (the container's normal DNS is used). `compose.yaml` and `.env.example`: `CLICKHOUSE_URL` default → `jdbc:clickhouse:http://clickhouse:8123/ulpf_raw?...`. Note that nginx resolves an upstream name once at startup; `depends_on: core-engine: service_healthy` (already present) covers the start order.
3. **Verify (green):** `./startup/start.sh`; UI login works through `:3000`; an event posted to `:3000/v1/events` lands in ClickHouse; full backend suite (AGENTS.md §5 command) still passes; stop and start only core-engine and confirm nginx recovers (document the behaviour if it needs an nginx reload).
4. **Ports stay open** in this task. Closing/loopback-binding them remains in Task 14, after everything else works by name.
Compatibility: minimum supported runtime is stated in the README: Podman 4+ or Docker Compose v2 (built-in DNS), or Podman 3.x with `dnsname` (verified above). Existing `.env` files that override `CLICKHOUSE_URL` must be edited by hand to `clickhouse:8123`.
Fallback if names don't resolve on a target host: fixed container IPs on the fixed `ulpf-net` subnet (the subnet is already required by this plan), used in the nginx upstream and `CLICKHOUSE_URL`.
Files: `frontend/nginx.conf`, `compose.yaml`, `.env.example`, `README.md`.

**Task 1: Add Actuator + Micrometer and secure the endpoints.**
Objective: `/actuator/health` open, `/actuator/prometheus` basic-auth protected. Guidance: resolve dependencies online first (`mvn dependency:resolve`) and confirm the Boot 4.x artifact names; add the two deps; add `management.endpoints.web.exposure.include=health,prometheus` and monitor creds to `application.yaml`; add an `@Order(1)` `SecurityFilterChain` with `securityMatcher("/actuator/**")` (health permitAll, prometheus `httpBasic`, BCrypt-hashed monitor user, stateless); remove `/actuator/**` `permitAll` from the main chain; leave `JwtFilter.EXCLUDED_PATHS` as is (see decision 3). Tests: the `ActuatorSecurityTest` rows. Demo: `curl /actuator/health` open; `curl -u monitor:pass /actuator/prometheus` returns metrics; no creds → 401; a user JWT → 401.

**Task 2: Grafana reverse-proxy auth session endpoints.**
Objective: nginx can validate an admin session and hand Grafana the username. Guidance: overload `JwtUtil.generateToken` (TTL + `purpose` claim); `POST /v1/auth/grafana-session` (`@PreAuthorize("hasRole('ADMIN')")`) mints a `purpose=grafana-session` token and sets cookie `ulpf_grafana` (`HttpOnly; SameSite=Lax; Path=/grafana; Max-Age=<TTL>`; `Secure` added when `ULPF_COOKIE_SECURE=true`, or when `auto` and the request's `X-Forwarded-Proto` is `https`; never when `false`); `GET /v1/auth/validate-grafana` verifies cookie, purpose, expiry, the 8 h `sessionStart` cap, and against the cached user row that the role is `ADMIN` and `pwv` still matches, returns 200 + `X-Webauth-User` (and a refreshed `Set-Cookie` below half TTL) or 401; `JwtFilter` rejects tokens with a `purpose` claim on normal routes; `POST /v1/auth/grafana-logout` returns 204 and expires the cookie (no auth, no DB; see decision 1, **Logout**). Tests: `GrafanaAuthSessionTest`, `JwtFilterPurposeTest`. Demo: curl flow login → session → validate.

**Task 3: Ingestion throughput metric (with source_type).**
Objective: Count events by vendor/source/type with no hot-path lookup cost. Guidance: add `sourceType` to `CredentialRecord` (canonical 7-arg) plus the 6-arg overload; add `s.source_type` to the credential SQL and row mapper; register counters lazily and cache per tag set; increment in `EventIngestionService.ingest()`. Tests: `EventIngestionMetricsTest`; extend `CredentialRepositoryTest`; confirm all existing tests compile unchanged. Demo: ingest an event, grep `ulpf_events_ingested` in `/actuator/prometheus`.

**Task 4: Queue & backpressure metrics.**
Objective: Surface buffer depth, webhook pool pressure and drops. Guidance: bind `ulpf.queue.raw.size`/`.canonical.size` in `MetricsConfig` to the repository getters (no constructor change); in `AlertDispatcher` add executor gauges and replace `DiscardPolicy` with a rejection handler that increments `ulpf.webhook.dropped` and then discards. Tests: `QueueMetricsTest`, `AlertDispatcherMetricsTest`. Demo: enqueue events → gauge moves; overflow webhooks → drop counter.

**Task 5: ClickHouse batch pipeline metrics.**
Objective: Flush latency, batch size, insert errors, degraded/dropped/re-enqueued. Guidance: add the `MeterRegistry` constructor (keep the 1-arg one); time the `batchUpdate` in `flush()`/`flushCanonical()` (tags `stage`, `table`); record batch size; increment error/degraded/dropped/re-enqueued counters at the named sites. Tests: `ClickHousePipelineMetricsTest` with a failing mock to drive failure paths; existing `ClickHouseIngestionRepositoryTest` unchanged and green.

**Task 6: Alerting & webhook delivery metrics.**
Objective: Webhook latency/success, drift rate, incident counts. Guidance: in `AlertDispatcher.sendWebhook`, start a `Timer.Sample` before the async send and stop it in `whenComplete`, tagging `alert_type`, `vendor_id` and `success`; increment `ulpf.drift.events{type}` in `checkAndNotifyDrift`; bind `ulpf.incidents.open` to `getOpenIncidents().size()` and increment `ulpf.ingestion.failures{type}` in each `notify*()`. Tests: `AlertDispatcherMetricsTest`, `SchemaDriftMetricsTest`, `IncidentMetricsTest`.

**Task 7: ClickHouse read-only grafana user (XML only).**
Objective: A locked-down user for Grafana's log panels. Guidance: `infra/clickhouse-config/users.d/grafana_user.xml` defining the `grafana` user (sha256 password hash, `<networks>` limited to the `ulpf-net` subnet, profile `grafana_profile`) with `readonly=1`, `max_execution_time=30`, `max_memory_usage=1000000000`, `max_result_rows=1000000`, `result_overflow_mode=break`, and `<grants>` with `GRANT SELECT ON ulpf_raw.*` and `ulpf_events.*`; COPY it in `infra/Containerfile`; add `GRAFANA_CLICKHOUSE_PASSWORD` to `.env.example` (note: the XML needs the hash, so document how to generate it, or template it at container start). No SQL grants file. Because XML reloads on every start, existing volumes pick it up after a restart — no one-time command. Tests: `ReadOnlyUserTest` (SELECT ok; INSERT/DDL denied; row limit hit). Demo: `clickhouse-client --user grafana`.

**Task 8: Prometheus service + scrape config.**
Objective: Prometheus scrapes the secured endpoint internally. Guidance: `infra/prometheus/prometheus.yml` (target `core-engine:8080`, `metrics_path: /actuator/prometheus`, `basic_auth` with `password_file: /run/secrets/monitor_password`, 15s interval); give the `prometheus` service the `monitor_password` compose secret; add `prometheus` service (internal-only, `prometheus-data` volume, `depends_on` core-engine healthy, healthcheck `wget -q --spider http://localhost:9090/-/healthy`); pinned image. **Retention (A6):** `command` flags `--config.file=/etc/prometheus/prometheus.yml --storage.tsdb.path=/prometheus --storage.tsdb.retention.time=${PROMETHEUS_RETENTION_TIME:-30d} --storage.tsdb.retention.size=${PROMETHEUS_RETENTION_SIZE:-5GB}`. Defaults are 30 days and 5 GB, overridable in `.env`; whichever limit is hit first wins; units are required (`30d`, `12w`, `5GB`, `500MB`). Retention only decides disk use; memory depends on the series count (see **Cardinality and memory budget**). The size cap counts blocks, WAL and head chunks, but only blocks are deleted to honour it, and cleanup runs at compaction (roughly every 2 hours), so give the volume's disk about 10% headroom above the cap. At the typical load above, 30 days is about 0.25-0.5 GB, so the time limit is what binds and the size cap is the safety net. Verification: `/api/v1/targets` shows UP; `/api/v1/status/flags` shows the configured retention values; setting `PROMETHEUS_RETENTION_TIME=2h` and restarting changes them; an invalid value (e.g. `30` without a unit) makes the container exit with a clear error; an artificially oversized scrape is rejected by `sample_limit`.

**Task 9: Grafana service + custom image + datasource provisioning.**
Objective: Air-gapped Grafana with both datasources auto-wired. Guidance: custom image baking the ClickHouse plugin at build time; `grafana.ini` as in Section 5; provision Prometheus (`http://prometheus:9090`) and ClickHouse (`http://clickhouse:8123`, user `grafana`, password from env) datasources; `grafana` service on `ulpf-net` (internal-only, `grafana-data` volume, `depends_on` prometheus, healthcheck `wget -q --spider http://localhost:3000/grafana/api/health`); set `GF_SECURITY_ADMIN_PASSWORD` from `GRAFANA_ADMIN_PASSWORD`. Verification: both datasources healthy.

**Task 10: Log Explorer dashboard.**
Objective: Replace the deleted `/v1/analytics/search` + `/timeseries`. Guidance: provision a ClickHouse dashboard with (a) volume histogram over `ulpf_raw.raw_events` using `$__timeFrom()/$__timeTo()`; (b) search table — time-range predicate first, then `hasToken(raw_payload, '$search_term')` (case-sensitive; document this) or a token-delimited `LIKE` alternative, `ORDER BY received_at DESC LIMIT 200`; no `OR match()` clause. Offer a separate, clearly labelled "regex (slow, scans range)" panel if regex is required; (c) error-spike panel using `hasToken` on error tokens. Verify with seeded data and `EXPLAIN indexes = 1` to confirm the skip index is used.

**Task 11: Pipeline Health dashboard.**
Objective: All Micrometer families in one page. Guidance: provision "Pipeline Health & Throughput" with panels for events/sec by vendor/source, queue depth, flush p95, batch size, insert-error rate, drop/degraded/re-enqueued, webhook p95 + success ratio, drift rate, open incidents. Verify with live data.

**Task 12: nginx reverse-proxy wiring for `/grafana/`.**
Objective: Single public entry, admin-only. Guidance (in `frontend/nginx.conf`):
```
map $http_upgrade $connection_upgrade { default upgrade; '' close; }

location = /_grafana_auth {
    internal;
    proxy_pass http://core-engine:8080/v1/auth/validate-grafana;
    proxy_pass_request_body off;
    proxy_set_header Content-Length "";
    proxy_set_header Cookie $http_cookie;
}

location /grafana/ {
    auth_request /_grafana_auth;
    auth_request_set $grafana_user $upstream_http_x_webauth_user;
    auth_request_set $new_cookie   $upstream_http_set_cookie;
    add_header Set-Cookie $new_cookie always;

    proxy_set_header X-WEBAUTH-USER $grafana_user;   # always overwritten; client value never forwarded
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection $connection_upgrade;
    proxy_set_header Host $host;
    proxy_pass http://grafana:3000;                  # NO trailing slash
}
```
Verify `nginx -V` includes `--with-http_auth_request_module`. If the Grafana health check must be reachable by start.sh, either check from inside the network (`docker compose exec`) or add a narrow unauthenticated `location = /grafana/api/health`. Verification: no/invalid cookie → 401; admin cookie → dashboard loads auto-logged-in and stays logged in past the original TTL; WebSocket (Grafana Live) connects.

**Task 12a: Make nginx the vendor ingestion entry point (Q1).**
Objective: vendors send to `:3000/v1/events` with no loss of throughput or reliability compared with hitting `:8080` directly. Today's `/v1/` block was written for UI calls, not ingest traffic: nginx's default `client_max_body_size` is 1 MB (a larger vendor batch gets `413`, but would succeed on `:8080`), and there is no upstream keep-alive (a new TCP connection to core-engine for every event request). Guidance (in `frontend/nginx.conf`):
```
# Keep the client-facing scheme when an outer HTTPS load balancer sits in front of this nginx
map $http_x_forwarded_proto $forwarded_proto {
    default $http_x_forwarded_proto;
    ''      $scheme;
}

upstream backend_service {
    server core-engine:8080;        # service name; host.containers.internal stops working once 8080 is loopback-bound
    keepalive 32;                   # reuse connections to core-engine
}

# Vendor ingestion: separate from /v1/ so ingest limits never affect UI API calls
location = /v1/events {
    client_max_body_size 10m;       # set from the largest expected vendor batch; confirm with vendors
    proxy_http_version 1.1;         # required for upstream keep-alive
    proxy_set_header Connection ""; # required for upstream keep-alive
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $forwarded_proto;
    proxy_read_timeout 30s;
    proxy_pass http://backend_service;
}
```
Also add `proxy_http_version 1.1` + `proxy_set_header Connection ""` to the existing `/v1/` block so UI calls reuse connections too, and switch its `X-Forwarded-Proto` to `$forwarded_proto`. The `/_grafana_auth` location (Task 12) must send `proxy_set_header X-Forwarded-Proto $forwarded_proto;` too, so `validate-grafana` sets `Secure` correctly on refreshed cookies. (A client that fakes `https` over plain HTTP only gets a cookie its own browser drops; no security impact.) `X-API-Key` passes through unchanged (nginx forwards request headers by default). Per-vendor rate limiting (`limit_req` keyed on `$http_x_api_key`) is optional and out of scope unless the team asks for it. Verification: `POST :3000/v1/events` with a valid key → same response as `:8080`; a body just under the limit → accepted; a body over the limit → `413`; with core-engine stopped → `502` (vendors must retry on `502`/`503`); a short load test shows nginx reusing upstream connections (`ss -tn` on core-engine shows a stable, small connection count) and no throughput drop versus `:8080`.

**Task 13: Frontend — Vitest + RTL, then the Grafana launch action.**
Objective: Test the admin-only link per AGENTS.md §4. Step 13a (**approved 2026-10-29**) is its own change, landed before 13b: add Vitest, React Testing Library and jsdom as **exact-pinned dev dependencies** (pick a Vitest release whose peer range supports the repo's Vite `5.4.x`), `vitest.config.js` (jsdom environment), a `"test": "vitest run"` script and one sample test; add `npm test` to the frontend job in `.github/workflows/ci-cd.yml` in the same change, so the tests actually run. Check `npm run build` still passes and the runtime image is unchanged (only the build stage installs dev dependencies). Step 13b: `frontend/src/api/grafana.js`, admin-only action in `Navbar.jsx` (existing Tailwind classes; reads role from localStorage; on click POSTs the session endpoint then opens `/grafana/` in a new tab); both logout paths (`LogoutButton.jsx` and the 401 handler in `client.js`) call `clearGrafanaSession()` fire-and-forget, for role `ADMIN` only; `VITE_GRAFANA_URL` build arg. Tests: `Navbar.test.jsx`, `LogoutButton.test.jsx`, `client.test.js`.

**Task 14: Wire compose.yaml, .env.example, start.sh; full verification.**
Objective: Everything integrated. Guidance: finalize `compose.yaml` (fixed `ulpf-net` subnet, loopback binds per decision 7: `127.0.0.1:8080:8080`, `127.0.0.1:8123:8123`, `127.0.0.1:9000:9000`; core-engine's `CLICKHOUSE_URL` default and `.env.example` move from `host.containers.internal:8123` to `clickhouse:8123`, otherwise core-engine loses ClickHouse once 8123 is loopback-bound; volumes, depends_on, healthchecks); `.env.example` observability section; `start.sh` readiness for Prometheus and Grafana (via the approach in Task 12), banner URL, interactive prompts for new secrets, and a note that existing `.env` files need the new keys appended manually (and an existing `CLICKHOUSE_URL` override must be changed to `clickhouse:8123` by hand). **Vendor cut-over:** before binding `8080` to loopback, confirm every vendor/source already sends to `:3000/v1/events`; if any still use `:8080`, keep both open for an agreed transition window. Verification: run the full backend suite via the repo's sanctioned procedure (AGENTS.md; reconcile with Hard Rule 2 — do not run `mvn` directly if that rule forbids it) and expect at least the 320 baseline plus the new tests; `npm run build`. Demo: `./startup/start.sh` → all services up; admin launches Grafana.

**Task 15: Documentation.**
Objective: Keep docs current (AGENTS.md §6). `ARCHITECTURE.md` (observability subsystem + diagram), `API_SPECIFICATION.md` (`/actuator/prometheus`, `/v1/auth/grafana-session`, `/v1/auth/validate-grafana`, security posture, token purpose), `FILE_GUIDE.md` (`infra/grafana`, `infra/prometheus`), `DATABASE_SCHEMA.md` (read-only grafana user via XML), `COMMON_DB_GUIDE.md` (metrics on `ClickHouseIngestionRepository`, `CredentialRecord.sourceType`), `frontend.md`, `README.md`, and `docs/to-do-list.md` (update the observability item). Set plan Status to Done.

## 7. Risks and open questions

- **Cardinality**: the main driver of memory in both the app and Prometheus. The first draft of the metrics measured 171,568 series at 1,000 sources; the tag policy, explicit buckets, the `ULPF_METRICS_MAX_TAG_VALUES` cap and the Prometheus `sample_limit` bring it to about 1.4k typical and 25k worst case (see **Cardinality and memory budget**). If a deployment onboards more than 5,000 sources, raise the cap and the `sample_limit` together and re-check RAM.
- **Spring Boot 4.1.0 artifacts** are not cached locally; resolve online once (`mvn dependency:resolve`) and confirm starter/module names and test auto-config before Task 1 tests. Offline builds afterwards need the populated `~/.m2` or an internal mirror.
- **Existing `.env`** won't auto-merge new keys (`start.sh` seeds only on first creation). Documented in Task 14.
- **ClickHouse XML user** changes take effect on restart/config reload, so existing volumes are fine. Never define the same user in SQL as well.
- **nginx `auth_request` module**: confirm in the nginx image (`nginx -V`). If absent, use a custom nginx image.
- **Cookie refresh plumbing**: `Set-Cookie` from the auth subrequest is dropped unless copied via `auth_request_set` + `add_header`; covered in Task 12 verification.
- **Grafana `auth.proxy` whitelist** requires a stable source address; mitigated by the fixed `ulpf-net` subnet.
- **Grafana ClickHouse plugin version**: pin at build; verify the air-gapped image contains it.
- **Image availability in air-gapped deploy**: `grafana/grafana`, `prom/prometheus` and the existing images must be pre-pulled to the local registry.
- **Regex search** over `raw_payload` cannot use the bloom-filter index; keep it behind a narrow time range.
- **Q1 (decided 2026-10-29):** vendors ingest through nginx `:3000/v1/events`; `8080` becomes internal-only after Task 12a and the vendor cut-over in Task 14.
- **Ingestion depends on the frontend container:** nginx lives in the frontend image, so every frontend redeploy briefly interrupts ingestion (`502` for a few seconds). Acceptable for now if vendors retry on `502`/`503`; a standalone gateway container is the long-term fix (out of scope for this plan).
- **Ingest body limit:** `10m` is a placeholder; set it from the largest real vendor batch before release.
- **Q2 (decided 2026-10-29):** no local registry and no Maven mirror. Images are built and pulled with internet during setup. After that the stack must run with no internet at all.
- **Q3 (decided 2026-10-29):** support both HTTP and HTTPS; ULPF is installed by others and we can't know which they use. Cookie `Secure` is decided per request from `X-Forwarded-Proto` (`ULPF_COOKIE_SECURE=auto|true|false`, default `auto`); Grafana `root_url` comes from `ULPF_PUBLIC_URL`. Who provides the TLS certificate (customer load balancer, or certs mounted into nginx) is a general deployment topic for the README, not part of this plan.

## 8. Outcome

(filled in at close: deviations, final test counts, follow-ups)
