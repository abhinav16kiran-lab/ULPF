# Plan: Agent scripts for session orientation and handoff

- **Date:** 2026-11-11
- **Status:** Approved (design agreed in a Q&A with the team lead on 2026-11-11). Implementation not started.
- **Approved by / on:** Adarsh (team lead), 2026-11-11. Each build step below runs test-first on a `scripts/` branch.
- **File name:** `agent-scripts/plans/2026-11-11-orientation-and-handoff.md`

## 1. Goal

A new agent session in ULPF starts with no memory. It greps its way around and misses things: the state of the work, the second implementation of something, and docs that are wrong. A resumed session has the opposite problem: it has its old context, but the repo may have changed under it.

Done means:
- At `startup`, `clear` and `compact`, Claude Code injects a short, correct brief of the repo state, without the agent having to remember anything.
- At `resume` and `fork`, Claude Code injects only what changed since the session last saw the repo, and nothing if nothing changed.
- Between turns, Claude Code learns about files that changed under it, for example a hand edit in the IDE (step 7).
- An agent can write a handoff note in one call, and the next session sees it.
- An agent can ask "which files usually change together with this file?" before it edits.

The order of what the brief targets: (a) the state of the work, (b) where code lives and what it does, (c) docs that contradict the code. Rules are already covered by `AGENTS.md`.

**Who the scripts serve.** The main user is the team lead working with Claude Code. Other agents (for example Antigravity) get the same scripts through general means only: the instructions in `AGENTS.md` and the manual commands of section 3.2. We do not build agent-specific adapters, hook files or transcript parsers for other agents now. The internal design keeps one common form for hook input, so an adapter can be added later as an extension.

## 2. What I verified

Environment:
- Python is 3.10.12. `tomllib` is not available, so configuration is JSON. Standard library only (REUSABLE_SCRIPTS.md rule 3).
- The ULPF repo has 256 commits, 96 in the last 30 days, and 267 tracked files: 175 in `core-engine/`, 46 in `frontend/`, 17 in `docs/`.
- 97 main Java files. 17 of them have no line that starts with `/**`. I did not check if each of those 17 lacks a class Javadoc or has it indented.
- 28 `.js`/`.jsx` files in `frontend/src`.
- 13 Java files hold 44 `@Get/Post/Put/Delete/PatchMapping` annotations.
- The loaded SQLite schema is `classpath:sqlite/schema.sql` (`core-engine/src/main/resources/application.yaml:12`). The other `schema.sql` in `src/main/resources/` is not loaded by that setting. The brief must name the loaded one.
- ClickHouse init SQL: `infra/clickhouse-init/01_raw_events.sql` only. Per-source tables are created at runtime, so the brief must say that.
- Plan files use a `- **Status:** <word> ...` line. Seen values: `Done`, `Draft`, `Approved`, `Superseded`, plus free text after the word. The parser takes the first word only.

Claude Code hooks (official docs `code.claude.com/docs/en/hooks.md`, `memory.md`, `settings.md`, checked by a sub-agent on 2026-11-11 through a summarizing fetch):
- `SessionStart` stdin JSON has `session_id`, `transcript_path`, `cwd`, `hook_event_name`, `source`, `model`, and also `seconds_since_last_response`, `context_tokens`, `prompt_cache_likely_expired`, `estimated_cache_write_usd`.
- `source` is one of `startup`, `resume`, `clear`, `compact`, `fork`. The hook matcher filters on it.
- Plain stdout (exit 0) is added to the model's context. JSON output can set `hookSpecificOutput.additionalContext` (to the model) and `systemMessage` (to the user).
- Each field is capped at 10,000 characters. Above the cap, Claude Code saves the text to a file and injects only the path and a 2,000-character preview. It does not tell the model to read the file. **Not re-checked by me on the page itself:** the exact numbers.
- **Not yet verified:** the input fields and the output contract of `UserPromptSubmit`. Step 7 checks them before it starts.

Other agents (official docs `antigravity.google/docs/rules` and `/hooks`, checked by a sub-agent on 2026-11-11). Recorded for a later extension only:
- Antigravity reads `AGENTS.md` automatically, so the `AGENTS.md` instructions reach it.
- Antigravity has its own hook system (`.agents/hooks.json`) with a different format and no resume or compaction event. Not targeted now.

