# Plan: The vendor creates the API key after approval

- **Date:** 2026-11-12
- **Status:** Done (2026-11-12). Backend 400 tests, frontend 30 tests, all green; end-to-end run passed.
- **Approved by / on:** Adarsh (team lead), 2026-11-12
- **File name:** `docs/plans/2026-11-12-api-key-after-approval.md`
- **Backlog:** item 16 in `to-do-list.md`. Decisions from a Q&A with the team lead on 2026-11-12 (Q1 to Q10).

## 1. Goal

Today the submit request creates an API key, runs the mapping engine, and returns a key that does not work until an admin approves. After this change, no key exists before approval. When the request is approved, the vendor clicks **Create API key** on the source, and ULPF shows the key once. The same button later regenerates the key and stops the old key at once. All code, tests, schema values and docs that only served the old flow are removed.

Done means:
- Submit returns no key, and creates no `credentials` row.
- Approve activates the source, creates no key, starts no replay, and tells the vendor to create a key.
- The owner of an active source can create a key, and later regenerate it with a confirmation. The raw key appears in one response only.
- Every key creation sends the vendor an in-app notification without the key.
- No reference to a pending key remains in the code, the schema, the tests or the docs.

## 2. What I verified

Current flow (all paths under `core-engine/src/main/java/com/ulpf/`):
- `OnboardingService.submitRequest` (`controlplane/service/OnboardingService.java:176-243`) generates the key at `:205-208` (`ulpf_live_` + 40 hex characters from 2 random UUIDs), saves a `PENDING_APPROVAL` credential at `:210-212`, runs the mapping engine at `:215`, and returns the raw key in `OnboardingSubmissionResult.rawApiKey` (`:156-163`, `:239`).
- `OnboardingController.java:75` puts `apiKey` in the submit response. The frontend shows it once in a copy box (`frontend/src/pages/OnboardingPage.jsx:114`, `:174-176`, `:777-796`).
- A pending key is useless: ingest looks up only `ACTIVE` keys on an `ACTIVE` source of an `ACTIVE` vendor (`common/db/CredentialRepository.java:78-90`, called from `dataplane/service/EventIngestionService.java:106`).
- `processAdminDecision` (`OnboardingService.java:361-440`) activates the credential (`:386`), says "Your API key is now ACTIVE" in the notification (`:396-400`), and starts a replay (`:402-410`). The replay reads `ulpf_raw.raw_events` for the source (`common/db/ClickHouseIngestionRepository.java:542`). Every event sent before approval was rejected, so the replay finds 0 events. It is dead work.
- The replay has one other caller, the admin endpoint `controlplane/controller/ReplayBackfillController.java:29`. It stays.
- `OnboardingService` holds `ReplayBackfillService` only for that trigger: the field (`:64`), the optional constructor parameter (`:136`, `:148`) and a setter used only by a test (`:152-154`).
- Reject (`:418-421`) and withdraw (`:467-470`) call `SourceRepository.revokeSource`, which also sets the source's credentials to `REVOKED`. With this change a pending source has no credentials. `revokeSource` stays, because a revoked source must never keep a working key.
- A schema update (`SCHEMA_UPDATE`) touches no credential. The existing key stays active (`OnboardingPage.jsx:799-803`). No change.
- A lost key cannot be replaced today. No endpoint creates, regenerates or rotates a key.
- **Correction to the Q&A:** in Q6 I said an admin "could still revoke a key". That is wrong. No admin endpoint revokes a key or suspends a source. `CredentialRepository.revokeCredential` (`:125-131`) has no caller at all. See section 7.
- `CredentialRepository.suspendCredential` (`:133-139`) has no caller, and it writes `SUSPENDED`, which the `CHECK` constraint rejects (`main/resources/sqlite/schema.sql:43`). It is dead and broken.
- `credentials.status` allows `PENDING_APPROVAL`, `ACTIVE`, `REVOKED` (`schema.sql:43`). Only `submitRequest` writes `PENDING_APPROVAL` for a credential. The loaded schema is `classpath:sqlite/schema.sql` (`main/resources/application.yaml:12`).
- No index stops 2 `ACTIVE` keys on one source. Only `idx_credentials_key_hash` exists (`schema.sql:97`).
- `JwtFilter` reads the role from the database on every request (`common/JwtFilter.java:91-98`). A `USER` promoted to `VENDOR` at approval can use the button without signing in again.
- `/v1/onboard/**` allows `USER` and `VENDOR` only (`common/SpringSecurity.java:50`). An admin gets `403` there.
- `GET /v1/onboard/my-sources` (`OnboardingController.java:171-175`) returns `SourceRecord`s with no key information. The page lists active sources in a table (`OnboardingPage.jsx:709-745`).
- Notifications are in-app only. ULPF has no email support, and the stack must run air-gapped.
- Tests that cover the old flow: `OnboardingServiceTest.testSubmitOnboardingRequestGeneratesPendingKeyAndFiles` (`:86-108`), `testAdminApproveActivatesKeyAndPushesNotification` (`:110-135`), `testAdminApproveTriggersAutomaticReplay` (`:137-151`), and the `rawApiKey` assertion at `:172`. `EventIngestionServiceDriftTest.java:51` hashes a key with `OnboardingService.hashSha256`. There is no `OnboardingPage.test.jsx`.
- The test fixture `src/test/resources/sqlite/schema-before-admin-accounts.sql` is a snapshot of an old schema for the migrator tests. It keeps `PENDING_APPROVAL` on purpose and does not change.
- Docs that describe the old flow or are wrong:
  - `README.md:210` says approval issues the key, and `:370` says "Approve request & issue API key".
  - `docs/ARCH/API_SPECIFICATION.md:211` shows `apiKey` in the submit response.
  - `docs/ARCH/API_SPECIFICATION.md:41` and `docs/frontend/frontend.md:60` say notifications return API keys. They do not: `NotificationController` has no key code. That claim is wrong today.
  - `docs/db/DATABASE_SCHEMA.md:96` already lists `ACTIVE`, `REVOKED` for credentials. It will match the code after this change.
  - `docs/db/COMMON_DB_GUIDE.md:142-152` describes the `CredentialRepository` methods.
  - `docs/plans/2026-10-28-llm-mapping-agent.md:135` (item 10) says submit returns the API key.

