# Plan: Admin accounts — one-time setup, super-admin, invite links, revoke

- **Date:** 2026-10-31
- **Status:** Draft
- **Owner:** Adarsh
- **Approved by / on:** _(blank until approved)_
- **File name:** `docs/plans/2026-10-31-admin-accounts-setup-and-invites.md`
- **Supersedes:** `docs/plans/2026-10-29-single-admin-enforcement.md` (backlog item 11). Its Problem B fix (an admin must never become a vendor) is carried over unchanged; its Problem A (stale admins after an `.env` rename) disappears, because `.env` no longer defines the live admin.
- **Blocks:** backlog item 7 (Prometheus + Grafana): Grafana trusts the `ADMIN` role, so admin accounts must be managed properly first.

## 1. Goal

Stop using `.env` as the live admin account. `.env` holds **setup credentials** that are used exactly once, to create the **super-admin** through a one-time setup screen. From then on admin accounts live only in SQLite: the super-admin invites other admins with a single-use link and can revoke them. The login screen keeps its three roles (`USER`, `VENDOR`, `ADMIN`); the super-admin signs in as `ADMIN`.

"Done" means: a fresh install shows the setup screen until the super-admin exists; after that `.env` is never read for accounts again; only the super-admin can invite or revoke admins (UI and API); a revoked admin is locked out on their next request; an admin can never be turned into a vendor; all tests listed in §4 pass and the full suite is green.

## 2. What I verified

How established self-hosted products do it (read 2026-10-31 from their docs):
- **Keycloak:** the startup admin options are used only on the very first start; the account is described as temporary, to be replaced by a permanent admin; a lost admin is recovered with a command on the server (`kc.sh bootstrap-admin user`).
- **GitLab:** initial root password at install; afterwards admins are managed in the admin UI; lost password reset with a server command (`gitlab-rake "gitlab:password:reset"`).
- **Grafana:** `admin_password` is "set once on first-run".
Common pattern: bootstrap once, then the database owns the accounts, plus a server-side recovery path. That is what this plan builds.

ULPF today:
- `AdminUserSeeder` (`controlplane/service/AdminUserSeeder.java:44-77`) re-applies `ULPF_ADMIN_USERNAME` / `ULPF_ADMIN_PASSWORD` on every start: creates the account or overwrites its password and role. This is what makes `.env` the live admin.
- `users` table (`sqlite/schema.sql:5-12`): `role` is `CHECK (role IN ('ADMIN','VENDOR','USER'))`; there is no super-admin notion. `spring.sql.init.mode: always` re-runs `schema.sql` on every start, but `CREATE TABLE IF NOT EXISTS` never adds a column to an existing table, and there is **no migration mechanism** in the repo. A new column needs a small startup migration (§3.1).
- Foreign keys are enforced (`application.yaml:7`, `PRAGMA foreign_keys = ON`); `notifications.user_id`, `onboarding_requests.user_id`, `vendors.owner_user_id` reference `users` with no cascade.
- Tokens: `JwtUtil.java:29-40` signs `id`, `username`, `role`, 24 h expiry. `JwtFilter.java:75-95` builds `ROLE_<role>` from the token alone, never from the database, so a deleted or revoked account keeps working until its token expires.
- `SpringSecurity.java:38-42`: `/v1/admin/**` and `/v1/analytics/**` require `ADMIN`; everything else authenticated; `/v1/onboard/**` has no role rule.
- Problem B (carried over, reproduced 2026-10-29 and again in red tests on branch `feature/single-admin-enforcement`, commit `b9f3533`): an `ADMIN` token gets **201** on `POST /v1/onboard/{username}`, and approving that request sets the admin's role to `VENDOR` (`OnboardingService.java:382`).
- Frontend: `LoginPage.jsx:9-10` pre-fills `admin` / `Admin@12345` in the form. Auth screens use `AuthLayout.css` (warm canvas `#faf9f5`, white card, teal `#0d9488`, Plus Jakarta Sans); admin screens use `AdminDashboardPage.css` (`#f4f7fc`, `max-width: 1280px`) and the white `Navbar.jsx`. `ProtectedRoute.jsx` checks `role` from `localStorage`. There is no frontend test framework yet (Vitest + RTL approved on 2026-10-29 as Task 13a of the Grafana plan).
- The admin's alert webhook is the installation-wide `ULPF_ALERT_WEBHOOK_URL` in `.env`, not tied to any account; it is unaffected. `AlertDispatcher` sends in-app system alerts to every `ADMIN` row, so with several admins all of them receive alerts (intended).