Claude Code transcript (`~/.claude/projects/<project>/<session>.jsonl`, sampled from this session, version `2.1.312`). The format is **not documented**:
- Each line is a JSON object with `type`. Seen types: `assistant`, `user`, `attachment`, `system`, `file-history-snapshot`, `file-history-delta`, and some internal ones.
- `assistant` lines carry `timestamp`, `sessionId`, `cwd`, `gitBranch`, `version`, and `message.content[]` blocks. Tool calls are blocks with `type: "tool_use"`, `name` and `input`. For example, `Read` has `input.file_path`.
- `file-history-delta` lines carry `trackingPath` (the path relative to the project) for each file that Claude Code backed up before an edit.
- In this session, `Bash` has 861 calls and `Read` has 80. **So most file reads happen through `cat`, `sed -n`, `grep` and `head` inside Bash, not through `Read`.** A detector that looks only at `Read` misses most of the context.

## 3. Proposed approach

### 3.1 Layout

The folder is shared and tracked by git. Only `handoff/` and `.out/` stay on each machine (git-ignored).

```
agent-scripts/
  INDEX.md                     one row for each script, plus a "Planned" list
  plans/                       one approved plan for each change to the scripts
  orient.json                  ULPF-specific sections for orient_session.py
  orient_session.py
  add_handoff_note.py
  _lib/                        shared helpers: git, transcript, output, hook adapter
  tests/                       unittest, fixtures (fake repos, transcript samples)
  handoff/                     one note for each file (git-ignored)
  .out/                        caches and large output (git-ignored)
.claude/settings.json          the Claude Code hooks (committed)
```

Changes to the scripts follow "Changing the scripts" in `REUSABLE_SCRIPTS.md`: a plan in `plans/`, a `scripts/<slug>` branch, approval by the team lead in the pull request, then tests and code. Change a script only when it is necessary.

History of the design, so nobody repeats it:
- First design: the scripts were personal and git-ignored, with a local git repo and no remote. Rejected on 2026-11-11 by the team lead. It was too complex, and the plan-and-branch rule protects shared scripts well enough.
- Shared handoff notes in `docs/handoff/` were rejected. They are one person's session notes, and other people's notes would fill the brief.
- A "stable contract" rule for script options was dropped. The approval rule covers it.

### 3.2 `orient_session.py`: one script, several modes

The Claude Code hook calls `orient_session.py --hook`. In this mode the script reads the hook JSON from stdin, converts it to one common form, and picks the mode from `source`. Any agent can call the same modes by hand:
- `--brief`: mode 1.
- `--changes`: the change report (mode 2) against the last run recorded in this working copy (`.out/last-run.json`). It needs no session id, so it works for any agent.
- `--since <commit>`: the change report against a given commit.
- `--related <file>`: mode 4.
- `--section <name>`: mode 5.

All output follows REUSABLE_SCRIPTS.md (JSON `status`, `summary`, `data`, `warnings`, or `--text`). In `--hook` mode the script prints the Claude Code hook JSON instead: the text for the model in `additionalContext`, and a message for the user in `systemMessage` when there is one.

**Budget:** the hook text is at most 8,000 characters, because of the 10,000-character cap. Full detail goes to `agent-scripts/.out/` and the brief gives the path. A test checks the cap.

**Deterministic output:** no clock time, no run time, and sorted lists. The same repo state gives the same bytes. This lets the prompt cache help when 2 sessions start close together.

#### Mode 1: brief (`startup`, `clear`)

Sections, in this order (most value first):
1. **Work state.** Branch, upstream ahead/behind (from the existing remote refs, no fetch), uncommitted and untracked files (counts plus up to 10 paths), stash count, merge or rebase in progress.
2. **Recent history.** The last 10 commit subjects with short hashes.
3. **Open work.** Plans in `docs/plans/` and `agent-scripts/plans/` whose status is not `Done`, `Superseded` or `Abandoned`, with their unchecked steps (first 5). The top of the backlog (`to-do-list.md`: the "Order of work" list and item titles that are not `[COMPLETED]`).
4. **Handoff notes.** The newest 5, from `agent-scripts/handoff/`.
5. **Hotspots.** The 10 files with the most commits in the last 30 days, each with its strongest "changes together with" file (mode 4).
6. **Code map, short form.** One line for each package or folder, with the file count and the summary of its central class. The full map (one line for each class from its Javadoc or the first comment of a `.js`/`.jsx` file) goes to `.out/map.md`.
7. **Facts the docs get wrong** (from `orient.json`): the loaded `schema.sql`, the ClickHouse tables created at runtime, the test baseline in `AGENTS.md` versus the last known test count.
8. **Drift warnings.** Classes with no summary. Paths named in `docs/ARCH/FILE_GUIDE.md` that do not exist.