Not verified yet: the current test count. I record it before step 1.

## 3. Proposed approach

### 3.1 The new flows

| Flow | Before | After |
|---|---|---|
| Submit (new vendor or new source) | Creates source (`PENDING_APPROVAL`), key (`PENDING_APPROVAL`), mapping candidate, request. Returns the raw key. | Creates source (`PENDING_APPROVAL`), mapping candidate, request. Returns the request id and the source id. No key. |
| Submit (schema update) | Unchanged. The active key stays. | Unchanged. |
| Approve | Activates mapping, source, key. Promotes a `USER`. Notifies "Your API key is now ACTIVE". Starts a replay. | Activates mapping and source. Promotes a `USER`. Notifies "Approved. Open Onboarding and click Create API key for source X." No key change, no replay. |
| Reject, withdraw | Revokes the source and its pending key. | Revokes the source. No key exists. Code unchanged. |
| Create API key | Not possible. | Owner of an active source with no key: ULPF creates the key, stores the hash, returns the raw key once, and notifies the vendor. |
| Regenerate API key | Not possible. | Owner of an active source with a key, after confirmation: in one transaction ULPF revokes the old key, evicts it from the cache, creates the new key, returns it once, and notifies the vendor. |
| Ingest | Unchanged. | Unchanged. The old key fails at once after a regenerate, because the cache entry is evicted before the commit. |

### 3.2 Backend

