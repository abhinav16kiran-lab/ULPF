# ULPF — Teammate Onboarding

Follow this in order. Each step should actually be verified working before moving to the next — don't skip the checks.

---

## 1. Clone the repo

```bash
git clone git@github.com:<your-username>/<repo-name>.git
cd <repo-name>
```

If you don't have SSH access set up yet for GitHub, ask whoever manages the repo to add you as a collaborator first, and set up your own SSH key (`ssh-keygen -t ed25519`, add the public key to your GitHub account under Settings → SSH and GPG keys).

---

## 2. Install the tools

Follow `docs/ULPF_Dev_Environment_Setup.md` for your OS (Linux or Windows section). At minimum, for working on the core engine right now, you need:

- Java 21 LTS + Maven
- Podman (+ `podman-compose` on Linux, or Podman Desktop on Windows via WSL2)
- `sqlite3` CLI — **not covered in the setup doc**, install separately: `sudo apt install -y sqlite3` (Linux) — this is only for manually inspecting the database, separate from the `sqlite-jdbc` Maven dependency the app itself uses.
- Git

You do **not** need Node, Python, Vector, or llama.cpp yet — those aren't in use until later phases (frontend, analytics service, ingestion pipeline, AI mapping).

Verify each tool with:
```bash
java -version    # should show 21.x
mvn -version
podman --version
sqlite3 --version
```

---

## 3. Start Podman's socket (Linux only, one-time)

```bash
systemctl --user enable --now podman.socket
systemctl --user status podman.socket   # should show "active (listening)"
```

Windows users: Podman Desktop handles this automatically once it's installed and a machine is initialized (`podman machine start`).

---

## 4. Set up your `.env` file

```bash
cp .env.example .env
```

Open `.env` and fill in real values (ask a teammate for the shared dev passwords if this is a shared local ClickHouse setup, or just use your own placeholder passwords for a fully local instance). `.env` is gitignored — never commit it.

---

## 5. Start ClickHouse

Run this from the **repo root** (not from inside `infra/`) — `podman compose` looks for `.env` relative to your current directory, not relative to `compose.yaml`:

```bash
podman compose -f infra/compose.yaml up -d clickhouse
```

Verify:
```bash
podman ps                          # should show ulpf-clickhouse running
curl http://localhost:8123/ping    # should return "Ok."
```

---

## 6. Set up the SQLite control-plane database

```bash
mkdir -p core-engine/data
sqlite3 core-engine/data/control-plane.db < core-engine/sqlite-init/schema.sql
sqlite3 core-engine/data/control-plane.db ".tables"
```

The last command should list all 7 tables: `credentials`, `mapping_versions`, `notifications`, `onboarding_requests`, `sources`, `users`, `vendors`.

---

## 7. Build and run the core engine

```bash
cd core-engine
mvn compile
mvn spring-boot:run
```

Watch the startup log — it should show Tomcat starting on port 8080 and no errors. Stop it with `Ctrl+C` once confirmed.

---

## 8. Read before writing code

- `docs/ARCHITECTURE.md` — control plane vs data plane split, diagrams, data model, mapping lifecycle
- `docs/FILE_GUIDE.md` — plain description of what every file in the repo does
- `docs/ULPF_Prototype_Technical_Design.md` — the original full design doc everything is based on
- `core-engine/sqlite-init/schema.sql` — the actual current database schema (source of truth over any doc description of it)

---

## Troubleshooting

| Problem | Fix |
|---|---|
| `podman compose` can't find `.env` / variables aren't substituted | Run the command from the repo root, not from inside `infra/` |
| `podman: command not found` or compose fails silently | Podman's user socket isn't running — see step 3 |
| `mvn -version` fails but `java -version` works (Windows) | `JAVA_HOME` isn't set — see the note in `docs/ULPF_Dev_Environment_Setup.md` §3.2 |
| Foreign key constraints don't seem to be enforced | Make sure you're connecting via the app (which sets `?foreign_keys=on` in the JDBC URL) — a plain `sqlite3` CLI session needs `PRAGMA foreign_keys = ON;` run manually, it's off by default |
| `mvn compile` says "no POM in this directory" | You're not in `core-engine/` — that's where `pom.xml` lives, not the repo root or `core-engine/src/` |

---

*If you hit something not covered here, add it to this file once you've solved it — this doc should grow as the team hits real issues.*