The map and the history analysis are cached in `.out/cache/<HEAD>.json`. A second run on the same commit reads the cache.

Why generate the map from the code: the hand-written docs lag the code. Rejected: summarizing `FILE_GUIDE.md`.

#### Mode 2: change report (`resume`, `fork`, `--changes`, `--since`)

Question: what in my context is no longer true?

The baseline is the last state this session saw:
- With the Claude Code hook: `.out/sessions/<session_id>.json`, which every hook run writes. It holds HEAD, branch, a hash of the working tree status, and the plan statuses and notes seen. Fallback: the last `gitBranch` and `timestamp` in the transcript, then the commit at that time from `git reflog`.
- With `--changes`: `.out/last-run.json`. With `--since`: the given commit.

Sections:
- **(a) Stale files.** Files this session read or edited whose content changed since the session last touched them. For each file: who changed it (a commit hash and subject, or "changed outside this session, not committed") and the changed line ranges. Needs a transcript, so only with the Claude Code hook.
- **(b) The ground moved.** The branch was switched, HEAD moved, history was rewritten (the old HEAD is not an ancestor of the new HEAD), uncommitted changes appeared or disappeared, the stash changed, or a merge or rebase is in progress.
- **(c) Other people's work.** New commits, ranked by how many of their files this session touched. New handoff notes. Plans whose status changed.
- **(d) Silence.** If nothing changed, the script prints nothing and writes nothing to the context.

How the script finds "files this session read or edited" from the Claude Code transcript:
- `Read`, `Edit` and `Write` tool calls: `input.file_path`.
- `file-history-delta`: `trackingPath`.
- `Bash` tool calls: tokens in the command that resolve to an existing file under the repo root, for `cat`, `sed`, `head`, `tail`, `grep`, `less`, `awk`, `wc`, `diff`, and also `git show <rev>:<path>`. This is a heuristic. It can add a file that was not read (for example `rm`). For a staleness warning, an extra file is harmless and a missed file is the expensive error.
- The time of last contact for each file is the `timestamp` of the last call that named it.
- "Changed since" means: the file's current content differs from its content at that time, where git can give that content (the commit at that time, and no uncommitted change then). Otherwise, the file's mtime is later than the time of last contact. The report says which test it used.

The transcript format is internal to Claude Code. The parser reads it with no compromise in correctness: it checks the fields it needs, and a test with a saved sample transcript fails at once if the format changes. If the parser meets a format it does not know, the script does not guess. It reports the git-based sections, and it shows a clear warning to the model and to the user (`systemMessage`). The warning names the missing field and the Claude Code `version` from the transcript, so we can update the parser.

**Cost message to the user:** if `prompt_cache_likely_expired` is true and `context_tokens` is above 100,000, the hook sets `systemMessage` with the estimated cost (`estimated_cache_write_usd`). If the open work looks finished (no uncommitted changes, and a handoff note newer than the last edit), the message suggests `/clear`, because the brief and the note restore the state. The threshold is a setting in `orient.json`. The message goes to the user only. A script cannot refill the prompt cache.

#### Mode 3: working set (`compact`)

A compaction summary drops details. After compaction the hook puts back:
- the brief (mode 1), shortened to fit the budget together with the items below;
- the files this session edited (from the transcript), with the current `git diff --stat` for them;
- the unchecked steps of the plan this session edited or read last;
- the last handoff note.

#### Mode 4: related files (`--related <file>`)

The script mines the local git history. For the given file, it lists the files that changed in the same commits, with "changed together in N of M commits" and the last commit that did so. It ignores large commits (more than 30 files) and merge commits, because they link unrelated files.

This targets "agents miss things": before an agent edits `OnboardingService.java`, it sees which docs and tests usually change with it.

#### Mode 5: one section (`--section work|history|plans|notes|hotspots|map|facts|drift`)

The script prints one section in full, with no budget. The agent uses this to drill down from the brief.

#### Mode 6: change check between turns (step 7, `UserPromptSubmit`)

Before each user prompt, the hook compares a cheap fingerprint with the one from the last turn: HEAD, the `git status --porcelain` output, the mtimes of files this session touched, and the handoff folder. If nothing changed, it prints nothing. If something changed, it injects a short version of the change report. It must finish in under 200 ms. A test with a timer guards this.

This catches hand edits in the IDE between two turns. The resume report cannot see those.

#### `orient.json`