New class `controlplane/service/ApiKeyService.java` with one public method:
- `IssuedApiKey issueKey(String username, String sourceId, boolean replaceExisting)`.
- Checks, in this order: the user exists. The source exists and its vendor's owner is this user, otherwise `403`. The source and the vendor are `ACTIVE`, otherwise `409`. If an active key exists and `replaceExisting` is false, `409` with code `KEY_EXISTS`.
- In one `@Transactional` block: revoke the active key of the source (if any), insert the new `ACTIVE` credential, save the notification.
- Key format stays `ulpf_live_` + 40 hex characters. I move the generation from `submitRequest` into this class. The hash stays `OnboardingService.hashSha256`, which `EventIngestionService` and `AdminLinkService` also use.
- The class owns key issuing, so `OnboardingService` (already 650+ lines) does not grow.

New endpoint in `OnboardingController`:
- `POST /v1/onboard/sources/{sourceId}/api-key`, body `{"replaceExisting": false}`.
- `201` with `{"sourceId", "apiKey", "createdAt", "replacedPreviousKey"}`. The response has `Cache-Control: no-store`.
- `403` not the owner, `404` no such source, `409` with `code` `SOURCE_NOT_ACTIVE` or `KEY_EXISTS`.
- The existing rule `/v1/onboard/**` → `USER`, `VENDOR` covers it, so an admin gets `403` (Q6).

`CredentialRepository`:
- New `Optional<CredentialRecord> findActiveBySourceId(String sourceId)`.
- New `void revokeActiveForSource(String sourceId)`: evicts the cache entries of the source first, then sets its `ACTIVE` keys to `REVOKED`.
- Remove `activateCredentialForSource` (only approval used it), `suspendCredential` (dead, writes an invalid status) and `revokeCredential` (no caller; `revokeActiveForSource` replaces it).

`GET /v1/onboard/my-sources`: each source also gets `apiKeyCreatedAt` (the creation time of its active key, or `null`). The frontend uses it to show **Create API key** or **Regenerate**. I add a small response record instead of changing `SourceRecord`, because `SourceRecord` is used across the code.

`OnboardingService`:
- `submitRequest`: remove the key generation and the credential save. Remove `rawApiKey` from `OnboardingSubmissionResult`, and change the result message.
- `processAdminDecision`: remove `activateCredentialForSource`, the replay trigger and the old notification text.
- Remove the `ReplayBackfillService` field, constructor parameter and setter.
- Fix the Javadoc and comments that describe the old flow.

Schema (`main/resources/sqlite/schema.sql`):
- `credentials.status` `CHECK` becomes `('ACTIVE', 'REVOKED')`.
- New `CREATE UNIQUE INDEX IF NOT EXISTS idx_credentials_one_active_per_source ON credentials(source_id) WHERE status = 'ACTIVE';`. It stops 2 active keys on one source, even if 2 clicks arrive together.
- No migration (Q9 A). Existing dev databases must be recreated: stop the stack, run `podman volume rm ulpf_sqlite-data`, start the stack, and run the first-run setup. The PR description gives these steps.

### 3.3 Frontend

- New `frontend/src/api/onboarding.js` with `createApiKey(sourceId, replaceExisting)`, in the same style as `api/admins.js`.
- `OnboardingPage.jsx`:
  - After submit: remove the key box. Show "Request submitted. After an admin approves it, create your API key under Active sources." Keep the schema-update box "Your existing API key remains active".
  - Active sources table: new column **API key**. With no key: a **Create API key** button. With a key: "Created <date>" and a **Regenerate** button.
  - **Regenerate** opens an in-page confirmation: "Your current key stops working now. Systems that use it will be rejected until you update them." I do not use `window.confirm`.
  - The new key appears in the existing copy box, extracted into a small `ApiKeyReveal` component, with the text "Copy it now. You cannot see it again." Closing the box removes the key from the page state.
