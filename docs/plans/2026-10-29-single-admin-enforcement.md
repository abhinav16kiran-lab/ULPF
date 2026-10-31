# Plan: Single-admin enforcement (remove stale ADMIN accounts, protect ADMIN from role changes)

- **Date:** 2026-10-29
- **Status:** Approved (amended 2026-10-31 by the team lead: stale admins are **deleted**, not demoted; password-reset case made explicit; see §2 and §3)
- **Owner:** Adarsh
- **Approved by / on:** Adarsh (team lead), 2026-10-31
- **File name:** `docs/plans/2026-10-29-single-admin-enforcement.md`
- **Blocks:** backlog item 7 (Prometheus + Grafana). Grafana access is granted to `ADMIN` only, so `ADMIN` must reliably mean "the one account defined in `.env`". This plan lands first.

## 1. Goal

ULPF supports exactly one admin, defined by `ULPF_ADMIN_USERNAME` / `ULPF_ADMIN_PASSWORD`. Today nothing enforces that. "Done" means: after every startup the database holds exactly one `ADMIN` row, the configured one; a replaced admin's account no longer exists (not even as a `USER`); no runtime flow can change an `ADMIN`'s role, and the admin account cannot file vendor onboarding requests (the UI already prevents it; the backend will too).

## 2. What I verified

Problem A is read from the code only. Problem B was reproduced on 2026-10-29 with throwaway tests (deleted afterwards; details under Problem B). The red tests in §4 are the permanent reproductions.

**Problem A: a stale admin survives a username change**
- `AdminUserSeeder.run()` (`AdminUserSeeder.java:45-72`) looks up only the configured username, creates it or refreshes its password and role, and never looks at other rows. Change `ULPF_ADMIN_USERNAME` from `alice` to `bob` and restart: `bob` is created, `alice` stays `ADMIN` with her old password. Changing only the case (`admin` → `Admin`) does the same, because SQLite compares usernames case-sensitively.
- Consequences: the old credentials keep working as admin; `AlertDispatcher` sends system alerts to every `ADMIN` row (`AlertDispatcher.java:401`), so the stale account keeps receiving them.

**Problem B: onboarding approval can turn the admin into a VENDOR**
- `OnboardingService.processAdminDecision` sets the request owner to `VENDOR` with no check of their current role (`OnboardingService.java:382`). The login path has the right guard, `role() == USER` (`AuthService.java:61`); this one does not.
- `POST /v1/onboard/{username}` only requires a valid login and a matching username (`OnboardingController.java:42-47`; `SpringSecurity` restricts only `/v1/admin/**` and `/v1/analytics/**` to ADMIN). So an admin token can file a request for its own account, and the admin then approves it via `/v1/admin/**` (`AdminController`, ADMIN only).
  - **Reproduced over real HTTP** (real security chain, real JWT, no mocks): logging in with the admin credentials and the VENDOR tab returns role `ADMIN`, so the UI sends the admin to `/admin`. `POST /v1/onboard/admin` with that admin token and a sample log file then returned **201** and created a vendor, a source and an API key. Controls in the same run: no token → 403; admin token filing for another user's account → 403; a USER token on `/v1/admin/stats` → 403.
  - The UI does hide the page (`Navbar.jsx:38` hides the link, `OnboardingPage.jsx:41-42` redirects ADMIN to `/admin`), but that is client-side only; the backend has no role check on these endpoints.
