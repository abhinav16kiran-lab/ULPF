# Plan: Single-admin enforcement (demote stale ADMIN accounts, protect ADMIN from role changes)

- **Date:** 2026-10-29
- **Status:** Approved
- **Owner:** Adarsh
- **Approved by / on:** Adarsh (team lead), 2026-10-31
- **File name:** `docs/plans/2026-10-29-single-admin-enforcement.md`
- **Blocks:** backlog item 7 (Prometheus + Grafana). Grafana access is granted to `ADMIN` only, so `ADMIN` must reliably mean "the one account defined in `.env`". This plan lands first.

## 1. Goal

ULPF supports exactly one admin, defined by `ULPF_ADMIN_USERNAME` / `ULPF_ADMIN_PASSWORD`. Today nothing enforces that. "Done" means: after every startup the database holds exactly one `ADMIN` row, the configured one, no runtime flow can change an `ADMIN`'s role, and the admin account cannot file vendor onboarding requests (the UI already prevents it; the backend will too).

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
- The repository already has what is needed: `findUsersByRole` (`UserRepository.java:74`) and `updateUserRole` (`:84`). No new SQL, no schema change.
- No `AdminUserSeederTest` exists. `UserRepositoryTest` shows the in-memory SQLite pattern to copy. `OnboardingServiceTest` already approves requests end to end.
- Docs to touch: `README.md:178` (describes the seeder), `.env.example:9-10`, the seeder's class Javadoc.

## 3. Proposed approach

**Principle:** `.env` is the only source of admins; the seeder is the only code that may create, restore or remove `ADMIN`.

1. **Startup rule (fixes A).** After the seeder has created or confirmed the configured admin, it demotes every other `ADMIN` row to `USER` and logs a `WARN` naming each demoted username. Sketch:
   ```java
   private void demoteOtherAdmins(String keepUserId, String keepUsername) {
       for (User u : userRepository.findUsersByRole(Role.ADMIN)) {
           if (!u.userId().equals(keepUserId)) {
               userRepository.updateUserRole(u.userId(), Role.USER);
               log.warn("Demoted stale ADMIN '{}' to USER; only '{}' (ULPF_ADMIN_USERNAME) may be ADMIN.", u.username(), keepUsername);
           }
       }
   }
   ```
   - **Ensure first, demote last.** There is never a moment with zero admins, and a crash in between is repaired on the next start (the step is idempotent).
   - **Blank config demotes nobody.** The seeder already returns early when username or password is blank (`:45-48`); that path stays untouched, so a missing variable cannot lock everyone out.
2. **Runtime rules (fix B).**
   - **At the door (primary).** In `SpringSecurity.filterChain`, after the `/v1/admin/**` rule and before `anyRequest().authenticated()`, add `.requestMatchers("/v1/onboard/**").hasAnyRole("USER", "VENDOR")`. An `ADMIN` token then gets 403 on every onboarding endpoint (submit, update, withdraw, my-requests, my-sources). `JwtFilter` grants `ROLE_<role>` straight from the token, so `USER` and `VENDOR` pass and `ADMIN` does not. The approval endpoint lives under `/v1/admin/onboard/**`, which this matcher does not touch.
   - **Backup (defence in depth).** In `OnboardingService`, promote to `VENDOR` only when the owner's current role is `USER`, mirroring `AuthService.java:61`. An `ADMIN` (or an existing `VENDOR`) is left alone even if some future caller reaches this code another way.

**Why `USER`, not delete or `VENDOR`:** `USER` is the no-privilege role; keeping the row preserves history and foreign keys; it is reversible (put the old username back in `.env` and the seeder promotes it again). An ex-admin owns no vendor, so `VENDOR` would be wrong.

**Alternatives rejected:**
- *Delete the old admin row:* breaks or orphans references (see §2).
- *Partial unique index "one ADMIN row" in SQLite:* good extra protection, but "ensure first, demote last" would violate it momentarily, and an existing database with two admins would fail to start. Possible later hardening.
- *Multiple admins:* out of scope; the product supports one admin credential today.

## 4. Test plan (written first)

New `AdminUserSeederTest` (`controlplane/service/`), real in-memory SQLite plus real BCrypt, same setup as `UserRepositoryTest`:

| Test | Class | Expected to fail before because… |
|---|---|---|
| username changed → previous admin becomes `USER`, new one is `ADMIN` | `AdminUserSeederTest` | old row stays `ADMIN` |
| after seeding, exactly one `ADMIN` row exists and it is the configured one | `AdminUserSeederTest` | two `ADMIN` rows |
| two earlier stale admins → both demoted | `AdminUserSeederTest` | both stay `ADMIN` |
| configured name already exists as `USER` → promoted to `ADMIN` and the old admin demoted | `AdminUserSeederTest` | old admin stays `ADMIN` |
| unchanged config, run twice → no role changes (idempotent) | `AdminUserSeederTest` | passes before (regression guard) |
| blank username or password → nobody demoted | `AdminUserSeederTest` | passes before (guards the early return) |
| `USER` and `VENDOR` rows are never touched | `AdminUserSeederTest` | passes before (guard) |
| approving a request owned by an `ADMIN` leaves that user `ADMIN` | `OnboardingServiceTest` (extend) | `OnboardingService.java:382` sets `VENDOR` unconditionally |
| approving a request owned by a `USER` still promotes to `VENDOR` | `OnboardingServiceTest` (extend) | passes before (guard) |
| `ADMIN` token gets 403 on `POST /v1/onboard/{adminUsername}`, `POST /v1/onboard/update/{u}`, `POST /v1/onboard/withdraw/{id}`, `GET /v1/onboard/my-requests`, `GET /v1/onboard/my-sources` | `OnboardingSecurityTest` (new) | today the same call returns 201 (reproduced 2026-10-29): no role check |
| `USER` and `VENDOR` tokens are not rejected by the new rule on the same endpoints (anything but 403); no token is still rejected | `OnboardingSecurityTest` (new) | passes before (guard) |
| `ADMIN` token on `PUT /v1/admin/onboard/{unknown-id}` → 404, not 403 (the new rule does not catch the approval endpoint) | `OnboardingSecurityTest` (new) | passes before (guard) |