The general core works in any git repo. `orient.json` adds the ULPF parts: the plan folders and how to read their status line, the backlog file, the facts in section 7 of the brief, the source roots for the map, and the setting for the cost message. With no `orient.json`, the script runs the general sections only.

### 3.3 `add_handoff_note.py`

- `add_handoff_note.py --text "..." [--title slug]` creates `agent-scripts/handoff/YYYY-MM-DD-<branch>-<slug>.md`. The script fills in the date, branch, HEAD and the agent name. The agent writes only what was done, what was decided, and what is next.
- The notes are local: git ignores `agent-scripts/handoff/`.
- The script only creates new files. It never overwrites a note and never commits. **Agreed exception to the `--dry-run`/`--confirm` rule:** an agent can run it without asking each time, because it creates only new files in `handoff/`.
- The script rejects text that looks like a secret: `ulpf_live_` keys, JWTs (`eyJ...` with 2 dots), `password=`, `secret=`, private key blocks, and long hex or base64 strings. It exits 2 and says which pattern matched.
- A note that records a design decision must point to the plan or `ARCHITECTURE.md` where the decision lives. The script cannot check this. `AGENTS.md` states it.
- Old notes are never deleted automatically.

### 3.4 Hook and instructions

`.claude/settings.json` (committed, so every Claude Code user on the team gets it):
- `SessionStart` with matcher `startup|clear|compact|resume|fork`, command `python3 "$CLAUDE_PROJECT_DIR/agent-scripts/orient_session.py" --hook`, timeout 10 seconds.
- Step 7 adds `UserPromptSubmit` for mode 6.
- The script must never break a session. On any internal error it exits 0, prints a one-line warning for the model and the user, and writes the traceback to `.out/hook-error.log`.

`AGENTS.md` (read by Claude Code and by Antigravity) gets a short section in step 8, when the scripts exist:
1. If your agent does not inject the brief at session start, run `agent-scripts/orient_session.py --brief --text`. After a pause, run `--changes`.
2. Use `--section` to drill down. Do not redo its work by hand.
3. Before a non-trivial edit, run `--related <file>`.
4. When you finish a task, or when the user ends the session, run `add_handoff_note.py`.
5. Do not put secrets, customer data or personal details in a note. Put decisions in the plan or `ARCHITECTURE.md`.

Rejected: a personal `CLAUDE.local.md`. The scripts are shared now, so the instructions belong in `AGENTS.md`.

### 3.5 Other scripts (the "Planned" list in `INDEX.md`)

In this order. Each one gets its own plan in `agent-scripts/plans/` and its own branch before it is built:
1. `summarize_test_results.py`: runs Maven or Vitest, and returns the counts and only the failures (test name, message, first relevant stack line). Large output goes to `.out/`.
2. `check_stack_health.py`: container status, health URLs, and the last errors in each container log.
3. `check_doc_drift.py`: doc claims that the code contradicts. It reuses the drift section of `orient_session.py` and goes deeper (paths, class names, endpoint lists in `API_SPECIFICATION.md`).
4. `find_endpoint.py`: an HTTP route to its controller method and the service it calls.
5. `render_html_screenshot.py`: headless Chrome screenshots with a virtual time budget, as used for the walkthrough slides.

## 4. Test plan (written first)

Framework: `unittest` from the standard library. Tests live in `agent-scripts/tests/`. Each test builds a small fake git repo in a temp folder (`tempfile`, `git init`, scripted commits). They do not touch the ULPF repo, except one smoke test that runs the brief on it read-only.