- The documented API does not forbid it: `docs/ARCH/API_SPECIFICATION.md:22-23` lists `/v1/onboard/{username}` and `/v1/onboard/update` as `VENDOR`/`ADMIN`. That table omits `USER` (who must be able to call them: a new user's first request is how they become a vendor), and the update path lacks `{username}` (`OnboardingController.java:81`). So the door rule in §3 is a deliberate API change, not a restoration of documented behaviour.
- Result: the admin's stored role becomes `VENDOR`; new tokens carry `VENDOR` (`AuthService.java:77`) until the next restart, when the seeder repairs it (`AdminUserSeeder.java:67-72`, "role != ADMIN"). Reproduced at service level with the real SQLite schema and repositories: after approval the ADMIN user's role was `VENDOR` (`expected: <ADMIN> but was: <VENDOR>`). The approval over HTTP could not complete in my environment (409, no ClickHouse running), so the full flip was not observed end to end over HTTP.

**Supporting facts**
- Public signup always creates `USER` (`AuthService.java:109-110`); the seeder is the only code that creates or restores `ADMIN`.
- Role is checked from the token for up to 24 h: `JwtUtil.java:40` (expiry), `JwtFilter.java:75,87` (authorities built from the token's role claim).
- Other tables reference `users`: `vendors.owner_user_id`, `onboarding_requests.user_id`, `notifications.user_id` (`sqlite/schema.sql:21,66,78`), so deleting a user is risky. `users.role` allows `USER` (`sqlite/schema.sql:10`).
- `UserRepository` has `findUsersByRole` (`:74`) and `updateUserRole` (`:84`) but no delete; this plan adds the delete SQL. No schema change.
- **Foreign keys are enforced** (`?foreign_keys=on` in `application.yaml:7`, `PRAGMA foreign_keys = ON` in `sqlite/schema.sql`) and no `REFERENCES users` has `ON DELETE CASCADE`. So deleting a user fails while any `notifications`, `onboarding_requests` or `vendors` row still points at it; those rows must be handled first, in one transaction (`SqliteConnectionConfig` already defines a transaction manager).
- **Admin webhooks are not stored on the admin account.** The admin's alert webhook is the installation-wide `ULPF_ALERT_WEBHOOK_URL` / `ULPF_ALERT_WEBHOOK_SECRET` in `.env` (`application.yaml:22-23`, read by `AlertDispatcher`); the only webhooks in SQLite are `vendor_webhooks`, keyed by vendor. Replacing the admin account therefore leaves no admin webhook behind in the database; whoever edits `.env` for the new admin sets the webhook in the same file. `webhook_delivery_logs` rows for the global webhook (`vendor_id` NULL) are an audit trail and are kept.
- **Password reset with the same username already works:** when the configured user exists, the seeder compares the `.env` password with the stored hash and, if they differ, re-hashes and saves it (`AdminUserSeeder.java:67-70`, `updatePasswordAndRole`). No new code is needed for that case; this plan adds a test so it stays that way.
- No `AdminUserSeederTest` exists. `UserRepositoryTest` shows the in-memory SQLite pattern to copy. `OnboardingServiceTest` already approves requests end to end.
- Docs to touch: `README.md:178` (describes the seeder), `.env.example:9-10`, the seeder's class Javadoc.

## 3. Proposed approach

**Principle:** `.env` is the only source of admins; the seeder is the only code that may create, restore or remove `ADMIN`.

1. **Startup rule (fixes A), amended 2026-10-31.** After the seeder has created or confirmed the configured admin, it **deletes** every other `ADMIN` account and logs a `WARN` naming each one. In one transaction per account: delete that user's `notifications` rows, then the `users` row. Sketch:
   ```java
   private void removeOtherAdmins(String keepUserId, String keepUsername) {
       for (User u : userRepository.findUsersByRole(Role.ADMIN)) {
           if (u.userId().equals(keepUserId)) continue;
           if (userRepository.ownsVendorData(u.userId())) {          // vendors or onboarding_requests point at it
               userRepository.lockAndDemote(u.userId());             // role USER + unusable password hash
               log.warn("Stale ADMIN '{}' owns vendor data; locked and demoted instead of deleted. Only '{}' may be ADMIN.", u.username(), keepUsername);
           } else {
               userRepository.deleteUserWithNotifications(u.userId());
               log.warn("Removed stale ADMIN '{}'; only '{}' (ULPF_ADMIN_USERNAME) may be ADMIN.", u.username(), keepUsername);
           }
       }
   }
   ```
   - **Ensure first, remove last.** There is never a moment with zero admins, and a crash in between is repaired on the next start (the step is idempotent).
   - **Blank config removes nobody.** The seeder already returns early when username or password is blank (`:45-48`); that path stays untouched, so a missing variable cannot lock everyone out.
   - **The rare exception: the old admin owns vendor data.** This can only happen if the account was a vendor before it was made admin, or through Problem B before this fix. Deleting it would require deleting vendors, sources, API keys, mappings and vendor webhooks that belong to a real vendor, so the seeder never does that. Instead it **locks** the account: role `USER` and a password hash nobody can match (a fresh BCrypt hash of random bytes), so it can no longer log in, and the `WARN` says what it owns so a person can decide. This is the only case where a replaced admin's row survives.
   - **Same username, new password (password reset).** Unchanged behaviour: edit `ULPF_ADMIN_PASSWORD` in `.env` and restart; the seeder updates the stored hash, the account stays `ADMIN` and nothing is removed. Login tokens issued before the reset stay valid until they expire (up to 24 h, §7 item 4); Grafana sessions end at once (the Grafana plan binds them to a password fingerprint).
   - **Webhooks:** nothing to remove (see §2: the admin webhook lives in `.env`, not on the account).
2. **Runtime rules (fix B).**
   - **At the door (primary).** In `SpringSecurity.filterChain`, after the `/v1/admin/**` rule and before `anyRequest().authenticated()`, add `.requestMatchers("/v1/onboard/**").hasAnyRole("USER", "VENDOR")`. An `ADMIN` token then gets 403 on every onboarding endpoint (submit, update, withdraw, my-requests, my-sources). `JwtFilter` grants `ROLE_<role>` straight from the token, so `USER` and `VENDOR` pass and `ADMIN` does not. The approval endpoint lives under `/v1/admin/onboard/**`, which this matcher does not touch.
   - **Backup (defence in depth).** In `OnboardingService`, promote to `VENDOR` only when the owner's current role is `USER`, mirroring `AuthService.java:61`. An `ADMIN` (or an existing `VENDOR`) is left alone even if some future caller reaches this code another way.

**Why delete (team lead, 2026-10-31):** a replaced admin should not keep any account at all; a leftover `USER` with known credentials is still a login nobody is responsible for. Cost: not reversible. Putting the old username back in `.env` creates a fresh admin with the `.env` password, but the old account's in-app notifications are gone. The original draft demoted to `USER` to keep the row; that is now used only for the vendor-data exception above.

**Alternatives rejected:**
- *Demote to `USER` (original draft):* leaves an account with known credentials behind; rejected by the team lead. Kept only as the locked fallback when the account owns vendor data.
- *Cascade-delete everything the old admin owns:* could destroy a real vendor's data; rejected.
- *Partial unique index "one ADMIN row" in SQLite:* good extra protection, but "ensure first, remove last" would violate it momentarily, and an existing database with two admins would fail to start. Possible later hardening.
- *Multiple admins:* out of scope; the product supports one admin credential today.

## 4. Test plan (written first)

New `AdminUserSeederTest` (`controlplane/service/`), real in-memory SQLite plus real BCrypt, same setup as `UserRepositoryTest`:

| Test | Class | Expected to fail before because… |
|---|---|---|
| username changed → previous admin's `users` row and its `notifications` rows are gone; new one is `ADMIN` | `AdminUserSeederTest` | old row stays `ADMIN` |
| after seeding, exactly one `ADMIN` row exists and it is the configured one | `AdminUserSeederTest` | two `ADMIN` rows |
| two earlier stale admins → both removed | `AdminUserSeederTest` | both stay `ADMIN` |
| stale admin that owns a vendor (and an onboarding request) → row kept with role `USER`, its password no longer matches anything (old password fails `matches`), vendor, source and API key untouched, `WARN` logged | `AdminUserSeederTest` | stays `ADMIN` with a working password |
| same username, new password → stored hash now matches the new password, old password fails, role still `ADMIN`, user id unchanged, nothing removed | `AdminUserSeederTest` | passes before (guard for the password-reset path) |
| configured name already exists as `USER` → promoted to `ADMIN` and the old admin removed | `AdminUserSeederTest` | old admin stays `ADMIN` |
| unchanged config, run twice → no role changes (idempotent) | `AdminUserSeederTest` | passes before (regression guard) |
| blank username or password → nobody removed or changed | `AdminUserSeederTest` | passes before (guards the early return) |
| `USER` and `VENDOR` rows are never touched | `AdminUserSeederTest` | passes before (guard) |
| approving a request owned by an `ADMIN` leaves that user `ADMIN` | `OnboardingServiceTest` (extend) | `OnboardingService.java:382` sets `VENDOR` unconditionally |
| approving a request owned by a `USER` still promotes to `VENDOR` | `OnboardingServiceTest` (extend) | passes before (guard) |
| `ADMIN` token gets 403 on `POST /v1/onboard/{adminUsername}`, `POST /v1/onboard/update/{u}`, `POST /v1/onboard/withdraw/{id}`, `GET /v1/onboard/my-requests`, `GET /v1/onboard/my-sources` | `OnboardingSecurityTest` (new) | today the same call returns 201 (reproduced 2026-10-29): no role check |
| `USER` and `VENDOR` tokens are not rejected by the new rule on the same endpoints (anything but 403); no token is still rejected | `OnboardingSecurityTest` (new) | passes before (guard) |
| `ADMIN` token on `PUT /v1/admin/onboard/{unknown-id}` → 404, not 403 (the new rule does not catch the approval endpoint) | `OnboardingSecurityTest` (new) | passes before (guard) |

`OnboardingSecurityTest` is a `@SpringBootTest` (`test` profile) using `MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity())`, with real logins and real JWTs. The pattern was proven by the 2026-10-29 reproduction (note: in this Spring version the multipart builder type is `MockMultipartHttpServletRequestBuilder`).

Expected red tests: 7 (5 seeder rows: rename, exactly-one, two stale, vendor-owning stale, existing-USER promotion; the service-guard row; the ADMIN → 403 row). The rest must already be green and stay green. `UserRepositoryTest` also gets tests for the three new repository methods (delete with notifications in one transaction, ownership check, lock).

## 5. Files to change

- `core-engine/src/main/java/com/ulpf/controlplane/service/AdminUserSeeder.java`: `removeOtherAdmins` after create/update/up-to-date branches; update the class Javadoc.
- `core-engine/src/main/java/com/ulpf/common/db/UserRepository.java`: `deleteUserWithNotifications(userId)` (transactional), `ownsVendorData(userId)` (any `vendors.owner_user_id` or `onboarding_requests.user_id` row), `lockAndDemote(userId)`; Javadoc for each.
- `core-engine/src/test/java/com/ulpf/common/db/UserRepositoryTest.java`: tests for the three methods.
- `core-engine/src/main/java/com/ulpf/controlplane/service/OnboardingService.java`: role guard at line 382.
- `core-engine/src/main/java/com/ulpf/common/SpringSecurity.java`: one `requestMatchers("/v1/onboard/**").hasAnyRole("USER", "VENDOR")` rule.
- `core-engine/src/test/java/com/ulpf/controlplane/service/AdminUserSeederTest.java`: new.
- `core-engine/src/test/java/com/ulpf/controlplane/controller/OnboardingSecurityTest.java`: new (real security chain).
- `core-engine/src/test/java/com/ulpf/controlplane/service/OnboardingServiceTest.java`: two tests added.
- `docs/ARCH/API_SPECIFICATION.md` (lines 22-23): onboarding endpoints are for `USER`/`VENDOR`, not `ADMIN`; fix the update path to `/v1/onboard/update/{username}`.
- `README.md` (line 178): the seeder now also removes any other admin (locks it instead if it owns vendor data); password reset = change `ULPF_ADMIN_PASSWORD` and restart; `.env.example`: one-line comment above `ULPF_ADMIN_USERNAME`; `to-do-list.md`: item 11 status.

## 6. Steps

- [ ] 1. Write the tests above; run them; confirm the seven expected failures fail for the stated reason and the guard tests pass
- [ ] 2. Repository methods (delete with notifications, ownership check, lock), then seeder: remove other admins after the configured admin is ensured
- [ ] 3. `SpringSecurity`: `/v1/onboard/**` requires `USER` or `VENDOR`
- [ ] 4. `OnboardingService`: promote to `VENDOR` only from `USER`
- [ ] 5. Refactor; keep class Javadoc accurate
- [ ] 6. Full suite: `mvn clean test -DJWT_SECRET="test-secret-key-1234567890"` (baseline 320, 0 failures) plus the new tests
- [ ] 7. Docs (`API_SPECIFICATION.md`, README, `.env.example`, `to-do-list.md`); set Status to Done

## 7. Risks and open questions

1. **Problem B is part of this plan** (you asked on 2026-10-29 for the door rule to be added). It can still be split out before approval; Problem A does not depend on it. **Door rule confirmed (2026-10-31, team lead):** onboarding endpoints are for `USER` (first sample submission) and `VENDOR` (more sources) only. `API_SPECIFICATION.md:22-23` listing `VENDOR`/`ADMIN` is wrong and is corrected as part of this change (§5). If the team ever wants the admin to onboard test sources, drop the door rule and keep the service guard: the guard alone already stops the role flip. No admin screen calls `/v1/onboard/**` today (only `OnboardingPage.jsx` does, and it redirects `ADMIN` away), so the rule only affects direct API calls with an admin token.
2. **Removal on upgrade.** A database that already has two admins (from earlier username changes) will delete the older ones (or lock them if they own vendor data) on the first start after this ships, with a `WARN`. That is the intended outcome; mention it in the release note.
3. **Typo in `ULPF_ADMIN_USERNAME`** creates a new admin and **deletes** the old one. The new account works with the `.env` password; restoring the old name creates a fresh admin under it, but the old account's notifications are lost (deletion is not reversible). A blank password or username changes nobody. Document in `.env.example` next to the variable.
4. **Not covered: tokens already issued.** A removed admin's login token (or the admin's token from before a password reset) still works as admin for up to 24 h (§2). Closing that means `JwtFilter` checking the user's current role through a short-lived cache. The Grafana plan already needs the same cached user lookup for `validate-grafana`, so it is best built once and reused. Proposed as a separate backlog item, not part of this plan.
5. **Multi-node:** every node runs the seeder at startup; the steps are idempotent, so concurrent runs end in the same state.
6. **Overlap with the Grafana plan:** its Task 1 also edits `SpringSecurity.java` (a new `@Order(1)` actuator chain). This plan lands first, so Task 1 builds on top of the onboarding rule.
7. **Not audited:** endpoints outside `/v1/admin/**` and `/v1/analytics/**` may also rely on UI-only gating. Only `/v1/onboard/**` was checked here; a full endpoint-by-endpoint role audit is a candidate follow-up.

## 8. Outcome (fill in when closing)

Deviations from the plan, final test counts, follow-ups.