- New `OnboardingPage.test.jsx` (Vitest and React Testing Library, mocking `api/onboarding.js` as `AdminsPage.test.jsx` mocks its API module).

### 3.4 Docs

- `docs/ARCH/ARCHITECTURE.md`: a new decision row, with the reason and the rejected alternatives:
  - The vendor creates the key after approval.
  - Rejected: a key at submit (B, today), raw events accepted while pending (C), a key created at approval and stored until fetched, and delivery by email, webhook or an admin.
- `docs/ARCH/API_SPECIFICATION.md`: the submit response without `apiKey`, the approve description, the new endpoint, and a fix to the notifications row at `:41`.
- `README.md`: the onboarding steps (`:205-216`) and the endpoint table (`:370`, plus a new row).
- `docs/frontend/frontend.md`: the onboarding page, and a fix to the notifications row at `:60`.
- `docs/db/COMMON_DB_GUIDE.md`: the `CredentialRepository` methods. `docs/db/DATABASE_SCHEMA.md`: the new unique index.
- `docs/plans/2026-10-28-llm-mapping-agent.md:135`: submit returns a request id, not a key.
- `to-do-list.md` item 16: link to this plan, and set the status.
- Local only, after the merge: the walkthrough (`docs/walkthrough/`, git-ignored). Box 2 of the onboarding slide and Journey A step 2 still show a key at submit.

### 3.5 Alternatives rejected (recorded in ARCHITECTURE.md)

- **B, a key at submit:** the vendor holds a key that does not work. It is the current behaviour.
- **C, accept raw events while pending:** anyone who signs up could write to ClickHouse before a human has reviewed them. It needs quotas and cleanup on reject.
- **Create the key at approval and keep it until the vendor fetches it:** a raw key stored at rest. Today only the hash is stored.
- **Email, vendor webhook, or an admin who hands it over:** no email in an air-gapped stack; a webhook sends a secret to an outside URL; an admin would know the vendor's key.
- **A migration for the `CHECK` constraint:** there are no users, so a migration would be code that serves nobody (Q9).

## 4. Test plan (written first)

Backend (JUnit 5). Service and repository tests use real in-memory SQLite repositories, as `OnboardingServiceTest` does today (`:45-69`). Controller tests mock the service with Mockito and use a standalone `MockMvc`, as `OnboardingControllerTest` does today.

| Test | Class | Expected to fail before because… |
|---|---|---|
| Submit creates no credential and the result has no key field | `OnboardingServiceTest` (replaces `testSubmitOnboardingRequestGeneratesPendingKeyAndFiles`) | Submit creates a pending key today |
| Approve activates the source, creates no credential, and the notification says to create a key | `OnboardingServiceTest` (replaces `testAdminApproveActivatesKeyAndPushesNotification`) | Approve activates a key and says "now ACTIVE" |
| Schema update leaves the active key unchanged | `OnboardingServiceTest` (updates the test at `:172`) | `rawApiKey` field still exists |
| Owner creates a key on an active source: returns a `ulpf_live_` key, stores only its hash, ingest lookup finds it | `ApiKeyServiceTest` (new) | No class |
| Another user's source: `403`; unknown source: `404` | `ApiKeyServiceTest` | No class |
| Source `PENDING_APPROVAL` or `REVOKED`, or vendor not active: `409 SOURCE_NOT_ACTIVE` | `ApiKeyServiceTest` | No class |
| Key exists and `replaceExisting` false: `409 KEY_EXISTS`, and the old key still works | `ApiKeyServiceTest` | No class |
| Regenerate: the old key fails ingest lookup at once (cache warmed first), the new key works, only one `ACTIVE` row | `ApiKeyServiceTest` | No class |
| Each issue saves a notification that names the source and does not contain the key | `ApiKeyServiceTest` | No class |
| A second `ACTIVE` row for one source violates the unique index | `CredentialRepositoryTest` | No index |
| `revokeActiveForSource` evicts the cached key before it updates the row | `CredentialRepositoryTest` | No method |
| A `PENDING_APPROVAL` credential violates the `CHECK` constraint | `CredentialRepositoryTest` | The value is still allowed |
| `POST /v1/onboard/sources/{id}/api-key`: `201` with `Cache-Control: no-store`; error codes map to `403`, `404`, `409` | `OnboardingControllerTest` | No endpoint |
| An `ADMIN` token gets `403` on the endpoint | `OnboardingSecurityTest` | No endpoint |
| `my-sources` returns `apiKeyCreatedAt` (`null` with no key) | `OnboardingControllerTest` | Field missing |