## 3. Proposed approach

### 3.1 Data
- `users` gets `is_super_admin INTEGER NOT NULL DEFAULT 0`. The role stays `ADMIN` for every admin, so every existing `ADMIN` check (backend rules, `AlertDispatcher`, frontend routes, Grafana later) keeps working unchanged. The super-admin is "an `ADMIN` with the flag".
- A partial unique index `CREATE UNIQUE INDEX one_super_admin ON users(is_super_admin) WHERE is_super_admin = 1` makes "at most one super-admin" a database guarantee (also stops two setup requests racing).
- New table `admin_invites`: `invite_id`, `token_hash` (SHA-256, unique), `label` (optional, "for Ashitha"), `created_by`, `created_at`, `expires_at`, `used_at`, `used_by_user_id`. The raw token is never stored.
- **Migration:** a new `SqliteSchemaMigrator` (runs at startup, before anything reads users) adds the column if `PRAGMA table_info(users)` lacks it, then creates the index. The index is created there, not in `schema.sql`, because `schema.sql` runs before the migrator and would fail on an old database. `admin_invites` goes in `schema.sql` (new table, `IF NOT EXISTS` works). Idempotent: safe on every start.
- `User` record stays unchanged (it is constructed in many places and tests); the flag is read through `UserRepository.isSuperAdmin(userId)`.