`OnboardingSecurityTest` is a `@SpringBootTest` (`test` profile) using `MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity())`, with real logins and real JWTs. The pattern was proven by the 2026-10-29 reproduction (note: in this Spring version the multipart builder type is `MockMultipartHttpServletRequestBuilder`).

Expected red tests: 6 (4 seeder rows, the service-guard row, the ADMIN → 403 row). The rest must already be green and stay green.

## 5. Files to change

- `core-engine/src/main/java/com/ulpf/controlplane/service/AdminUserSeeder.java`: `demoteOtherAdmins` after create/update/up-to-date branches; update the class Javadoc.
- `core-engine/src/main/java/com/ulpf/controlplane/service/OnboardingService.java`: role guard at line 382.
- `core-engine/src/main/java/com/ulpf/common/SpringSecurity.java`: one `requestMatchers("/v1/onboard/**").hasAnyRole("USER", "VENDOR")` rule.
- `core-engine/src/test/java/com/ulpf/controlplane/service/AdminUserSeederTest.java`: new.
- `core-engine/src/test/java/com/ulpf/controlplane/controller/OnboardingSecurityTest.java`: new (real security chain).
- `core-engine/src/test/java/com/ulpf/controlplane/service/OnboardingServiceTest.java`: two tests added.
- `docs/ARCH/API_SPECIFICATION.md` (lines 22-23): onboarding endpoints are for `USER`/`VENDOR`, not `ADMIN`; fix the update path to `/v1/onboard/update/{username}`.
- `README.md` (line 178): the seeder now also demotes any other admin; `.env.example`: one-line comment above `ULPF_ADMIN_USERNAME`; `to-do-list.md`: item 11 status.

## 6. Steps

- [ ] 1. Write the tests above; run them; confirm the six expected failures fail for the stated reason and the guard tests pass
- [ ] 2. Seeder: demote other admins after the configured admin is ensured
- [ ] 3. `SpringSecurity`: `/v1/onboard/**` requires `USER` or `VENDOR`
- [ ] 4. `OnboardingService`: promote to `VENDOR` only from `USER`
- [ ] 5. Refactor; keep class Javadoc accurate
- [ ] 6. Full suite: `mvn clean test -DJWT_SECRET="test-secret-key-1234567890"` (baseline 320, 0 failures) plus the new tests
- [ ] 7. Docs (`API_SPECIFICATION.md`, README, `.env.example`, `to-do-list.md`); set Status to Done

## 7. Risks and open questions

1. **Problem B is part of this plan** (you asked on 2026-10-29 for the door rule to be added). It can still be split out before approval; Problem A does not depend on it. **Door rule confirmed (2026-10-31, team lead):** onboarding endpoints are for `USER` (first sample submission) and `VENDOR` (more sources) only. `API_SPECIFICATION.md:22-23` listing `VENDOR`/`ADMIN` is wrong and is corrected as part of this change (§5). If the team ever wants the admin to onboard test sources, drop the door rule and keep the service guard: the guard alone already stops the role flip. No admin screen calls `/v1/onboard/**` today (only `OnboardingPage.jsx` does, and it redirects `ADMIN` away), so the rule only affects direct API calls with an admin token.
2. **Demotion on upgrade.** A database that already has two admins (from earlier username changes) will demote the older ones on the first start after this ships, with a `WARN`. That is the intended outcome; mention it in the release note.
3. **Typo in `ULPF_ADMIN_USERNAME`** creates a new admin and demotes the old one. The new account works with the `.env` password, and restoring the old name in `.env` promotes it back. A blank password or username demotes nobody.
4. **Not covered: tokens already issued.** A demoted admin's login token still works as admin for up to 24 h (§2). Closing that means `JwtFilter` checking the user's current role through a short-lived cache. The Grafana plan already needs the same cached user lookup for `validate-grafana`, so it is best built once and reused. Proposed as a separate backlog item, not part of this plan.
5. **Multi-node:** every node runs the seeder at startup; the steps are idempotent, so concurrent runs end in the same state.
6. **Overlap with the Grafana plan:** its Task 1 also edits `SpringSecurity.java` (a new `@Order(1)` actuator chain). This plan lands first, so Task 1 builds on top of the onboarding rule.
7. **Not audited:** endpoints outside `/v1/admin/**` and `/v1/analytics/**` may also rely on UI-only gating. Only `/v1/onboard/**` was checked here; a full endpoint-by-endpoint role audit is a candidate follow-up.

## 8. Outcome (fill in when closing)

Deviations from the plan, final test counts, follow-ups.