Removed with the behaviour it tested: `OnboardingServiceTest.testAdminApproveTriggersAutomaticReplay`. The automatic replay is deleted on purpose (Q5), so the test has nothing left to check. The replay itself stays covered by `ReplayBackfillServiceTest` and `ReplayBackfillControllerTest`.

Frontend (`OnboardingPage.test.jsx`, new):

| Test | Expected to fail before because… |
|---|---|
| After submit, the page shows no key and says to create it after approval | The page shows the key box |
| An active source without a key shows **Create API key**; a click shows the key once in the copy box | No button |
| **Regenerate** asks for confirmation first, and a cancel sends no request | No button |
| Closing the copy box removes the key from the page | No component |

## 5. Files to change

Backend:
- `controlplane/service/ApiKeyService.java` — new.
- `controlplane/service/OnboardingService.java` — submit, approve, the replay dependency, the result record, Javadoc.
- `controlplane/controller/OnboardingController.java` — the new endpoint, the submit response, `my-sources`.
- `common/db/CredentialRepository.java` — new methods, 3 removed methods, Javadoc.
- `main/resources/sqlite/schema.sql` — `CHECK` constraint and unique index.
- Tests: `OnboardingServiceTest`, `ApiKeyServiceTest` (new), `CredentialRepositoryTest`, `OnboardingControllerTest`, `OnboardingSecurityTest`.

Frontend:
- `frontend/src/api/onboarding.js` — new.
- `frontend/src/pages/OnboardingPage.jsx` — submit result, sources table, confirmation, `ApiKeyReveal`.
- `frontend/src/pages/OnboardingPage.test.jsx` — new.

Docs: as listed in section 3.4.

## 6. Steps

- [x] 1. Record the current test counts: backend 379, frontend 26, all green.
- [x] 2. Backend tests first, red for the right reason. Minimal stubs (empty `ApiKeyService`, empty repository methods) made the tests compile, so they failed on behaviour: `404` for the missing endpoint, a key row that still existed, "not implemented". Two tests passed before the change, and they stay as guards: the `ADMIN` security test (the `/v1/onboard/**` rule already refuses admins) and the controller test for `my-sources` (it only checks the pass-through). A service test for `my-sources` was added, and it was red.
- [x] 3. Schema, `CredentialRepository`, `ApiKeyService`, endpoint, `OnboardingService`. Green.
- [x] 4. Frontend tests first (4, red: missing text and buttons), then the UI. Green.
- [x] 5. Repo-wide search for the old names. Only the old-schema test fixture, dated plans and this plan still mention them.
- [x] 6. Full suites: backend 400 tests, 0 failures (379 + 21 new; 3 old-flow tests replaced, 1 removed). Frontend 30 tests (26 + 4). `npm run build` passes.
- [x] 7. End-to-end run on a throwaway ClickHouse container (port 18123, no named volume) and a temporary SQLite file, so the dev volumes were not touched. Results: submit returned no key; a key before approval got `409 SOURCE_NOT_ACTIVE`; an admin token got `403`; create returned the key with `Cache-Control: no-store`; a second create got `409 KEY_EXISTS`; ingest with the key got `202`; after regenerate the old key got `401` and the new key `202`; 3 notifications, none with a key; SQLite held 1 `ACTIVE` and 1 `REVOKED` hash and no raw key; no replay ran.
- [x] 8. Docs: `ARCHITECTURE.md` §3.1, §3.6 and new §3.10; `API_SPECIFICATION.md`; `README.md`; `frontend.md`; `COMMON_DB_GUIDE.md`; `DATABASE_SCHEMA.md`; item 10 plan; `to-do-list.md` items 14, 16 and new 17; `AGENTS.md` §8.