### 3.2 One-time setup (the super-admin)
- **Setup mode** = no super-admin row exists. `AdminUserSeeder` is removed; nothing creates admins at startup any more.
- `GET /v1/setup/status` (public) → `{ "setupRequired": true|false }`.
- `POST /v1/setup` (public, works only in setup mode): body `setupUsername`, `setupPassword` (must equal `ULPF_ADMIN_USERNAME` / `ULPF_ADMIN_PASSWORD`, constant-time compare), `username`, `password`, `confirmPassword`. In one transaction it creates the super-admin (`ADMIN`, flag 1, BCrypt hash). Afterwards it returns `409` forever, and `.env` is never read for accounts again.
- Why ask for the `.env` setup credentials on the setup screen: between "the stack started" and "the operator finished setup", anyone who can reach the UI could otherwise claim the super-admin. Needing the setup credentials (which only the person with the server's `.env` has) closes that window. Portainer solves the same race with a timeout; Jenkins with a one-time password read from the server.
- Blank setup credentials in `.env` → setup is refused with a clear message (fail closed; nobody can claim the instance).
- Existing installs (upgrade): no row has the flag, so the setup screen appears once. Existing `ADMIN` rows stay as regular admins; the new super-admin can revoke any that should not exist (this replaces item 11's automatic cleanup).
- Remove the pre-filled `admin` / `Admin@12345` from `LoginPage.jsx`.

### 3.3 Invite links (regular admins)
- Super-admin clicks **Invite admin**, optionally types a label, gets a link: `<current address>/invite/<token>` (token: 32 random bytes, base64url; the frontend builds the link from `window.location.origin`, like the Grafana button). The link is shown **once** with a Copy button; only its hash is stored (same idea as vendor API keys).
- The link is valid for **24 hours** and **once**. Accepting runs `UPDATE admin_invites SET used_at=… WHERE token_hash=? AND used_at IS NULL AND expires_at > now`; only the request that changes that row may create the account, so a link cannot be used twice even by two people at the same moment.
- Invitee opens the link: `GET /v1/invites/{token}` (public) → valid or not. If valid: username, password, confirm password → `POST /v1/invites/{token}/accept` → new `ADMIN` (flag 0) → sent to the login screen.
- Air-gapped: there is no email; the super-admin shares the link themselves (chat, in person). Anyone who has the link can use it, so the UI says "share it privately".
- Pending invites are listed with label and time left; the super-admin can cancel one.

### 3.4 Revoke
- `DELETE /v1/admin/admins/{userId}` (super-admin only): deletes the account and its notifications in one transaction (your rule from 2026-10-31: replaced or removed admins leave no account behind). Admins cannot own vendor data (§3.6), so nothing else references them; if a row does reference one (only possible from before this fix), revoke is refused with a clear message instead of destroying vendor data.
- The super-admin cannot revoke themselves; nobody can revoke the super-admin.
- **Revoke must work immediately**, not after the token's 24 h expiry. So `JwtFilter` checks that the account still exists (and reads its role and super-admin flag) through a 60-second cache, evicted on revoke and on password change. Cost: one cache read per request; a database read at most once per minute per user. The Grafana plan's `validate-grafana` needs the same cached lookup (its A2); it is built once here and reused there.

### 3.5 Who can do what (enforced at the door, not only in the UI)
- `JwtFilter` adds `ROLE_SUPER_ADMIN` (taken from the database, not from the token) next to `ROLE_ADMIN` for the super-admin.
- `SpringSecurity`, in this order: `/v1/setup/**` and `/v1/invites/**` public (and added to `JwtFilter`'s excluded paths so a stale token in the browser cannot 401 them); `/v1/admin/admins/**` and `/v1/admin/invites/**` → `SUPER_ADMIN`; then the existing `/v1/admin/**` → `ADMIN`; `/v1/onboard/**` → `USER` or `VENDOR` (Problem B).
- Login response adds `"superAdmin": true|false`; the frontend stores it next to `role` to decide whether to show the Admins screen. It is a display hint only; the backend rules above are the real check.

### 3.6 An admin can never become a vendor (from item 11, unchanged)
- Door rule: `/v1/onboard/**` requires `USER` or `VENDOR`; an admin token gets `403`.
- Backup in `OnboardingService`: promote to `VENDOR` only if the owner is currently `USER`.

### 3.7 Screens (dead simple, consistent with today's UI)
Design rules for all three screens:
- **Reuse, don't invent.** Setup and invite use the existing auth card (`AuthLayout.css`: warm canvas, white card, teal primary, same fonts, same input style). The Admins screen uses the existing admin layout (`AdminDashboardPage.css`, `Navbar`). No new colours, no gradients, no illustrations, no icons beyond what exists.
- **One job per screen, one primary button**, full width on auth cards, teal. Secondary actions are plain text buttons.
- **8-point spacing:** 8 px label→input, 16 px between fields, 24 px between groups, 32 px card padding. Left-aligned text; one heading, one short sentence under it.
- **Errors inline**, under the field that caused them, in the existing rose text colour; no banners, no browser `alert()`/`confirm()` dialogs.
- **Plain words.** "Super-admin", "Invite admin", "Revoke". No jargon.

1. **Setup** (`/setup`, shown instead of login while setup is required). Heading "Set up ULPF". Small grey "Step 1 of 2" above the heading.
   - Step 1: "Enter the setup credentials from your server's `.env` file." Fields: Setup username, Setup password. Button: **Continue**.
   - Step 2: "Create your super-admin account. You'll use this from now on." Fields: Username, Password, Confirm password; one grey hint under Password with the rule. Button: **Create super-admin**.
   - Done: back to login with one quiet line: "Super-admin created. Sign in to continue."
2. **Invite** (`/invite/<token>`). Heading "Join ULPF as an admin". One line: "Invited as: <label>" when a label exists. Fields: Username, Password, Confirm password. Button: **Create admin account**. If the link is invalid, expired or used: no form, just "This invite link is no longer valid. Ask your super-admin for a new one."
3. **Admins** (`/admin/admins`, Navbar link "Admins", visible to the super-admin only).
   - Header row: title "Admins" on the left, **Invite admin** button on the right.
   - Clicking it opens a small inline panel under the header (not a modal): optional "Name (for your reference)" + **Create link**. Then the panel shows the link in a monospace read-only field with **Copy**, and one line: "Works once, for 24 hours. Share it privately."
   - List of admins: username, a small "Super-admin" tag on that row, "Added <date>", and a text button **Revoke** on regular admins. Revoke turns the row into an inline confirmation: "Revoke @name? They're signed out immediately." **Revoke** / Cancel.
   - "Pending invites" section only when there are any: label, "expires in 5 h", **Cancel**.
   - Empty state (only the super-admin): "Just you so far. Invite an admin to share the work."

### 3.8 Alternatives rejected
- *Keep `.env` as the live admin (item 11 as approved):* config files are not an account store; every restart re-applies them, renames leave stale admins, no way to have more than one admin. Rejected 2026-10-31 by the team lead.
- *Setup screen without the `.env` setup credentials (first visitor wins):* anyone on the network can claim the instance before the operator does.
- *A separate `SUPER_ADMIN` role value:* every existing `role == ADMIN` / `hasRole('ADMIN')` check in backend, frontend and the Grafana plan would need changing, and the login role selector would grow a fourth option. A flag on an `ADMIN` gives the same power with far less change.
- *Email invites:* no email in an air-gapped deployment.
- *Disable instead of delete on revoke:* keeps an account row nobody owns; you chose deletion for replaced admins on 2026-10-31.

### 3.9 Not in this plan (future)
- **Scoped admins** ("this admin can only approve onboarding", like AWS IAM). The flag can later become a set of permissions; nothing here blocks that.
- Transferring the super-admin role to another admin.
- Company login (LDAP / Active Directory / SSO).

## 4. Test plan (written first)

Backend (JUnit 5; real in-memory or temp-file SQLite; real BCrypt; real security chain where the door is under test).

| Test | Class | Expected to fail before because… |
|---|---|---|
| migrator adds `is_super_admin` to a database created with the old `users` table, keeps existing rows, is safe to run twice | `SqliteSchemaMigratorTest` | migrator does not exist |
| empty database → `GET /v1/setup/status` says `setupRequired: true`; after setup → `false` | `SetupControllerTest` | endpoint does not exist |
| setup with wrong `.env` setup credentials → 401, nothing created | `SetupServiceTest` | service does not exist |
| setup with blank `.env` setup credentials → refused, nothing created | `SetupServiceTest` | — |
| setup with password ≠ confirm, or invalid username → 400, nothing created | `SetupServiceTest` | — |
| setup succeeds → one `ADMIN` with the flag, new username, BCrypt hash of the new password; login as `ADMIN` works and returns `superAdmin: true`; the `.env` setup credentials cannot log in | `SetupServiceTest`, `SetupControllerTest` | — |
| second setup after success → 409, even with correct setup credentials | `SetupServiceTest` | — |
| restart with different `.env` values after setup → super-admin unchanged, no account created or changed | `SetupServiceTest` | today `AdminUserSeeder` re-applies `.env` on every start |
| two setups at once → exactly one super-admin (unique index) | `SetupServiceTest` | no index |
| super-admin creates an invite → raw token returned once; database holds only its SHA-256 hash; expiry 24 h | `AdminInviteServiceTest` | — |
| regular admin, vendor, user → 403 on `/v1/admin/invites/**` and `/v1/admin/admins/**` | `AdminAccountSecurityTest` | — |
| accept a valid invite → new `ADMIN` without the flag; invite marked used; second accept → 410 | `AdminInviteServiceTest` | — |
| expired invite, cancelled invite, unknown token → rejected, nothing created | `AdminInviteServiceTest` | — |
| accept with a username that is taken → 409; password ≠ confirm → 400 | `AdminInviteServiceTest` | — |
| two accepts of one invite at once → exactly one account | `AdminInviteServiceTest` | — |
| super-admin revokes an admin → account and its notifications deleted; that admin's existing token → 401 on the next request | `AdminAccountSecurityTest` | `JwtFilter` trusts the token for 24 h |
| super-admin cannot revoke themselves; nobody can revoke the super-admin | `AdminAccountServiceTest` | — |
| revoke refused if the admin row is referenced by vendor data (legacy) | `AdminAccountServiceTest` | — |
| token of a deleted user → 401; repeated requests within 60 s hit the database once | `JwtFilterTest` (extend or new) | no live lookup |
| `ROLE_SUPER_ADMIN` comes from the database flag, not from a token claim (a token with a forged claim gains nothing) | `JwtFilterTest` | — |
| **carried over:** `ADMIN` token → 403 on all five `/v1/onboard/**` endpoints | `OnboardingSecurityTest` (exists, red, `b9f3533`) | today 201/200/400 |
| **carried over:** approving an `ADMIN`-owned request keeps `ADMIN` | `OnboardingServiceTest` (exists, red, `b9f3533`) | today becomes `VENDOR` |
| `USER` and `VENDOR` tokens are not rejected by the onboarding door rule | `OnboardingSecurityTest` | passes before (guard) |

The five `AdminUserSeederTest` tests in `b9f3533` test the `.env`-driven seeder this plan removes; they are deleted in the same change, not weakened.

Frontend (Vitest + React Testing Library; added first as its own change, the already approved Task 13a of the Grafana plan, moved earlier):

| Test | File |
|---|---|
| login screen sends the user to `/setup` when `setupRequired` is true; no credentials are pre-filled | `LoginPage.test.jsx` |
| setup: step 1 → step 2; mismatched confirm shows an inline error and sends nothing; success goes to login with the notice | `SetupPage.test.jsx` |
| invite: invalid link shows the message and no form; valid link → submit → login | `InvitePage.test.jsx` |
| "Admins" link and route only for the super-admin | `Navbar.test.jsx`, `ProtectedRoute.test.jsx` |
| Admins screen: invite shows the link once with Copy; Revoke asks inline and only then calls the API | `AdminsPage.test.jsx` |

## 5. Files to change

Backend (`core-engine/`):
- `src/main/resources/sqlite/schema.sql` — `is_super_admin` in `CREATE TABLE users` (fresh installs); new `admin_invites` table.
- `common/db/SqliteSchemaMigrator.java` (new) — adds the column and the unique index on existing databases.
- `common/db/UserRepository.java` — `isSuperAdmin`, `createSuperAdmin`, `findAdmins`, `deleteUserWithNotifications`, `isReferencedByVendorData`.
- `common/db/AdminInviteRepository.java` (new).
- `controlplane/service/SetupService.java`, `controlplane/controller/SetupController.java` (new) — `/v1/setup`.
- `controlplane/service/AdminAccountService.java`, `controlplane/controller/AdminAccountController.java` (new) — invites, accept, list, revoke.
- `controlplane/service/AdminUserSeeder.java` — removed.
- `common/JwtFilter.java` — cached live user lookup, `ROLE_SUPER_ADMIN`, excluded paths for setup and invites.
- `common/SpringSecurity.java` — rules in §3.5.
- `controlplane/service/AuthService.java`, `controlplane/controller/AuthController.java` — `superAdmin` in the login response.
- `controlplane/service/OnboardingService.java` — promote only from `USER`.
- Tests: new classes in §4; delete `AdminUserSeederTest.java` (from `b9f3533`).

Frontend (`frontend/`):
- Vitest + RTL setup (Task 13a, own commit first).
- `src/App.jsx` — routes `/setup`, `/invite/:token`, `/admin/admins`.
- `src/pages/LoginPage.jsx` — setup redirect, remove pre-filled credentials, store `superAdmin`.
- `src/pages/SetupPage.jsx`, `src/pages/InvitePage.jsx` (new) — reuse `AuthLayout.css`.
- `src/pages/AdminsPage.jsx` (new) — reuse `AdminDashboardPage.css`; small additions there only if needed.
- `src/components/Navbar.jsx`, `src/routes/ProtectedRoute.jsx` — super-admin-only link and route.
- `src/api/admins.js` (new).

Config and docs:
- `.env.example` — `ULPF_ADMIN_USERNAME` / `ULPF_ADMIN_PASSWORD` described as one-time setup credentials.
- `startup/start.sh` — see open question 1.
- `docs/ARCH/API_SPECIFICATION.md` — new endpoints; onboarding endpoints `USER`/`VENDOR`; fix update path.
- `docs/ARCH/ARCHITECTURE.md` — accounts and roles section.
- `README.md` — first-run setup, inviting admins, revoking.
- `docs/frontend/frontend.md` — new screens.
- `to-do-list.md` — item 11 points to this plan.
- `docs/plans/2026-10-29-single-admin-enforcement.md` — Status: Superseded by this plan.
- `docs/plans/2026-10-28-prometheus-grafana-observability.md` — A2 wording: Grafana sessions end when an admin's password changes or the admin is revoked; the cached user lookup comes from this plan; each admin gets their own Grafana login (`auth.proxy` uses the username).

## 6. Steps

- [ ] 0. Frontend test framework (Vitest + RTL + jsdom, pinned dev dependencies, `npm test` in CI), own commit
- [ ] 1. Backend red tests (§4); confirm each fails for the stated reason
- [ ] 2. Migration + repositories
- [ ] 3. Setup service and endpoints; remove `AdminUserSeeder`
- [ ] 4. `JwtFilter` live lookup + `SpringSecurity` rules + onboarding guard
- [ ] 5. Invites and revoke
- [ ] 6. Frontend red tests, then screens (setup, invite, admins, navbar, login)
- [ ] 7. Refactor; Javadoc on every new class
- [ ] 8. Full backend suite (baseline 320) + `npm test` + `npm run build`; manual run through the screens on rootless Docker
- [ ] 9. Docs; Status → Done

Each step is its own commit on this branch; the branch can be split into two PRs (backend, frontend) if that reads better.

## 7. Risks and open questions

1. **Setup credentials in `.env`: fixed default or generated?** With the fixed default from `.env.example` (`admin` / `Admin@12345`, public on GitHub), anyone who reaches the UI before the operator can finish setup. **Recommendation:** `start.sh` generates a random setup password when it creates `.env` (as it will for the monitor password) and prints it once. A fixed default stays possible for local development.
2. **Lost super-admin password.** Without a recovery path the instance can never be administered again (short of editing the database). **Recommendation:** a small server-side command, e.g. `./startup/reset-super-admin.sh`, that resets the super-admin's password from the host (the GitLab and Keycloak approach: whoever controls the server is trusted). Include it here or as the next small item?
3. **Changing your own password.** Admins cannot change their password in the app today. Small "Change password" form (current, new, confirm) for all admins: include here or later?
4. **Password rule for admins.** Today any account needs 6-100 characters. Suggest at least 12 for admin accounts (super-admin and invited admins).
5. **Invite lifetime:** 24 hours, single use. OK?
6. **Upgrade behaviour:** existing databases show the setup screen once; existing `ADMIN` rows become regular admins. Acceptable, given that the product is not deployed anywhere with real customers yet? (Assumption to confirm.)
7. **Existing tokens and Problem B overlap:** the live user lookup in `JwtFilter` also closes item 11's "removed admin's token works for 24 h" gap; no separate backlog item needed.
8. **Not audited:** endpoints outside `/v1/admin/**`, `/v1/analytics/**` and `/v1/onboard/**` may still rely on UI-only gating. A full endpoint role audit remains a candidate follow-up.
9. **Branch:** the work continues on `feature/single-admin-enforcement` (which already holds the two carried-over red tests). Renaming it to `feature/admin-accounts` is cosmetic; your call.

## 8. Outcome (fill in when closing)

Deviations from the plan, final test counts, follow-ups.
