# ULPF — Development Environment Setup

SIH Prototype • Cross-platform (Linux + Windows) setup reference

This document lists every piece of software, its target version, and the install path for each OS. The rule of thumb across both platforms: **ClickHouse and Vector run inside Podman containers on both OSes** (identical behavior for everyone); **everything else (Java, Node, Python, llama.cpp) runs natively** on the host for a fast local dev loop.

---

## 1. Shared Version Matrix

| Layer | Software | Version | Notes |
|---|---|---|---|
| Core engine | Java (Temurin/Corretto) | **21 LTS** | Language for control plane + data plane |
| Core engine | Spring Boot | **4.1.0** | Maven-based. Built on Spring Framework 7 / Jakarta EE — use `jakarta.*` imports, not `javax.*`, everywhere (persistence, validation, servlet, etc.) |
| Core engine | Maven | latest 3.9.x | Build tool |
| Control-plane DB | SQLite | bundled via `sqlite-jdbc` (latest 3.4x.x) | Embedded, no server |
| Event storage | ClickHouse | **26.3 LTS** | Runs in a Podman container on both OSes |
| Ingestion/buffer/router | Vector | **0.55.0** | Native binary on both OSes (or containerized — see §4) |
| AI mapping engine | llama.cpp | latest `master` (pin exact commit) | Run as local HTTP sidecar (`llama-server`) |
| AI model | Qwen2.5-3B-Instruct or Phi-3.5-mini | GGUF, Q4_K_M quant | Evaluate both, pick one |
| Analytics service | Python | **3.13** | Read-only consumer of ClickHouse |
| Analytics service | FastAPI | latest 0.14x.x, **pinned exact** | Pin in `pyproject.toml`, don't track latest |
| Analytics service | uv | latest | Package/env manager |
| Analytics service | clickhouse-connect | latest stable | Official Python ClickHouse driver |
| Frontend | Node.js | **24.x LTS** ("Krypton") | |
| Frontend | React | **19.x** | |
| Containerization | Podman | latest stable | See OS-specific setup below |
| Version control | Git | latest stable | See line-ending config in §5 |

---

## 2. Linux Setup

Assumes Ubuntu 22.04/24.04 or similar Debian-based distro. Adjust package manager commands for other distros.

### 2.1 Java 21 LTS + Maven
```bash
sudo apt update
sudo apt install -y openjdk-21-jdk maven
java -version   # verify 21.x
mvn -version
```
Spring Boot 4.1.0 requires Java 17 minimum and supports up to Java 26 — Java 21 LTS is comfortably within range.
Alternative: use `sdkman` if you want to switch JDK versions easily across projects:
```bash
curl -s "https://get.sdkman.io" | bash
sdk install java 21-tem
sdk install maven
```

### 2.2 Node.js 24 LTS + npm
Use `nvm` to pin the exact major version cleanly:
```bash
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.1/install.sh | bash
nvm install 24
nvm use 24
node -v   # verify v24.x
```

### 2.3 Python 3.13 + uv
```bash
curl -LsSf https://astral.sh/uv/install.sh | sh
uv python install 3.13
uv --version
```

### 2.4 Podman
```bash
sudo apt install -y podman podman-compose
podman --version
```
Native on Linux — no VM layer needed.

### 2.5 Vector
```bash
curl --proto '=https' --tlsv1.2 -sSfL https://sh.vector.dev | bash
vector --version   # verify 0.55.0
```

### 2.6 llama.cpp
```bash
git clone https://github.com/ggml-org/llama.cpp
cd llama.cpp
git checkout <pin-a-specific-commit-hash>
cmake -B build
cmake --build build --config Release -j$(nproc)
```
Download the chosen GGUF model into `models/` and start the sidecar:
```bash
./build/bin/llama-server -m models/<model>.gguf --port 8081
```

### 2.7 Git
```bash
sudo apt install -y git
git config --global core.autocrlf input
```

---

## 3. Windows Setup

Windows **cannot run ClickHouse natively** (no production server build) and Podman itself requires a Linux backend. The path below uses **WSL2** purely as Podman's container backend — you still develop with native Windows installs of Java, Node, Python, and llama.cpp for a fast edit/debug loop.

### 3.0 Prerequisite: Enable WSL2 (one-time, do this first)
Open PowerShell **as Administrator**:
```powershell
wsl --install
```
This installs WSL2 and a default Linux distro (Ubuntu). Reboot when prompted, then let the Ubuntu setup finish (it'll ask you to create a Linux username/password — any values are fine, you won't use this distro directly for dev).

Verify:
```powershell
wsl --status
```

### 3.1 Podman Desktop (uses the WSL2 backend automatically)
1. Download and install **Podman Desktop** from https://podman-desktop.io
2. On first launch, it detects WSL2 and offers to initialize a Podman machine — accept this.
3. Verify from PowerShell or a terminal:
```powershell
podman machine start
podman --version
```
From here, `podman` and `podman-compose`/`compose.yaml` commands behave identically to Linux — ClickHouse (and optionally Vector) run inside this Podman machine.