## 7. Risks and open questions

- **Admins cannot revoke a key** (correction to Q6). Nothing in ULPF lets an admin stop one vendor's key or source today. With this change the vendor can kill a leaked key by regenerating it, but an admin still cannot act alone. The team lead agreed to keep it out of this change: backlog item 17.
- **Dead code found outside the scope** (for item 14, not fixed here):
  - `SourceRepository.suspendSource` and `VendorRepository.suspendVendor`, `activateVendor`, `revokeVendor` are called only by tests.
  - 3 classes use `OnboardingService.hashSha256` for unrelated hashes (`EventIngestionService`, `AdminLinkService`, a test), and `MerkleTreeCalculator.hashSha256` is a second copy of the same function.
  - `docs/db/DATABASE_SCHEMA.md:71` and `:84` list `SUSPENDED` for vendors and sources. Checked: the `CHECK` constraints allow it, so the doc is right.
- **Dev databases must be recreated** (Q9 A). This deletes local accounts and onboarding data on each dev machine. There are no users, so this is accepted.
- **The vendor must keep the key safe.** If the vendor loses it, they regenerate it. Systems that still use the old key are then rejected, so the vendor must update them. The confirmation text says so.
- **The vendor waits for the mapping engine at submit,** as today. Item 10 Phase 4 moves the mapping to the background (Q3).

## 8. Outcome (fill in when closing)

Delivered as planned. Deviations:
- **A cache race, found while writing the tests.** A revoke inside a transaction evicts the key from the cache, but an ingest request on another connection still reads the key as `ACTIVE` until the commit and caches it again. Cache hits refresh the idle timer, so a busy client could keep a revoked key working. `revokeActiveForSource` now also evicts after the commit. A test with 2 real connections reproduces the race. It fails without the fix and passes with it (checked by removing the fix for one run).
- **`ApiKeyException` and a handler in `GlobalExceptionHandler`.** The plan named the codes but not the mechanism. The exception carries the status and the code, and the response is `{"error", "code"}`.
- **`CredentialRepository.evictByCredentialId` removed too.** Its only callers were the 2 dead methods.
- **The stale `src/main/resources/schema.sql` was deleted.** Nothing loads it: the app and the test config both point at `sqlite/schema.sql`. It still allowed `PENDING_APPROVAL` keys, and it lacked newer tables. `AGENTS.md` §8 listed it as a known quirk, and now records the deletion.
- **SQLite constraint errors reach Spring as `UncategorizedSQLException`**, not `DataIntegrityViolationException`. The schema tests expect the common parent, `DataAccessException`. If 2 first-time creates race, the second gets a `500`, and the index keeps the data correct. I accepted this because it needs 2 clicks within milliseconds.
- **Key generation uses `SecureRandom`** (20 bytes, 40 hex characters) instead of 2 UUIDs. The format is unchanged.
- **The frontend imports `errorMessage` from `api/admins.js`.** It is a general helper; moving it was out of scope.
- **Docs that were already wrong:** `README.md` listed `POST /v1/admin/onboard/{id}/approve` and `/reject`, but the endpoint is `PUT /v1/admin/onboard/{id}`. `API_SPECIFICATION.md` showed `apiKey` in the approve response. Both are fixed.

Follow-ups: backlog item 17 (admin can suspend or revoke a source), and the dead-code list in item 14. Every developer must recreate the local SQLite volume once (see the PR).

