# Agent scripts

Read this file at the start of a multi-step tool task. See `../REUSABLE_SCRIPTS.md` for the rules. Do not create or change a script without an approved plan in `plans/` on a `scripts/` branch.

| Script | Does | Use when | Side effects |
|---|---|---|---|

## Planned (in build order)

1. `orient_session.py`: the session brief, the change report on resume, the working set after compaction, related files from git history, and a change check between turns. Plan: `plans/2026-11-11-orientation-and-handoff.md`.
2. `add_handoff_note.py`: creates one handoff note in `handoff/`. Rejects text that looks like a secret.
3. `summarize_test_results.py`: runs Maven or Vitest, returns counts and only the failures.
4. `check_stack_health.py`: container status, health URLs, last errors in each log.
5. `check_doc_drift.py`: doc claims that the code contradicts.
6. `find_endpoint.py`: an HTTP route to its controller method and service.
7. `render_html_screenshot.py`: headless Chrome screenshots with a virtual time budget.
