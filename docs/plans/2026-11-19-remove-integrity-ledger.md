# Plan: Remove the integrity ledger

- **Date:** 2026-11-19
- **Status:** In progress
- **Approved by / on:** team lead, 2026-11-19 (new branch; commit the docs first; drop the old table by hand; measure the gain after the removal, as the first step of Phase 1)
- **File name:** `docs/plans/2026-11-19-remove-integrity-ledger.md`

## 1. Goal

Remove the Merkle integrity ledger completely: the backend package, its hook in the raw flush, the SQLite table, the UI page and every link to it, its tests, and the docs that describe it. "Done" means:

- No code, test, route, table or current doc refers to the ledger.
- The full backend and frontend suites pass.
- The stack ingests events as before.
- `docs/ARCH/ARCHITECTURE.md` records why we removed the ledger and which alternatives we rejected.

**Why we remove it** (the team lead's decision, 2026-11-19, after the code review in `docs/ARCH/INTEGRITY_LEDGER.md`):

1. **It does not protect what it claims to protect.** The blocks are in SQLite, and the events are in ClickHouse. Both are in the same stack. A person who can change ClickHouse can usually change SQLite too. Deleted blocks, missing blocks and SQLite edits go unnoticed, because nothing checks the `previous_block_hash` chain.
2. **It makes ingestion slower.** It runs inside the `synchronized` raw flush. It does two SQLite lookups per block that read every earlier block of the source. It was 30% of the time inside the lock, and it made the knee fall from 400 to 300 ev/s as the ledger grew (`docs/perf/RESULTS.md`, Result 2).
3. **It adds code and likely bugs.** By reading the code, verification can report `TAMPERED_DETECTED` for data that nobody changed (millisecond ordering, overlapping windows, short bulk-import IDs).
4. **No requirement needs it.** The SIH problem statement asks to preserve the raw data (a) and to keep traceability between normalized and original events (d). `ulpf_raw.raw_events` and `lineage_id` do this. The ledger is not part of either.

## 2. What I verified

All by reading the code at `7f843a5` on 2026-11-19 (a full `grep` for `integrity`, `merkle`, `ledger`, `tamper`, `blockchain`).

**Backend: the whole feature is one package plus one hook.**
- `core-engine/src/main/java/com/ulpf/integrity/`: 4 files, 648 lines (`IntegrityController`, `IntegrityRepository`, `BatchIntegrityService`, `MerkleTreeCalculator`). No other main class imports them, except `ClickHouseIngestionRepository`.
- `ClickHouseIngestionRepository`:
  - field `batchIntegrityService` (`:45`);
  - `@Autowired` setter `setBatchIntegrityService` (`:101-104`);
  - the call after a good insert (`:292-299`);
  - the call in the "already committed" recovery branch (`:319-325`);
  - the metric description that mentions the integrity block (`:87`).
- The recovery probe (`:304-316`) must stay. It stops a duplicate insert when ClickHouse wrote the batch but the client timed out. Only the integrity call inside it goes.
- No property, compose setting or security rule refers to the ledger. Spring security uses the class-level `@PreAuthorize` on `IntegrityController`, which goes with the class.
- `schema.sql:176-188`: table `batch_integrity_blocks` and index `idx_integrity_source_created`. `schema.sql` uses `CREATE TABLE IF NOT EXISTS`, so deleting these lines does not drop the table from a database that already has it. `SqliteSchemaMigrator` has no drop step.
- The code never sends a notification with "tamper" or "merkle" in it.

**Tests.**
- `core-engine/src/test/java/com/ulpf/integrity/`: 3 classes, 20 tests (`MerkleTreeCalculatorTest` 4, `IntegrityControllerTest` 5, `BatchIntegrityServiceTest` 11). They test only the ledger.
- `ClickHouseIngestionRepositoryTest`: `testFlushRecoversWhenBatchAlreadyCommittedOnTimeout` (`:81`) and `testFlushReEnqueuesWhenGenuinelyFailed` (`:109`) test the recovery probe. They also mock the integrity service and check the call. Their queue assertions stay valid without the ledger.
- `core-engine/src/test/resources/sqlite/schema-before-admin-accounts.sql:170-182` has the table. It is a snapshot of an old database for `SqliteSchemaMigratorTest`. Old databases did have the table.
- No frontend test refers to the ledger (`Navbar.test.jsx` checks only the "Admins" link).

**Frontend.**
- `pages/IntegrityConsolePage.jsx` (347 lines): the page. It imports only `client` and `Navbar`.
- `App.jsx:9` (import) and `:68-75` (route `/integrity`).
- `components/Navbar.jsx:57-59`: the "🔐 Integrity Audit" link.
- `pages/AdminDashboardPage.jsx`: `IconShield` (`:9`, used only at `:295`), state `integrityBlocks` (`:52`), the second request in `Promise.all` (`:107-110`, `:117`) and the "Tamper Audit" button (`:280-296`).
- `pages/AnalyticsPage.jsx:1585-1590`: the "Integrity Check: VERIFIED (SHA-256)" row. It is fixed text. Nothing is checked.
- `pages/NotificationsPage.jsx:445` and `:457`: the words "tamper" and "merkle" in the SECURITY filter and badge. No notification contains them.

**Docs that describe the ledger as current.** `AGENTS.md` (`:18`, `:38`, `:47`, `:121`, `:137`), `README.md` (`:26`, section 5 at `:322-329`, `:401-403`), `docs/ARCH/ARCHITECTURE.md` (`:8`, `:33`, `:46`, `:74`, §3.4 `:76-81`, `:182`), `docs/ARCH/API_SPECIFICATION.md` (`:52-54`, §3.6 `:380-417`), `docs/ARCH/INTEGRITY_LEDGER.md`, `docs/ARCH/FILE_GUIDE.md` (`:38`, `:58`), `docs/db/DATABASE_SCHEMA.md` (`:138-153`, `:256`), `docs/db/COMMON_DB_GUIDE.md:254`, `docs/frontend/frontend.md:61`, `to-do-list.md` (`:241`, `:247`, `:263`, `:277`), `docs/plans/2026-11-13-ingestion-throughput.md` (Phase 1 items 0 and 3, and section 9.1), `docs/perf/RESULTS.md` (Result 2).

**Docs that are dated records.** The plans from 2026-10-28 to 2026-11-15 and `docs/research/2026-11-13-profiling-ingestion.md` describe the code as it was on their date. They stay unchanged.

**Scan for supporting code (2026-11-19).** I looked for code that was added for the ledger but does not have "integrity" or "merkle" in its name. Method: every commit that touched the ledger (`d614a2b`, `10a41d0`, `513ab9f`, `36527ea`, `eaf928f`, `b597032`, `5126655`), and every file that each commit changed outside the ledger. I also checked the imports of the `integrity` package, `pom.xml`, `package.json` and the git-ignored `dev-tools/`.

| Found | Added in | Verdict | Why |
|---|---|---|---|
| Hook, setter, field and metric text in `ClickHouseIngestionRepository`; page, route, nav link, dashboard button and request; table and index | `d614a2b` and later | **Remove** (already in this plan) | Only the ledger uses them |
| The recovery probe in `flush()` (`:304-330`) and its 2 tests | `5126655` (the "false tamper" fix commit) | **Keep** | It was added to stop duplicate raw rows, which caused false tamper results. It also stops duplicate rows in `raw_events` when ClickHouse stores a batch but the client times out. That is a data-quality need without the ledger. |
| The `RawEventRecord` compact constructor that sets `receivedAt` to `now()` when it is null, and `testFlushPreservesOriginalReceivedAtTimestamps` | `5126655` | **Keep** | It keeps the real arrival time of each event. Ingestion, replay and analytics use `received_at`. |
| `getUniqueBatchTime()` (one unique time per batch, for the ledger) | `eaf928f` | Nothing to do | `5126655` already removed it |
| `raw_unmapped` (`EventIngestionService.extractUnmappedFields`, `CanonicalEventRecord.rawUnmapped`) | `d614a2b` | **Keep** | Same commit, but a different feature (lossless overflow of unmapped fields) |
| Block on ClickHouse table functions, drift-alert cooldown, 400 on validation errors | `5126655` | **Keep** | Same commit, but different fixes |
| `dev-tools/load_test.py`, `dev-tools/test_ingest.py` | `513ab9f` | Nothing to do | Git-ignored and local. No reference to the ledger. |
| Libraries | none | Nothing to do | The ledger uses only the JDK (`MessageDigest`). No dependency was added for it. |
| Shared code that only the ledger used | none | Nothing to do | The `integrity` package imports only `RawEventRecord`, which the flush uses. |

Result: the scan found no further code to remove. The two items that look like ledger support (the probe and the `receivedAt` default) protect the raw data, so they stay.

## 3. Proposed approach

Delete. Add nothing in its place.

1. Delete the `integrity` package and its 3 test classes.
2. In `ClickHouseIngestionRepository`, delete the field, the setter and the two calls. Keep the probe and the re-enqueue logic as they are.
3. In the two recovery tests, delete the mock and the `verify` lines. Keep the queue assertions.
4. Delete the table and the index from `schema.sql`.
5. Delete the page, the route, the nav link, the dashboard button and request, the fixed "VERIFIED" row, and the two dead filter words.
6. Update the docs (section 5). Keep `INTEGRITY_LEDGER.md` and mark it as a **removed feature** (see below).

**The ledger doc stays, with the status "Removed".** I chose "Removed", not the other common terms:
- "Deprecated" means that the feature still exists but must not be used. That is false here.
- "Archived" suggests that the feature is stored and can be switched on again. That is also false.
- "Removed (2026-11-19)" says exactly what is true: the code is gone, and the doc is a historical record.

The changes to `INTEGRITY_LEDGER.md`:
- A status line at the top: **Status: Removed on 2026-11-19.**
- A warning before section 1: the feature does not exist in the code, and nothing in the doc describes the current system.
- The line numbers stay. They refer to `main` at `8412f87`, the last merged commit that has the code (`7f843a5` is the same code, but only on a branch). The header shows how to read the old code: `git show 8412f87:<path>`.
- A new section "Why it was removed": the 4 reasons from section 1 and the rejected alternatives from this section. This is the full record of the decision.
- The "Rule for editors" changes: do not update the doc for code changes, because the feature has no code.

`ARCHITECTURE.md` §3.4 keeps its number and becomes a short entry: "Integrity ledger: removed on 2026-11-19", 2 or 3 sentences, and a link to the "Why it was removed" section. The full reasons are in one place only, so the two docs cannot disagree.

**Existing databases.** Deleting the lines from `schema.sql` leaves the old table in a database that already has it. No code reads it, so it does no harm. I propose to drop it once by hand on the team lead's volumes, and to put the one-line command in the PR description for the rest of the team:

```sql
DROP TABLE IF EXISTS batch_integrity_blocks;  -- drops its index too
```

**Alternatives considered and rejected.**

| Alternative | Why rejected |
|---|---|
| Add indexes for the two lookups (Phase 1 item 0) | Fixes the speed only. Keeps the code, the false-alarm bugs and a security claim that is not true. |
| Move the ledger work off the flush path | More code (a second queue or thread). The security claim is still not true. |
| Make the chain real: verify `previous_block_hash`, add a separator to the leaf | The blocks and the events stay in the same trust zone. A person with access to both databases rewrites both. More code for no real protection. |
| Real tamper-evidence: sign roots with a key outside the stack, or write them to append-only storage outside the stack | This is the only design that protects against a database administrator. It is a new feature with key management, and no requirement asks for it. If a requirement comes, it starts from a new plan. |
| Drop the old table with a `DROP TABLE IF EXISTS` line in `schema.sql` or a `SqliteSchemaMigrator` step | It runs on every start, forever, for a table that no code uses. There are no users, so a one-time manual drop is enough. |
| Remove the table from `schema-before-admin-accounts.sql` | That file is a snapshot of an old database, and old databases had the table. Changing it makes the snapshot false. |

**Out of scope.** The recovery probe `SELECT count() ... WHERE event_id = ?` reads the whole table, because `event_id` is not in the sort key. This is a Phase 1 question. The throughput plan keeps it.

## 4. Test plan

This change adds no behaviour, so it has no new red test. The guards are the existing tests:

| Guard | What it proves |
|---|---|
| `ClickHouseIngestionRepositoryTest` (all tests, the two recovery tests changed as in section 3) | The raw flush, the recovery probe and the re-enqueue still work without the ledger |
| Full backend suite | Nothing else depended on the `integrity` package (a missing class fails the compile) |
| `npm test` and `npm run build` | No import or route refers to the deleted page |
| `grep` for `integrity`, `merkle`, `ledger`, `tamper` | Only these still match: the dated records (section 2), `INTEGRITY_LEDGER.md`, the short entry in `ARCHITECTURE.md` §3.4, the `AGENTS.md` docs-list entry, and the old-database snapshot `schema-before-admin-accounts.sql` |
| Stack check: start the stack, send events with `POST /v1/events`, count rows in `raw_events` and in the per-source table | Ingestion works end to end. The engine log has no integrity `WARN` |

Expected counts: backend = baseline − 20, with 0 failures. Frontend = baseline, with 0 failures.

## 5. Files to change

**Delete**
- `core-engine/src/main/java/com/ulpf/integrity/` (4 files)
- `core-engine/src/test/java/com/ulpf/integrity/` (3 files)
- `frontend/src/pages/IntegrityConsolePage.jsx`

**Code**
- `core-engine/src/main/java/com/ulpf/common/db/ClickHouseIngestionRepository.java`: delete the field, the setter, the two calls. Change the metric description to "including the wait for the insert".
- `core-engine/src/test/java/com/ulpf/common/db/ClickHouseIngestionRepositoryTest.java`: delete the integrity mock and `verify` lines in 2 tests.
- `core-engine/src/main/resources/sqlite/schema.sql`: delete section 10. Renumber the later section comments.
- `frontend/src/App.jsx`: delete the import and the route.
- `frontend/src/components/Navbar.jsx`: delete the link.
- `frontend/src/pages/AdminDashboardPage.jsx`: delete `IconShield`, `integrityBlocks`, the second request and the button. The fetch becomes one `client.get("/v1/admin/onboard")`.
- `frontend/src/pages/AnalyticsPage.jsx`: delete the "Integrity Check" row.
- `frontend/src/pages/NotificationsPage.jsx`: delete "tamper" and "merkle" from the 2 keyword checks.

**Docs**
- `docs/ARCH/INTEGRITY_LEDGER.md`: keep. Mark it "Removed" and add the section "Why it was removed" (section 3).
- `docs/ARCH/ARCHITECTURE.md`: delete the ledger from the overview and the diagrams. Make §3.4 the short "removed" entry with a link (section 3). Update the `:74` cost note and the `:182` row (its reason was the ledger).
- `docs/ARCH/API_SPECIFICATION.md`: delete the 3 rows and §3.6. Renumber if needed.
- `docs/db/DATABASE_SCHEMA.md`: delete the table section and the relation line.
- `docs/db/COMMON_DB_GUIDE.md:254`: delete "and the Merkle integrity block".
- `docs/ARCH/FILE_GUIDE.md`: delete the `integrity/` line and "Integrity" from the pages list.
- `docs/frontend/frontend.md`: delete the `/integrity` row.
- `AGENTS.md`: delete the ledger from the summary, the package table, the core flow and the security list. In the docs list, change the `INTEGRITY_LEDGER.md` entry to "removed feature (2026-11-19): why it was removed, and how it worked".
- `README.md`: delete the ledger from the intro, delete section 5 and renumber sections 6-9, delete the 3 API rows. This is a feature removal, not perf work, so the rule "do not edit `README.md` for perf work" does not apply.
- `to-do-list.md`: item 13 (`:241`, `:247`), delete the `hashSha256` copy bullet (`:277`, its cause is gone), change the emoji example (`:263`).
- `docs/plans/2026-11-13-ingestion-throughput.md`: mark Phase 1 items 0 and 3 as replaced by this plan. Update the section 9.1 pointers and the "fresh volumes" note.
- `docs/perf/RESULTS.md`: keep all numbers (they are measurements). Add one dated note under Result 2: the ledger was removed, so the block count no longer affects runs.

**Local, not in git**
- `perf/README.md` (lessons `:202-214`): the same note.
- Memory `throughput-item-13`: Phase 1 items 0 and 3 are replaced.

## 6. Steps

Prerequisite: the open questions in section 7 are answered.

- [x] 1. Record the baseline: full backend suite and `npm test` counts. Backend 430 tests, 0 failures. Frontend 30 tests, 0 failures. Before that, the doc work was committed as `6dc2c4a` (decision 2).
- [x] 2. Delete the backend package and its tests. Change `ClickHouseIngestionRepository` and its test. Run the full backend suite.
- [x] 3. Delete the table from `schema.sql`. Run the full backend suite. 410 tests, 0 failures, 0 skipped (430 − 20).
- [x] 4. Make the frontend changes. Run `npm test` and `npm run build`. 30 tests, 0 failures. The build passes.
- [x] 5. Update the docs. Run the `grep` check. Only the expected matches are left (section 4).
- [x] 6. Run the stack check. Drop the old table on the team lead's volumes. Done on 2026-11-19 (section 8).
- [ ] 7. Show the result and the diff to the team lead. Commit and push only when asked.

## 7. Risks and open questions

**Risks**
- **The feature cannot come back by a revert later without work.** Git keeps the code. A real tamper-evidence design would be different anyway (section 3).
- **Other docs or slides outside this repo can still claim tamper-evidence.** I cannot check them.

**Decisions (team lead, 2026-11-19).** All 4 proposals below were accepted.

**Questions that were open**
1. **Branch.** I propose a new branch `refactor/remove-integrity-ledger` from the current `perf/phase-1-ingestion` head, with its own PR to `main`. Phase 1 then starts from `main` after the merge. The other choice is to do it on `perf/phase-1-ingestion`, but then the removal waits for all of Phase 1.
2. **The uncommitted doc work from today** (`INTEGRITY_LEDGER.md` and the 6 corrected docs). I propose to commit it first as one commit. Git history then keeps the corrected docs as they described the live feature, and the removal commit shows only the removal. The other choice is to put everything in the removal commit.
3. **The old table.** I propose a one-time manual drop (section 3). The other choice is one `DROP TABLE IF EXISTS` line in `schema.sql`.
4. **Measure after?** The removal deletes 30% of the time inside the raw flush lock. One run can give Phase 1 a new baseline. I propose to do this as the first step of Phase 1, not here.

## 8. Outcome (fill in when closing)

**Tests.** Backend: 410 tests, 0 failures, 0 skipped. The 20 deleted tests tested only the ledger. Frontend: 30 tests, 0 failures, and `npm run build` passes.

**Stack check (2026-11-19, rootless Podman, images built from the working tree by `startup/start.sh`).**
- 50 JSON events to `POST /v1/events` for `perf-json` gave 50 × `202`. ClickHouse then held 50 rows in `ulpf_raw.raw_events` and 50 rows in `ulpf_events.events_perf_json`.
- With an admin token, `GET /v1/integrity/blocks` and `POST /v1/integrity/verify-all` return `404`. `GET /v1/admin/onboard` returns `200`.
- The engine log has no line with "integrity" or "merkle".
- The frontend answers `200` on `/`.

**Old table.** The `ulpf_sqlite-data` volume had `batch_integrity_blocks` with 0 rows (emptied on 2026-11-16). I backed up the database file, then ran `DROP TABLE IF EXISTS batch_integrity_blocks;`. The index went with it, and `PRAGMA integrity_check` gave `ok`. After the restart, the table did not come back.

**Deviations from the plan.**
- `API_SPECIFICATION.md`: §3.6 was deleted, and the later sections keep their numbers, because §3.7 was already missing.
- `README.md`: the sections after the deleted §5 were renumbered (6-12 became 5-11).
- `ARCHITECTURE.md`: the decision row "Each comparison starts on fresh volumes" was kept, with a note that its first reason is gone. The rule still keeps the starting state equal, and it was not measured without the ledger.
- `perf/README.md` (local) and `docs/perf/RESULTS.md` got a dated note. All measured numbers are unchanged.

**Follow-ups.**
- Phase 1 starts by measuring the gain against the Phase 0 baseline (`8412f87`).
- Other team members can drop the old table in their own databases with the command in section 3.