### 3.2 Java 21 LTS + Maven
1. Install via the **Eclipse Adoptium (Temurin) MSI installer**: https://adoptium.net — pick JDK 21 LTS, Windows x64 MSI, check "Add to PATH" and "Set JAVA_HOME" during install.
2. Install Maven: download the binary zip from https://maven.apache.org/download.cgi, extract, add `bin` to your `PATH` environment variable.
3. Verify in a new terminal:
```powershell
java -version
mvn -version
```

### 3.3 Node.js 24 LTS + npm
1. Download the **Windows Installer (.msi)** for the 24.x LTS line from https://nodejs.org
2. Run it (default options are fine — it adds Node to PATH automatically).
3. Verify:
```powershell
node -v
npm -v
```
(Optional) If you want version switching later, `nvm-windows` (a separate tool from Linux's `nvm`) works too: https://github.com/coreybutler/nvm-windows

### 3.4 Python 3.13 + uv
1. Install via `winget` (built into Windows 11 / recent Windows 10):
```powershell
winget install Python.Python.3.13
```
2. Install `uv`:
```powershell
powershell -ExecutionPolicy ByPass -c "irm https://astral.sh/uv/install.ps1 | iex"
```
3. Verify:
```powershell
python --version
uv --version
```

### 3.5 Vector
Two options — pick one and keep it consistent with what Linux teammates use:
- **Native Windows install** (simplest, has a proper MSI):
```powershell
winget install Vector
```
or download the `.msi` directly from https://vector.dev/docs/setup/installation/operating-systems/windows/
- **Containerized via Podman** (recommended if you want byte-identical pipeline behavior with the Linux setup) — runs as part of `compose.yaml`, nothing to install natively.

### 3.6 llama.cpp
Requires a Rust/MSVC build environment:
1. Install **Visual Studio Build Tools** (C++ workload) or full Visual Studio Community — needed for the MSVC toolchain.
2. Install **CMake**: `winget install Kitware.CMake`
3. Build:
```powershell
git clone https://github.com/ggml-org/llama.cpp
cd llama.cpp
git checkout <pin-a-specific-commit-hash>
cmake -B build
cmake --build build --config Release
```
4. Run the sidecar the same way as Linux:
```powershell
.\build\bin\Release\llama-server.exe -m models\<model>.gguf --port 8081
```

### 3.7 Git for Windows
1. Download from https://git-scm.com/download/win
2. During install, when prompted for line-ending behavior, choose **"Checkout as-is, commit Unix-style line endings"** (equivalent to `core.autocrlf=input`).
3. Verify config after install:
```powershell
git config --global core.autocrlf
```
Should report `input`.

---

## 4. ClickHouse — same on both OSes

ClickHouse is **never installed natively** by anyone on this team — Windows can't run it natively at all, and running it identically via Podman on Linux keeps both dev environments byte-for-byte consistent. It's defined once in the shared `infra/compose.yaml` and started with:
```bash
podman compose up -d clickhouse
```
This command is identical on Linux and Windows (Windows just routes through the Podman machine automatically).

---

## 5. Cross-Platform Gotchas to Handle Once, Up Front

1. **Line endings**: `core.autocrlf` is set above for both OSes (`input` on Linux/macOS via config, matching MSVC-equivalent choice on Windows install). Add a `.gitattributes` file to the repo root forcing LF for `*.java *.py *.ts *.tsx *.yaml *.yml *.sql` regardless of local git config, so this can't drift per-machine.
2. **Case sensitivity**: Linux filesystems are case-sensitive, Windows/WSL2 (via Windows-mounted paths) are not. A misnamed import (`Config.java` vs `config.java`) fails silently on Windows and breaks on Linux/CI — worth a lint/CI check later, not urgent now.
3. **Scripts**: avoid bash-only setup/run scripts if both OSes need to run them directly. Either provide paired `.sh` / `.ps1` scripts, or standardize on a tool with native builds for both (e.g. `just`), or lean on `podman compose` commands directly since those work identically everywhere.
4. **Path separators**: keep all config (application.yaml, vector config, .env files) using forward slashes and relative paths — both Java and Node handle this fine cross-platform; avoid hardcoding `C:\...` or `/home/...` absolute paths anywhere in committed config.

---

## 6. Quick Verification Checklist (run after setup, either OS)

```
java -version        # 21.x
mvn -version          # 3.9.x
node -v                # v24.x
npm -v
python --version      # 3.13.x
uv --version
podman --version
podman machine list    # Windows only — should show a running machine
vector --version       # 0.55.0 (if native)
git config --global core.autocrlf   # should be "input" (or Windows equivalent)
```

---

*End of setup document. Next: directory structure (see project discussion) and `infra/compose.yaml` for ClickHouse.*
