# Plan: Admin accounts — one-time setup, super-admin, invite and reset links, revoke

- **Date:** 2026-10-31
- **Status:** Approved (rev 2, 2026-10-31: team lead's answers to §7 applied)
- **Owner:** Adarsh
- **Approved by / on:** Adarsh (team lead), 2026-10-31
- **File name:** `docs/plans/2026-10-31-admin-accounts-setup-and-invites.md`
- **Supersedes:** `docs/plans/2026-10-29-single-admin-enforcement.md` (backlog item 11). Its Problem B fix (an admin must never become a vendor) is carried over unchanged; its Problem A (stale admins after an `.env` rename) disappears, because `.env` no longer defines the live admin.
- **Blocks:** backlog item 7 (Prometheus + Grafana): Grafana trusts the `ADMIN` role, so admin accounts must be managed properly first.

## 1. Goal

Stop using `.env` as the live admin account. `.env` holds **setup credentials** that are used exactly once, to create the **super-admin** through a one-time setup screen. From then on admin accounts live only in SQLite: the super-admin invites other admins with a single-use link, can send a regular admin a password-reset link, and can revoke admins. A lost super-admin password is reset with a command on the server. The login screen keeps its three roles (`USER`, `VENDOR`, `ADMIN`); the super-admin signs in as `ADMIN`.

"Done" means: a fresh install shows the setup screen until the super-admin exists; after that `.env` is never read for accounts again; only the super-admin can invite or revoke admins (UI and API); a revoked admin, or anyone whose password was reset, is locked out of old sessions on their next request; the admins left over from the old `.env` seeder are removed once, on the first start after this ships; an admin can never be turned into a vendor; all tests listed in §4 pass and the full suite is green.

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
- New table `admin_links` (single-use links): `link_id`, `kind` (`INVITE` or `RESET`), `token_hash` (SHA-256, unique), `label` (optional, "for Ashitha"), `target_user_id` (the admin whose password a `RESET` link changes; empty for `INVITE`), `created_by`, `created_at`, `expires_at`, `used_at`. The raw token is never stored.
- **Migration:** a new `SqliteSchemaMigrator` (runs at startup, before anything reads users) adds the column if `PRAGMA table_info(users)` lacks it, then creates the index. The index is created there, not in `schema.sql`, because `schema.sql` runs before the migrator and would fail on an old database. `admin_links` goes in `schema.sql` (new table, `IF NOT EXISTS` works). Safe on every start: the column step only runs when the column is missing.
- **One-time cleanup of old admins (team lead, 2026-10-31):** by design there should never have been any admin except the one from `.env`; the extra `ADMIN` rows that exist come from item 11's Problem A. So, in the same migration step that adds the column, and in one transaction, every existing `ADMIN` row and its notifications are deleted and each is logged as a `WARN`. Because that step only runs when the column is missing, the cleanup happens **exactly once per database**: on the first start after this ships. It never runs again: not on later restarts, not after setup, not on any password reset. After it, the database has no admin, so the setup screen appears and the super-admin is created fresh.
  - *Edge case (what "an admin owns vendor data" means):* normally an admin owns nothing. The only way it could is the bug this plan fixes (Problem B): an admin token submitting the vendor onboarding form makes a vendor, a source and an API key whose owner is the admin's account. The database then refuses to delete that account, because the vendor still points at it. Note: approving a request does not link the vendor to the approving admin (there is no approver column in `vendors` or `onboarding_requests`); only the submitter becomes the owner. If that ever turns out to be the case, the migration does not delete the vendor's data and does not stop the server from starting: it removes the account's `ADMIN` role, replaces its password with one nobody knows, and logs which vendor data it owns so a person can decide. On your local database there are no users at all; the container volume was not checked.
- `User` record stays unchanged (it is constructed in many places and tests); the flag is read through `UserRepository.isSuperAdmin(userId)`.

### 3.2 One-time setup (the super-admin)
- **Setup mode** = no super-admin row exists. `AdminUserSeeder` is removed; nothing creates admins at startup any more.
- `GET /v1/setup/status` (public) → `{ "setupRequired": true|false }`.
- `POST /v1/setup` (public, works only in setup mode): body `setupUsername`, `setupPassword` (must equal `ULPF_ADMIN_USERNAME` / `ULPF_ADMIN_PASSWORD`, constant-time compare), `username`, `password`, `confirmPassword`. In one transaction it creates the super-admin (`ADMIN`, flag 1, BCrypt hash). Afterwards it returns `409` forever, and `.env` is never read for accounts again.
- Why ask for the `.env` setup credentials on the setup screen: between "the stack started" and "the operator finished setup", anyone who can reach the UI could otherwise claim the super-admin. Needing the setup credentials (which only the person with the server's `.env` has) closes that window. Portainer solves the same race with a timeout; Jenkins with a one-time password read from the server.
- Blank setup credentials in `.env` → setup is refused with a clear message (fail closed; nobody can claim the instance).
- Existing installs (upgrade): the one-time cleanup in §3.1 removes the old admins, so the setup screen appears once and the super-admin is created fresh.
- **Generated setup password (team lead, 2026-10-31):** when `start.sh` creates `.env` for the first time, it fills `ULPF_ADMIN_PASSWORD` with a random 10-character password (letters and digits) and prints it once with the setup URL. If an existing `.env` still has the old public default `Admin@12345`, `start.sh` prints a warning. A setup password is entered once and then dies, so 10 random characters are enough; it is not the super-admin's password.
- **Admin password rule:** at least 12 characters (max 100) for every admin account: super-admin, invited admins, and resets. `USER` and `VENDOR` accounts keep today's 6-100 rule.
- Remove the pre-filled `admin` / `Admin@12345` from `LoginPage.jsx`.

### 3.3 Invite links (regular admins)
- Super-admin clicks **Invite admin**, optionally types a label, gets a link: `<current address>/link/<token>` (token: 32 random bytes, base64url; the frontend builds the link from `window.location.origin`, like the Grafana button). The link is shown **once** with a Copy button; only its hash is stored (same idea as vendor API keys).
- The link is valid for **12 hours** and **once**. Accepting runs `UPDATE admin_links SET used_at=… WHERE token_hash=? AND used_at IS NULL AND expires_at > now`; only the request that changes that row may create the account, so a link cannot be used twice even by two people at the same moment.
- Invitee opens the link: `GET /v1/links/{token}` (public) → valid or not, and its kind. If a valid invite: username, password, confirm password → `POST /v1/links/{token}` → new `ADMIN` (flag 0) → sent to the login screen.
- Air-gapped: there is no email; the super-admin shares the link themselves (chat, in person). Anyone who has the link can use it, so the UI says "share it privately".
- Pending invites are listed with label and time left; the super-admin can cancel one.

### 3.4a Password reset (who can do it)
- **A regular admin lost their password:** on the Admins screen the super-admin clicks **Reset password** on that admin's row. This makes a `RESET` link (same rules as invites: shown once, 12 hours, single use). The admin opens it and sets a new password (password + confirm). Only the super-admin can create these links (`/v1/admin/admins/**` is super-admin only); the public endpoint that consumes a link can change only the one account the link names.
- **The super-admin lost their password:** there is no button for this, on purpose: nobody who is signed in can vouch for the super-admin. Instead a command run on the server: `./startup/reset-super-admin.sh`. It asks for the new password twice and runs the core-engine image in a one-shot "reset" mode against the same SQLite file, then exits. Who can run it: only someone who can run container commands on the server. That person can already read the database and `.env`, so this adds no new way in; there is no network endpoint for it at all. Every use is logged as a `WARN`.
- **A reset signs the person out everywhere.** Login tokens carry a short fingerprint of the password hash (`pwv`, the same idea as the Grafana plan's A2). `JwtFilter`'s live lookup (§3.4) compares it, so tokens issued before the reset stop working within the 60-second cache window (immediately for resets done through the app, which evict the cache).
- **Changing your own password while signed in** is not in this plan (team lead, 2026-10-31: on hold; it normally needs a second check such as email, or the super-admin's approval). See §3.9.

### 3.4 Revoke
- `DELETE /v1/admin/admins/{userId}` (super-admin only): deletes the account and its notifications in one transaction (your rule from 2026-10-31: replaced or removed admins leave no account behind). Admins cannot own vendor data (§3.6), so nothing else references them; if a row does reference one (only possible from before this fix), revoke is refused with a clear message instead of destroying vendor data.
- The super-admin cannot revoke themselves; nobody can revoke the super-admin.
- **Revoke must work immediately**, not after the token's 24 h expiry. So `JwtFilter` checks that the account still exists (and reads its role and super-admin flag) through a 60-second cache, evicted on revoke and on password change. Cost: one cache read per request; a database read at most once per minute per user. The Grafana plan's `validate-grafana` needs the same cached lookup (its A2); it is built once here and reused there.

### 3.5 Who can do what (enforced at the door, not only in the UI)
- `JwtFilter` adds `ROLE_SUPER_ADMIN` (taken from the database, not from the token) next to `ROLE_ADMIN` for the super-admin.
- `SpringSecurity`, in this order: `/v1/setup/**` and `/v1/links/**` (opening and using invite and reset links) public, and added to `JwtFilter`'s excluded paths so a stale token in the browser cannot 401 them; `/v1/admin/admins/**` (list, invite, reset link, revoke) → `SUPER_ADMIN`; then the existing `/v1/admin/**` → `ADMIN`; `/v1/onboard/**` → `USER` or `VENDOR` (Problem B).
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
2. **Invite / reset link** (`/link/<token>`, one page for both kinds). Invite: heading "Join ULPF as an admin", one line "Invited as: <label>" when a label exists, fields Username, Password, Confirm password, button **Create admin account**. Reset: heading "Set a new password", one line "For @name", fields Password, Confirm password, button **Save password**. If the link is invalid, expired or used: no form, just "This link is no longer valid. Ask your super-admin for a new one."
3. **Admins** (`/admin/admins`, Navbar link "Admins", visible to the super-admin only).
   - Header row: title "Admins" on the left, **Invite admin** button on the right.
   - Clicking it opens a small inline panel under the header (not a modal): optional "Name (for your reference)" + **Create link**. Then the panel shows the link in a monospace read-only field with **Copy**, and one line: "Works once, for 12 hours. Share it privately."
   - List of admins: username, a small "Super-admin" tag on that row, "Added <date>", and two text buttons on regular admins: **Reset password** and **Revoke**. Reset password shows the reset link in the same inline panel style ("Works once, for 12 hours. Share it only with @name."). Revoke turns the row into an inline confirmation: "Revoke @name? They're signed out immediately." **Revoke** / Cancel.
   - "Pending invites" section only when there are any: label, "expires in 5 h", **Cancel**.
   - Empty state (only the super-admin): "Just you so far. Invite an admin to share the work."

### 3.8 Alternatives rejected
- *Keep `.env` as the live admin (item 11 as approved):* config files are not an account store; every restart re-applies them, renames leave stale admins, no way to have more than one admin. Rejected 2026-10-31 by the team lead.
- *Setup screen without the `.env` setup credentials (first visitor wins):* anyone on the network can claim the instance before the operator does.
- *A separate `SUPER_ADMIN` role value:* every existing `role == ADMIN` / `hasRole('ADMIN')` check in backend, frontend and the Grafana plan would need changing, and the login role selector would grow a fourth option. A flag on an `ADMIN` gives the same power with far less change.
- *Email invites:* no email in an air-gapped deployment.
- *Disable instead of delete on revoke:* keeps an account row nobody owns; you chose deletion for replaced admins on 2026-10-31.

### 3.9 Not in this plan (future)
- **Change your own password while signed in** (on hold, team lead 2026-10-31: decide later between a second check such as email, or super-admin approval).
- **Scoped admins** ("this admin can only approve onboarding", like AWS IAM). The flag can later become a set of permissions; nothing here blocks that.
- Transferring the super-admin role to another admin.
- Company login (LDAP / Active Directory / SSO).

## 4. Test plan (written first)

Backend (JUnit 5; real in-memory or temp-file SQLite; real BCrypt; real security chain where the door is under test).

| Test | Class | Expected to fail before because… |
|---|---|---|
| migrator adds `is_super_admin` to a database created with the old `users` table, keeps `USER`/`VENDOR` rows, is safe to run twice | `SqliteSchemaMigratorTest` | migrator does not exist |
| first run on an old database deletes every `ADMIN` row and its notifications; `USER`/`VENDOR` rows and their data untouched | `SqliteSchemaMigratorTest` | — |
| cleanup runs once: after setup, running the migrator again (restart) leaves the super-admin and invited admins untouched | `SqliteSchemaMigratorTest` | — |
| legacy admin that owns vendor data → not deleted, `ADMIN` role removed, password unusable, vendor data untouched, startup continues, `WARN` logged | `SqliteSchemaMigratorTest` | — |
| empty database → `GET /v1/setup/status` says `setupRequired: true`; after setup → `false` | `SetupControllerTest` | endpoint does not exist |
| setup with wrong `.env` setup credentials → 401, nothing created | `SetupServiceTest` | service does not exist |
| setup with blank `.env` setup credentials → refused, nothing created | `SetupServiceTest` | — |
| setup with password ≠ confirm, password shorter than 12, or invalid username → 400, nothing created | `SetupServiceTest` | — |
| setup succeeds → one `ADMIN` with the flag, new username, BCrypt hash of the new password; login as `ADMIN` works and returns `superAdmin: true`; the `.env` setup credentials cannot log in | `SetupServiceTest`, `SetupControllerTest` | — |
| second setup after success → 409, even with correct setup credentials | `SetupServiceTest` | — |
| restart with different `.env` values after setup → super-admin unchanged, no account created or changed | `SetupServiceTest` | today `AdminUserSeeder` re-applies `.env` on every start |
| two setups at once → exactly one super-admin (unique index) | `SetupServiceTest` | no index |
| super-admin creates an invite → raw token returned once; database holds only its SHA-256 hash; expiry 12 h | `AdminLinkServiceTest` | — |
| regular admin, vendor, user → 403 on `/v1/admin/admins/**` (list, invite, reset link, revoke) | `AdminAccountSecurityTest` | — |
| accept a valid invite → new `ADMIN` without the flag; invite marked used; second accept → 410 | `AdminLinkServiceTest` | — |
| expired invite, cancelled invite, unknown token → rejected, nothing created | `AdminLinkServiceTest` | — |
| accept with a username that is taken → 409; password ≠ confirm or shorter than 12 → 400 | `AdminLinkServiceTest` | — |
| super-admin creates a reset link for a regular admin → using it changes only that admin's password; their old tokens → 401; link single use, 12 h | `AdminLinkServiceTest`, `AdminAccountSecurityTest` | — |
| reset link cannot target the super-admin; a reset link cannot create an account, an invite link cannot change a password | `AdminLinkServiceTest` | — |
| super-admin reset command sets a new password (12+ characters, confirmed); old super-admin tokens stop working; no other account changes | `ResetSuperAdminCommandTest` | command does not exist |
| a token issued before a password change is rejected (`pwv` mismatch); a token issued after it works | `JwtFilterTest` | no `pwv` claim |
| two accepts of one invite at once → exactly one account | `AdminLinkServiceTest` | — |
| super-admin revokes an admin → account and its notifications deleted; that admin's existing token → 401 on the next request | `AdminAccountSecurityTest` | `JwtFilter` trusts the token for 24 h |
| super-admin cannot revoke themselves; nobody can revoke the super-admin | `AdminAccountServiceTest` | — |
| revoke refused if the admin row is referenced by vendor data (legacy) | `AdminAccountServiceTest` | — |
| token of a deleted user → 401; repeated requests within 60 s hit the database once | `JwtFilterTest` (extend or new) | no live lookup |
| `ROLE_SUPER_ADMIN` comes from the database flag, not from a token claim (a token with a forged claim gains nothing) | `JwtFilterTest` | — |
| **carried over:** `ADMIN` token → 403 on all five `/v1/onboard/**` endpoints | `OnboardingSecurityTest` (exists, red, `b9f3533`) | today 201/200/400 |
| **carried over:** approving an `ADMIN`-owned request keeps `ADMIN` | `OnboardingServiceTest` (exists, red, `b9f3533`) | today becomes `VENDOR` |
| `USER` and `VENDOR` tokens are not rejected by the onboarding door rule | `OnboardingSecurityTest` | passes before (guard) |

The five `AdminUserSeederTest` tests in `b9f3533` test the `.env`-driven seeder this plan removes; they are deleted in the same change, not weakened.

Manual check (shell script, no unit test): `start.sh` writes a random 10-character `ULPF_ADMIN_PASSWORD` into a new `.env`, prints it once, and warns when an existing `.env` still holds `Admin@12345`.

Frontend (Vitest + React Testing Library; added first as its own change, the already approved Task 13a of the Grafana plan, moved earlier):

| Test | File |
|---|---|
| login screen sends the user to `/setup` when `setupRequired` is true; no credentials are pre-filled | `LoginPage.test.jsx` |
| setup: step 1 → step 2; mismatched confirm shows an inline error and sends nothing; success goes to login with the notice | `SetupPage.test.jsx` |
| link page: invalid link shows the message and no form; invite → username + passwords → login; reset → passwords only → login | `LinkPage.test.jsx` |
| "Admins" link and route only for the super-admin | `Navbar.test.jsx`, `ProtectedRoute.test.jsx` |
| Admins screen: invite and reset show the link once with Copy; Revoke asks inline and only then calls the API | `AdminsPage.test.jsx` |

## 5. Files to change

Backend (`core-engine/`):
- `src/main/resources/sqlite/schema.sql` — `is_super_admin` in `CREATE TABLE users` (fresh installs); new `admin_links` table.
- `common/db/SqliteSchemaMigrator.java` (new) — adds the column and the unique index on existing databases, and runs the one-time cleanup of old admins.
- `common/db/UserRepository.java` — `isSuperAdmin`, `createSuperAdmin`, `findAdmins`, `deleteUserWithNotifications`, `isReferencedByVendorData`.
- `common/db/AdminLinkRepository.java` (new).
- `controlplane/service/SetupService.java`, `controlplane/controller/SetupController.java` (new) — `/v1/setup`.
- `controlplane/service/AdminAccountService.java`, `controlplane/controller/AdminAccountController.java` (new) — list, invite link, reset link, revoke (super-admin), and the public `/v1/links/{token}` endpoints.
- `controlplane/cli/ResetSuperAdminCommand.java` (new) — one-shot reset mode (runs instead of the web server when started with the reset option, then exits).
- `common/JwtUtil.java` — `pwv` claim.
- `controlplane/service/AdminUserSeeder.java` — removed.
- `common/JwtFilter.java` — cached live user lookup, `ROLE_SUPER_ADMIN`, excluded paths for setup and invites.
- `common/SpringSecurity.java` — rules in §3.5.
- `controlplane/service/AuthService.java`, `controlplane/controller/AuthController.java` — `superAdmin` in the login response.
- `controlplane/service/OnboardingService.java` — promote only from `USER`.
- Tests: new classes in §4; delete `AdminUserSeederTest.java` (from `b9f3533`).

Frontend (`frontend/`):
- Vitest + RTL setup (Task 13a, own commit first).
- `src/App.jsx` — routes `/setup`, `/link/:token`, `/admin/admins`.
- `src/pages/LoginPage.jsx` — setup redirect, remove pre-filled credentials, store `superAdmin`.
- `src/pages/SetupPage.jsx`, `src/pages/LinkPage.jsx` (new) — reuse `AuthLayout.css`.
- `src/pages/AdminsPage.jsx` (new) — reuse `AdminDashboardPage.css`; small additions there only if needed.
- `src/components/Navbar.jsx`, `src/routes/ProtectedRoute.jsx` — super-admin-only link and route.
- `src/api/admins.js` (new).

Config and docs:
- `.env.example` — `ULPF_ADMIN_USERNAME` / `ULPF_ADMIN_PASSWORD` described as one-time setup credentials.
- `startup/start.sh` — generate the setup password into a new `.env`, print it once with the setup URL; warn on the old default.
- `startup/reset-super-admin.sh` (new) — runs the reset mode; prompts for the password twice.
- `docs/ARCH/API_SPECIFICATION.md` — new endpoints; onboarding endpoints `USER`/`VENDOR`; fix update path.
- `docs/ARCH/ARCHITECTURE.md` — accounts and roles section.
- `README.md` — first-run setup, inviting admins, resetting passwords (regular admins and the super-admin), revoking.
- `docs/frontend/frontend.md` — new screens.
- `to-do-list.md` — item 11 points to this plan.
- `docs/plans/2026-10-29-single-admin-enforcement.md` — Status: Superseded by this plan.
- `docs/plans/2026-10-28-prometheus-grafana-observability.md` — A2 wording: Grafana sessions end when an admin's password changes or the admin is revoked; the cached user lookup comes from this plan; each admin gets their own Grafana login (`auth.proxy` uses the username).

## 6. Steps

- [x] 0. Frontend test framework (2026-10-31): Vitest 3.2.7 (last line supporting Vite 5), jsdom 26.1.0 and @testing-library/react 16.3.3 + dom 10.4.2, exact-pinned dev dependencies; config in `vite.config.js`; `npm test` added to CI. jest-dom and user-event left out (jest-dom 6.10+ needs Node 22, CI and the build image use Node 20; RTL's built-ins are enough). First test `ProtectedRoute.test.jsx` (3 passing; a deliberately broken copy failed as expected). Production bundle unchanged
- [x] 1. Backend red tests (2026-10-31). Full suite: 367 run, 45 red (13 failures, 32 errors), all in the new or changed classes; every pre-existing test still passes. Every red test fails for the stated reason: an assertion on missing behaviour, or `UnsupportedOperationException("not implemented yet ... step N")` from the new skeleton classes (needed so the tests compile; each skeleton names the step that fills it). Two new guard tests already pass (`JwtFilterTest.requestsWithoutATokenAreLeftToTheSecurityRules`, `OnboardingSecurityTest.userAndVendorTokensAreNotStoppedByTheOnboardingRule`). `AdminUserSeederTest` (5 tests of the removed `.env` seeder) deleted.
  Deviations from §4, for maintainability and suite speed: `SetupControllerTest` and `AdminAccountSecurityTest` merged into one `AdminAccountsHttpTest` (one Spring context instead of two; ordered, because setup happens once per database); `ResetSuperAdminCommandTest` is `SuperAdminRecoveryServiceTest` (the logic lives in a service, the command is a thin wrapper); new `AccountLookupTest` for the cache. Shared helpers `testsupport/TestSqlite` and `testsupport/MutableClock`; frozen fixture `src/test/resources/sqlite/schema-before-admin-accounts.sql` (the pre-change schema, so the upgrade test keeps testing an old database). `OnboardingSecurityTest` now creates its accounts directly instead of through the removed seeder (same assertion, still red for the same reason).
  API fixed by the tests: `GET /v1/setup/status`, `POST /v1/setup` (201); `GET /v1/admin/admins`, `POST /v1/admin/admins/invites` (201, returns the token once), `POST /v1/admin/admins/{id}/reset-link` (201), `DELETE /v1/admin/admins/{id}` (204), `GET /v1/admin/admins/links`, `DELETE /v1/admin/admins/links/{linkId}`; public `GET /v1/links/{token}` and `POST /v1/links/{token}` (204; 410 for unknown, expired, used or cancelled). Refusals use one `AccountActionException(status, message)`.
- [x] 2. Migration + repositories (2026-10-31). `schema.sql`: `users.is_super_admin` (fresh installs) and `admin_links`; `SqliteSchemaMigrator` adds the column, the `one_super_admin` index and the one-time cleanup on old databases; `UserRepository` and `AdminLinkRepository` implemented. Additions, test-first: `AdminLinkRepositoryTest` (5) and 5 new `UserRepositoryTest` tests (it now runs on the real schema instead of a hand-written table). Decisions: link times are epoch milliseconds (exact integer comparison, no date parsing); `admin_links` has a CHECK that only `RESET` links have a target; deleting a user also deletes the links it made or that target it, in one transaction. Found while testing: Spring has no SQLite error codes, so a UNIQUE violation surfaced as `UncategorizedSQLException`, not the `DataIntegrityViolationException` the stub's Javadoc promised; `UserRepository` inserts now translate it to `DuplicateKeyException` (a global SQLite translator would be the long-term fix; noted, not done). Caffeine moves to step 4, where `AccountLookup` first uses it. Full suite: 377 run, 40 red, all in the step 3–5 classes; `SetupServiceTest.theDatabaseAllowsOnlyOneSuperAdmin` is now green and duplicates the new repository test (merge in step 7). Docs: `docs/db/DATABASE_SCHEMA.md`, `docs/db/COMMON_DB_GUIDE.md`.
- [ ] 3. Setup service and endpoints; remove `AdminUserSeeder`
- [ ] 4. `JwtFilter` live lookup + `SpringSecurity` rules + onboarding guard
- [ ] 5. Invite and reset links, revoke, super-admin reset command and script
- [ ] 6. Frontend red tests, then screens (setup, invite, admins, navbar, login)
- [ ] 7. Refactor; Javadoc on every new class
- [ ] 8. Full backend suite (baseline 320) + `npm test` + `npm run build`; manual run through the screens on rootless Docker
- [ ] 9. Docs; Status → Done

Each step is its own commit on this branch; the branch can be split into two PRs (backend, frontend) if that reads better.

## 7. Risks and open questions

Decided by the team lead on 2026-10-31:
1. `start.sh` generates a random 10-character setup password (§3.2).
2. Password reset: regular admins through a super-admin reset link; the super-admin through a server command only (§3.4a). Confirmed: "reset button" = the super-admin's **Reset password** button for regular admins; the super-admin's own reset deliberately has no button. Confirmed: a reset ends that person's old sessions (`pwv`).
3. Changing your own password while signed in: on hold (§3.9).
4. Admin passwords: at least 12 characters.
5. Invite and reset links: 12 hours, single use.
6. Old admins (left over from Problem A) are deleted once, on the first start after this ships; never again (§3.1).

Still open or worth knowing:
- **Old admins are gone after the upgrade.** Anyone who logs in with old admin credentials after this ships gets "Invalid Credentials"; the person with the server's `.env` completes setup. Fine while there are no real deployments.
- **The live user lookup in `JwtFilter`** also closes item 11's "removed admin's token works for 24 h" gap; no separate backlog item needed.
- **Not audited:** endpoints outside `/v1/admin/**`, `/v1/analytics/**` and `/v1/onboard/**` may still rely on UI-only gating. A full endpoint role audit remains a candidate follow-up.
- **Branch:** the work continues on `feature/single-admin-enforcement` (which already holds the two carried-over red tests). Renaming it to `feature/admin-accounts` is cosmetic; your call.

## 8. Outcome (fill in when closing)

Deviations from the plan, final test counts, follow-ups.
