# Implementation Plan & Gap Analysis — ULPF AI Mapping Engine

A comprehensive architectural audit comparing the team's design specification ([docs/mapping_engine_design (3).md](file:///home/venzz/Work/Projects/ULPF/docs/mapping_engine_design%20%283%29.md)), the current documentation ([docs/MAPPING_ENGINE.md](file:///home/venzz/Work/Projects/ULPF/docs/MAPPING_ENGINE.md)), and the Java codebase (`com.ulpf.mapping.*`).

---

## 1. Audit Findings & Gap Analysis

The 4-layer cascade architecture (**Layer 1 Alias Lookup** $\rightarrow$ **Layer 2 TF-IDF N-gram** $\rightarrow$ **Layer 3 Levenshtein Typo** $\rightarrow$ **Layer 4 Local ONNX MiniLM**) is **fully implemented** and verified by 54 passing unit tests (`BUILD SUCCESS`).

However, our audit revealed **5 open gaps / unlinked contracts** between the mapping engine, database schema, and control-plane onboarding:

### Gap 1: Missing `is_strict` Column in Database Schema
* **Design Spec:** In `docs/mapping_engine_design (3).md` (lines 686-690), STRICT vendors skip Layer 4 ML matching to avoid false-positive semantic guesses.
* **Current Code:** `MappingEngineOrchestrator.mapFields(List<String> rawFields, boolean vendorIsStrict)` accepts `vendorIsStrict`, but neither `vendors` nor `sources` table in `schema.sql` contains a column for `is_strict`.
* **Proposed Fix:** Add `is_strict INTEGER DEFAULT 0` to the `sources` (or `vendors`) table in SQLite `schema.sql`.

### Gap 2: Human Review Feedback Loop (`mapping_aliases` Auto-Learning)
* **Design Spec:** When a human admin reviews and corrects a proposed mapping during onboarding, the newly confirmed mapping alias should be saved to SQLite `mapping_aliases` with `source = 'human_correction'`. This allows Layer 1 to learn dynamically over time.
* **Current Code:** `AliasRepository` can read `mapping_aliases`, but there is no service method to append new human-approved aliases back to SQLite.
* **Proposed Fix:** Add `saveAlias(String canonicalField, String rawAlias, String source)` to `AliasRepository` and wire it into the mapping approval service.

### Gap 3: Lock Down `mapping_json` Schema Contract
* **Design Spec:** Highlights an unconfirmed contract for the `mapping_json` column in `mapping_versions`.
* **Current Code:** `MappingProposalService` formats `mapping_json` as a field-keyed JSON object:
  ```json
  {
    "SrcAddress": {
      "canonicalField": "src_ip",
      "confidence": 1.0,
      "source": "ALIAS_LOOKUP"
    },
    "dest_port": {
      "canonicalField": "destination_port",
      "confidence": 0.85,
      "source": "TFIDF"
    }
  }
  ```
* **Proposed Action:** Formally document and freeze this JSON contract in `docs/DATABASE_SCHEMA.md` and `docs/db/COMMON_DB_GUIDE.md`.

### Gap 4: `mapping_aliases` Table DDL & Pre-Seeding in `schema.sql`
* **Design Spec:** Specifies `mapping_aliases` table DDL with `alias_key` (normalized, no spaces).
* **Current Code:** `AliasRepository` queries `mapping_aliases`, but `schema.sql` should be verified to contain default seed aliases (`srcip`, `sourceip`, `clientip` $\rightarrow$ `src_ip`, `dstip`, `destip` $\rightarrow$ `destination_ip`, etc.).
* **Proposed Fix:** Ensure `mapping_aliases` DDL and comprehensive initial seed statements exist in `core-engine/src/main/resources/sqlite/schema.sql`.

### Gap 5: Direct Wiring with Onboarding Controller (`/v1/onboard`)
* **Design Spec:** Details the end-to-end flow from `POST /v1/onboard` sample payload submission $\rightarrow$ `MappingEngineOrchestrator.mapFields()` $\rightarrow$ `MappingProposalService.saveMappingVersion()`.
* **Current Code:** `MappingEngineOrchestrator` and `MappingProposalService` are standalone services ready to be called by the Onboarding Controller.
* **Proposed Action:** Ensure `OnboardingService` invokes `orchestrator.mapFields()` on vendor sample payload submission.

---

## User Review Required

> [!IMPORTANT]
> **Key Architectural Decisions for Approval:**
> 1. Should we add `is_strict INTEGER DEFAULT 0` to the `sources` table in SQLite so vendors can select STRICT vs. STANDARD mapping rules?
> 2. Confirm freezing the field-keyed JSON format for `mapping_json` in SQLite `mapping_versions`.
> 3. Confirm adding automatic alias learning when an admin approves human corrections during onboarding.

---

## Proposed Changes

### Database & Schema Updates

#### [MODIFY] [schema.sql](file:///home/venzz/Work/Projects/ULPF/core-engine/src/main/resources/sqlite/schema.sql)
- Add `is_strict INTEGER DEFAULT 0` column to `sources` table.
- Verify DDL for `mapping_aliases` table and populate default seed aliases.

---

### Core Engine Mapping Package

#### [MODIFY] [AliasRepository.java](file:///home/venzz/Work/Projects/ULPF/core-engine/src/main/java/com/ulpf/mapping/repository/AliasRepository.java)
- Add `saveAlias(String canonicalField, String rawAlias, String source)` method to persist human corrections to SQLite and update in-memory lookup cache.

#### [MODIFY] [MappingProposalService.java](file:///home/venzz/Work/Projects/ULPF/core-engine/src/main/java/com/ulpf/mapping/service/MappingProposalService.java)
- Add helper method `recordHumanCorrection(String sourceId, String rawField, String approvedCanonicalField)` to save new human-approved aliases back to Layer 1.

---

## Verification Plan

### Automated Tests
- Run `mvn test` in `core-engine/` to verify all 54 existing tests and new alias learning tests pass cleanly.

### Manual Verification
- Verify SQLite schema initialization on startup with `schema.sql`.
