# AGENTS.md — ULPF (Universal Log Processing Framework)

Instructions for any AI coding agent (Claude, Gemini, open-weight models, etc.) working in this repo.
Humans: this is also a fair summary of how we work.

## 1. Working agreement (read first)

1. **Plan before you build.** For anything beyond a trivial fix, write a plan, show it to the user, and wait for approval before touching code. See [§3](#3-plan-first-workflow).
2. **Test first.** Write a failing test, then the code that makes it pass. See [§4](#4-test-driven-development).
3. **Be honest.** If the user's idea is flawed, or a bug report's stated cause is wrong, say so and explain why. Verify claims in bug reports before trusting them.
4. **Think long term.** Prefer maintainable, evolvable code over the quickest patch.
5. **Don't guess; check.** Verify file names, method names and commands exist before relying on them. Docs in this repo can lag the code.

## 2. Project at a glance

ULPF ingests logs from vendors (Syslog, CEF, LEEF, JSON), maps vendor fields onto a canonical schema, stores them in ClickHouse, and keeps a tamper-evident Merkle-tree audit ledger.

| Part | Stack | Location |
|---|---|---|
| Core engine | Java 21, Spring Boot 4.1, Maven | `core-engine/` |
| Control-plane DB | SQLite (`schema.sql` in `src/main/resources/sqlite/`) | runtime: `core-engine/data/` |
| Data-plane DB | ClickHouse | `infra/clickhouse-init/` |
| Frontend | React 18 + Vite + Tailwind | `frontend/` |
| Orchestration | Podman/Docker compose | `compose.yaml`, `startup/` |

Ports: core engine `8080`, frontend `3000` (nginx), ClickHouse `8123` (HTTP) / `9000` (native).

### Backend packages (`core-engine/src/main/java/com/ulpf/`)

| Package | Owns |
|---|---|
| `controlplane` | Auth (JWT; roles `ADMIN`, `VENDOR`, `USER`), onboarding workflow, vendor webhooks, notifications |
| `dataplane` | Event ingestion (`POST /v1/events`, `X-API-Key`), protocol autodetect (`format/`), schema-drift and ingestion-failure alerts, `AlertDispatcher` |
| `mapping` | 4-layer field mapping (alias → TF-IDF → typo/Levenshtein → ONNX embeddings), confidence scoring, human feedback loop, dynamic ClickHouse table provisioning |
| `replay` | Async historical replay/backfill from `ulpf_raw.raw_events` into per-source tables |
| `integrity` | Merkle-tree hashing, chained blocks, bulk forensic audit |
| `analytics` | ClickHouse query execution, table catalog, column discovery |
| `common` | DB access (`common/db/`, incl. `ClickHouseIngestionRepository`), JWT filter, tracing (MDC), global exception handler |

### Core flow

```
vendor → POST /v1/events → autodetect format → in-memory queue → ClickHouse async micro-batch (raw)
                                                      └→ MappingTransformer → events_{vendor}_{source} (+ raw_unmapped overflow)
                                                      └→ out-of-band Merkle hashing → SQLite batch_integrity_blocks
drift / failures → TableIngestionFailureNotificationService / SchemaDriftNotificationService → AlertDispatcher → in-app + signed vendor webhooks
approved onboarding → replay worker backfills history through the same MappingTransformer
```

## 3. Plan-first workflow

Do **not** start implementing straight away. For every feature, refactor or non-trivial bug fix:

1. **Investigate** the relevant code and docs. Read only what you need; start with the Javadoc summary at the top of each file and `docs/ARCH/`.
2. **Write the plan** to `docs/plans/YYYY-MM-DD-<short-slug>.md` using [`docs/plans/TEMPLATE.md`](docs/plans/TEMPLATE.md). It must include: goal, what you verified, proposed approach, files to change, the tests you will write first, risks, and open questions.
3. **Show the user** the plan (summarize it in chat and point at the file). **Stop and wait for approval.** Approval of one plan does not cover a different or expanded scope; if scope changes, update the plan and ask again.
4. **Implement** following [§4](#4-test-driven-development). Keep the plan file current: tick off steps and record any deviations.
5. **Close out**: set the plan's `Status` to `Done` (or `Abandoned`, with a reason), and update affected docs (see [§6](#6-documentation)).

Skip the plan only for trivial edits (typo, comment, a one-line obvious fix). When unsure whether something is trivial, write the plan.

Bug reports: first reproduce/verify the bug and identify the real cause (treat any cause or fix suggested in the report as a hypothesis), write the plan, get approval, then fix test-first.

## 4. Test-driven development

All new behaviour and every bug fix follows red → green → refactor:

1. **Red**: write a test that captures the desired behaviour (for a bug: one that reproduces it). Run it and **confirm it fails for the right reason**. Do not skip this; a test that never failed proves nothing.
2. **Green**: write the minimum code to pass.
3. **Refactor**: clean up with the tests green.
4. Run the **full** suite before declaring done, not just the new test.

Rules:
- Never weaken, delete or skip an existing test to get green. If a test is wrong, say why and fix it deliberately.
- Don't mock what you own when a real in-memory/SQLite instance is cheap; mock external boundaries (ClickHouse, HTTP, the embedding model).
- If something is hard to test, treat that as a design smell and raise it in the plan.
- Report test results faithfully: counts, failures, and anything skipped.

### Backend (JUnit 5 + Mockito, `spring-boot-starter-test`)
- Tests live in `core-engine/src/test/java/` and **mirror the main package**: `X.java` → `XTest.java`.
- Prefer plain unit tests; use `@SpringBootTest`/`@WebMvcTest` only when wiring is the thing under test (a few exist, e.g. `MappingLearningIntegrationTest`).
- Baseline when this file was written: **320 tests, 0 failures** (~30 s).

### Frontend
- **There is currently no test framework** in `frontend/` (no Vitest/Jest, no linter). Today the only check is `npm run build`.
- For frontend work under TDD, the first step of the plan must be to propose adding Vitest + React Testing Library (get approval, then do it as its own change). Don't silently add dependencies.

## 5. Commands

Backend (run from `core-engine/`):
```bash
mvn clean test -DJWT_SECRET="test-secret-key-1234567890"        # full suite (same as CI)
mvn -o test -DJWT_SECRET="test-secret-key-1234567890" -Dtest=AliasLookupServiceTest   # one class (-o = offline)
mvn -DskipTests package                                          # build jar
```
`JWT_SECRET` is required for tests to boot. Target is Java 21 (CI uses Temurin 21).

Frontend (run from `frontend/`):
```bash
npm ci
npm run dev       # Vite dev server
npm run build     # production build; currently the only automated frontend check
```

Whole platform (repo root):
```bash
./startup/start.sh [--no-cache]    # creates .env from .env.example if missing, builds + starts containers
./startup/stop.sh
```
Config comes from `.env` (see `.env.example`). `.env`, `*.db`, `core-engine/data/` and `core-engine/storage/` are git-ignored: never commit secrets or runtime data.

CI (`.github/workflows/`) runs backend tests and the frontend build on push/PR; it ignores changes to `*.md` and `docs/**`.

## 6. Documentation

- `docs/ARCH/ARCHITECTURE.md` — system design; `MAPPING_ENGINE.md`, `API_SPECIFICATION.md`, `DISTRIBUTED_TRACING_ARCHITECTURE.md`, `SCALABILITY_AND_CLUSTER_GUIDE.md`
- `docs/ARCH/FILE_GUIDE.md` — directory map (hand-written; may be stale, trust the filesystem)
- `docs/db/` — SQLite/ClickHouse schemas, DB package guide
- `docs/frontend/frontend.md` — frontend architecture
- `docs/plans/` — approved implementation plans (see [§3](#3-plan-first-workflow))
- `to-do-list.md` — backlog

When you change behaviour, endpoints, schema or config, update the matching doc in the same change. If you find a doc that contradicts the code, fix it or flag it; don't repeat the wrong claim.

## 7. Conventions

- Every main Java class has a Javadoc summary above the class declaration (what it is, how it fits). Keep it accurate when you change the class; write one for new classes.
- Follow the style of the surrounding code (naming, comment density, idioms). Don't reformat unrelated code.
- Keep changes scoped to the approved plan. Note unrelated problems you spot instead of fixing them in passing.
- Security-sensitive areas (JWT filter, API-key handling, webhook SSRF checks and HMAC signing, Merkle integrity) get extra scrutiny and explicit tests for failure and abuse cases.
- Large files are expensive to read: search first (`grep`), then read the relevant range rather than the whole file.
- Commits/PRs: only when the user asks. Don't push or open PRs unprompted.

## 8. Known quirks

- `dev-tools/` and `GEMINI.md` are listed in `.gitignore` (the former is still referenced by docs).
- Local JDK may be newer than 21 (tests passed on JDK 25), but write code that compiles on 21.
- `core-engine/src/main/resources/` contains both `schema.sql` and `sqlite/schema.sql`; check which one is actually loaded before editing either.
