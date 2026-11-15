# Plan: Plain-text log lines got 415 at `POST /v1/events`

- **Date:** 2026-11-15
- **Status:** Done (2026-11-15)
- **Approved by / on:** Adarsh (team lead), 2026-11-15, in chat (option A)
- **File name:** `docs/plans/2026-11-15-plain-text-ingest.md`
- **Found by:** the first smoke run of the item 13 performance kit against the real stack. Half of the events (Syslog, CEF, LEEF) were refused.

## 1. Goal

A raw log line sent as plain text is accepted and stored byte for byte. JSON bodies work as before.

## 2. What I verified

- Every `text/plain`, `application/octet-stream` and `application/x-www-form-urlencoded` body got `415`. Only a JSON string (`"<134>1 …"` with `application/json`) was accepted. `API_SPECIFICATION.md` promised "plain text for Syslog/CEF".
- Cause: `EventController` bound every body to `@RequestBody Object`. Spring has no message converter from text to `Object`. The `consumes` list named `text/plain`, so the mapping matched, but the body could not be read.
- Side effect: for text, even the missing-API-key check never ran, because the 415 came first.

## 3. Approach

Two handlers on `POST /v1/events`:
- `application/json` reads `Object`, which is unchanged. The body is now required.
- `!application/json` reads the raw request stream and decodes it with the declared charset, or UTF-8.

The reasons and the rejected alternative are in `ARCHITECTURE.md` §3.3, next to the feature.

## 4. Tests (written first)

`EventControllerTest` (new, 11 tests):
- Red before the fix (`415`): text/plain, no charset (UTF-8), declared charset, octet-stream, no type, form type, blank body, missing key.
- Regression guards that passed before and after: a JSON object, and a JSON string.
- Added during the fix: an empty body of either kind gets `400`.

## 5. Outcome

- Two problems appeared while making the tests green. Both are fixed and covered by tests:
  - For a form type, Spring rebuilt the body from parsed form fields and re-encoded it (`src=` became `src%3D`). So the text handler reads the raw stream, not `@RequestBody`.
  - With an optional JSON body, both mappings matched an empty request, and Spring answered `409` (ambiguous handler). So the JSON body is now required, and the text mapping is `!application/json`.
- Full backend suite: 414 tests, 0 failures (403 + 11).
- Container check (Podman 3.4.4, the real filter chain):
  - text/plain, no type, `curl -d`, octet-stream and the nginx path on `:3000`: `202`;
  - JSON object: `202`;
  - empty body with and without a type: `400`;
  - a UTF-8 CEF line without a charset was stored with its umlauts intact;
  - 6 sends of the same Syslog line are stored byte for byte.
- Docs:
  - `API_SPECIFICATION.md`: a content-type table, a raw-line example and the error codes. This replaces the promise that was wrong.
  - `README.md`: a plain-text `curl` example.
  - `ARCHITECTURE.md` §3.3: the body forms and the decisions. Two bullets that the code contradicted were also corrected: "Non-Blocking Micro-Batching" and "0% Ingestion Overhead … Merkle hashing … out-of-band". The request thread that fills a queue runs the synchronized flush, which includes the Merkle step.