| Test | Expected to fail before because… |
|---|---|
| Brief lists uncommitted, untracked and stash counts, and the branch | No script exists |
| Brief lists a plan with status `Draft` and hides `Done` and `Superseded`, in both plan folders | No plan parser |
| Brief shows the newest 5 handoff notes, newest first | No note reader |
| Hotspots rank files by commit count in the window | No history miner |
| Map uses the Javadoc summary; a class with no Javadoc appears under drift | No map builder |
| Hook text stays at or below 8,000 characters on a fake repo with 500 files and 50 plans | No budget logic |
| Same repo state gives byte-identical output in 2 runs | No determinism |
| `--hook` with `source: resume` and no change prints nothing | No change report |
| Resume after a new commit lists the commit; a commit that touches a file in the transcript ranks first | No ranking |
| Resume reports a file that this session read with `sed -n` in Bash and that changed since | No Bash path extraction |
| Resume after `git checkout` of another branch reports "branch switched" | No ground checks |
| Resume after a rebase reports "history rewritten" | No ancestry check |
| `--changes` with no session id reports a commit made after the last run | No last-run baseline |
| Transcript with an unknown shape gives the git sections plus a warning naming the field, and exit 0 | No format check |
| Saved sample transcript (redacted copy from Claude Code `2.1.312`) parses to the expected file list | Guards against format changes |
| Cost message appears only when the cache expired and `context_tokens` > threshold | No cost logic |
| `compact` output includes the edited files and the unchecked plan steps | No working-set mode |
| `--related` gives "N of M" and ignores merges and commits with more than 30 files | No co-change miner |
| Mode 6 prints nothing when nothing changed, reports a hand edit, and finishes in under 200 ms | No turn check |
| Internal error in `--hook` exits 0, prints a warning, and writes `hook-error.log` | No error guard |
| `add_handoff_note.py` creates a new file with date, branch and HEAD; a second call with the same title creates a different file | No script |
| `add_handoff_note.py` rejects a JWT, a `ulpf_live_` key and `password=`, and exits 2 | No secret check |
| Every script: `--help` works, bad input exits 2, and the JSON has `status`, `summary`, `data`, `warnings` | REUSABLE_SCRIPTS.md contract |

Before the hook is enabled, I run it once for real in a new Claude Code session for each `source` value and record the result here.

## 5. Files to change

Already done on branch `scripts/shared-agent-scripts` (this plan's pull request):
- `.gitignore` — tracks `agent-scripts/`; ignores `agent-scripts/handoff/` and `agent-scripts/.out/`.
- `REUSABLE_SCRIPTS.md` — the folder is shared; new section "Changing the scripts" (plan, branch, team lead approval, change only when necessary).
- `AGENTS.md` rule 7 — points to that rule.
- `agent-scripts/INDEX.md`, this plan.

Build steps (later branches):
- `agent-scripts/orient_session.py`, `add_handoff_note.py`, `_lib/*.py` — new.
- `agent-scripts/orient.json` — new, the ULPF sections.
- `agent-scripts/tests/` — new, tests and fixtures.
- `agent-scripts/INDEX.md` — a row for each script.
- `.claude/settings.json` — new, the hooks.
- `AGENTS.md` — the short section of 3.4.

## 6. Steps

- [x] 0. Shared doc changes for the personal design, commit `e50d3a7` on `main` (later replaced by step 0b).
- [x] 0b. Shared design: `.gitignore`, `REUSABLE_SCRIPTS.md`, `AGENTS.md` rule 7, this plan, on branch `scripts/shared-agent-scripts`.
- [ ] 1. `_lib/` helpers and the brief (mode 1, sections 1 to 4), test-first.
- [ ] 2. Brief sections 5 to 8 (hotspots, map, facts, drift), cache, budget, determinism.
- [ ] 3. `add_handoff_note.py`.
- [ ] 4. `--hook` and the change report (mode 2, plus `--changes` and `--since`), with the transcript parser and the saved sample.
- [ ] 5. Working set (mode 3) and the cost message.
- [ ] 6. `--related` (mode 4) and its pairs in the brief.
- [ ] 7. Verify the `UserPromptSubmit` contract, then mode 6.
- [ ] 8. `.claude/settings.json` and the `AGENTS.md` section. Run a real session for each `source` value.
- [ ] 9. Full test run, `INDEX.md` rows, `LAST VERIFIED` dates, outcome below.
- [ ] 10. Planned scripts, one at a time (section 3.5).

## 7. Risks and open questions

- **The transcript format can change** with a Claude Code update. Mitigation: the saved-sample test and the loud warning (section 3.2). The git-based sections still work.
- **Bash path extraction is a heuristic.** It can list a file that was only deleted or moved. That costs one extra line. A missed file is worse, so the heuristic is generous.
- **Staleness for uncommitted files uses mtime** when git cannot give the old content. A `touch` with no content change gives a false positive. The report says which test it used.
- **Hook time.** The first run on a new HEAD builds the map and mines history. On 256 commits this must stay under 2 seconds. A test with a timer guards it. If it gets slow, the hook prints the cached brief of the last HEAD plus the delta.
- **The 10,000-character cap** comes from a summarizing fetch of the docs. The 8,000-character budget leaves a margin. Step 8 checks it in a real session.
- **A committed hook runs on every teammate's machine** that uses Claude Code in this repo. It needs `python3` on the `PATH`. The error guard keeps a failure from breaking their session.
- **Other agents get no automatic brief.** Accepted: they use `AGENTS.md` and the manual commands. An adapter can be added later on the common hook form.

## 8. Outcome (fill in when closing)

