# ULPF AI Mapping Engine - Phase 1 Implementation Report

## Status: Phase 1 Complete, Layer 4 Pending

Implementation completed on: September 2, 2026

---

## Files Created

### Configuration & Models
1. `/core-engine/src/main/java/com/ulpf/mapping/config/MappingConfig.java`
   - Loads mapping thresholds from application.yaml

2. `/core-engine/src/main/java/com/ulpf/mapping/model/NormalizedField.java`
   - Immutable model for preprocessed field data

3. `/core-engine/src/main/java/com/ulpf/mapping/model/MappingCandidate.java`
   - Immutable model for scored candidates from layers

4. `/core-engine/src/main/java/com/ulpf/mapping/model/MappingProposal.java`
   - Final output model for one field's mapping decision

5. `/core-engine/src/main/java/com/ulpf/mapping/model/TrainingExample.java`
   - Model for TF-IDF training examples

### Services
6. `/core-engine/src/main/java/com/ulpf/mapping/service/FieldPreprocessor.java`
   - Step 0: Normalizes raw field names using three-step regex

7. `/core-engine/src/main/java/com/ulpf/mapping/service/AliasLookupService.java`
   - Layer 1: Dictionary lookup for exact matches

8. `/core-engine/src/main/java/com/ulpf/mapping/service/TfidfTrainingStore.java`
   - Layer 2: Builds TF-IDF vocabulary and vectorizes training examples

9. `/core-engine/src/main/java/com/ulpf/mapping/service/TfidfMatchingService.java`
   - Layer 2: Nearest-neighbor matching using TF-IDF vectors

10. `/core-engine/src/main/java/com/ulpf/mapping/service/ConfidenceEvaluator.java`
    - Shared confidence checking logic for Layers 2 and 3

11. `/core-engine/src/main/java/com/ulpf/mapping/service/TypoMatchingService.java`
    - Layer 3: Edit-distance matching for typo detection

12. `/core-engine/src/main/java/com/ulpf/mapping/service/MappingEngineOrchestrator.java`
    - Sequential pipeline coordinator for all layers

13. `/core-engine/src/main/java/com/ulpf/mapping/service/MappingProposalService.java`
    - Temporary service for persisting mapping proposals to database

### Repositories
14. `/core-engine/src/main/java/com/ulpf/mapping/repository/AliasRepository.java`
    - Loads alias dictionary from SQLite at startup

### Resource Files
15. `/core-engine/src/main/resources/mapping/training_examples.json`
    - **PLACEHOLDER**: 10-entry sample dataset for TF-IDF training

16. `/core-engine/src/main/resources/mapping/canonical_descriptions.json`
    - **PLACEHOLDER**: 6 canonical field descriptions for Layer 4 embeddings

### Test Files
17. `/core-engine/src/test/java/com/ulpf/mapping/service/FieldPreprocessorTest.java`
    - 9 tests: acronyms, camelCase, separators, token creation

18. `/core-engine/src/test/java/com/ulpf/mapping/service/AliasLookupServiceTest.java`
    - 4 tests: exact match, miss, space handling, no fuzzy matching

19. `/core-engine/src/test/java/com/ulpf/mapping/service/ConfidenceEvaluatorTest.java`
    - 9 tests: confidence/gap thresholds, meaningful token validation

20. `/core-engine/src/test/java/com/ulpf/mapping/service/TypoMatchingServiceTest.java`
    - 7 tests: short/long word thresholds, edit distance, score sorting

21. `/core-engine/src/test/java/com/ulpf/mapping/service/TfidfMatchingServiceTest.java`
    - 5 tests: k=3 neighbors, score ordering, layer labels

22. `/core-engine/src/test/java/com/ulpf/mapping/service/MappingEngineOrchestratorTest.java`
    - 8 tests: all short-circuit paths, exception handling, batch processing

23. `/core-engine/src/test/java/com/ulpf/mapping/service/MappingProposalServiceTest.java`
    - 4 tests: batch persistence, JSON structure, status handling

---

## Files Edited

1. `/core-engine/src/main/resources/sqlite/schema.sql`
   - **Added**: `mapping_aliases` table definition (appended to end)
   - Note: Flagged as proposal needing team sign-off per design doc

2. `/core-engine/src/main/resources/application.yaml`
   - **Added**: `mapping:` configuration block with three thresholds

3. `/core-engine/pom.xml`
   - **Added**: `jackson-databind` dependency
   - **Added**: `jakarta.annotation-api` dependency

---

## Test Results

**All 47 tests PASS**

Breakdown by test suite:
- FieldPreprocessorTest: 9/9 ✓
- AliasLookupServiceTest: 4/4 ✓
- ConfidenceEvaluatorTest: 9/9 ✓
- TypoMatchingServiceTest: 7/7 ✓
- TfidfMatchingServiceTest: 5/5 ✓
- MappingEngineOrchestratorTest: 8/8 ✓
- MappingProposalServiceTest: 4/4 ✓
- ModelLifecycleManagerTest (existing): 1/1 ✓

---

## Plan Tasks Completed

### Phase 0 - Schema & Config ✓
- [x] Task 1: Add `mapping_aliases` table to schema.sql
- [x] Task 2: Confirm `mapping_embeddings` table exists (verified)
- [x] Task 3: Add mapping thresholds to application.yaml
- [x] Task 4: Create `MappingConfig.java`
- [~] Task 5: Create resource data files (placeholder datasets only)

### Phase 1 - Shared Models ✓
- [x] Task 6: Create `NormalizedField.java`
- [x] Task 7: Create `MappingCandidate.java`
- [x] Task 8: Create `MappingProposal.java`

### Phase 2 - Preprocessing ✓
- [x] Task 9: Create `FieldPreprocessor.java`

### Phase 3 - Layer 1: Dictionary Lookup ✓
- [x] Task 10: Create alias repository loader
- [x] Task 11: Create `AliasLookupService.java`

### Phase 4 - Layer 2: TF-IDF ✓
- [x] Task 12: Create `TfidfTrainingStore.java`
- [x] Task 13: Create `TfidfMatchingService.java`

### Phase 5 - Confidence Check ✓
- [x] Task 14: Create `ConfidenceEvaluator.java`

### Phase 6 - Layer 3: Typo Matching ✓
- [x] Task 15: Create `TypoMatchingService.java`

### Phase 7 - Layer 4: MiniLM Semantic Match (SKIPPED)
- [ ] Task 16: Create `EmbeddingRepository.java` - **Skipped: blocked on Layer 4**
- [ ] Task 17: Create setup for `mapping_embeddings` - **Skipped: blocked on Layer 4**
- [ ] Task 18: Create `EmbeddingClient.java` - **Skipped: Adarsh's signatures not final**
- [ ] Task 19: Create `EmbeddingMatchingService.java` - **Skipped: depends on Task 18**

### Phase 8 - Orchestrator ✓
- [x] Task 20: Create `MappingEngineOrchestrator.java` (with Layer 4 TODO placeholder)

### Phase 9 - Persisting Batch ✓
- [x] Task 21: Create `MappingProposalService.java` (temporary bridge implementation)

### Phase 10 - Tests ✓
- [x] Task 22: Unit tests for `FieldPreprocessor`
- [x] Task 23: Unit tests for `AliasLookupService`
- [x] Task 24: Unit tests for `ConfidenceEvaluator`
- [x] Task 25: Unit tests for `TypoMatchingService`
- [x] Task 26: Unit tests for `TfidfTrainingStore` / `TfidfMatchingService`
- [ ] Task 27: Unit tests for `EmbeddingMatchingService` - **Skipped: Layer 4 not implemented**
- [x] Task 28: Integration tests for `MappingEngineOrchestrator` (Layer 4 test cases skipped)
- [x] Task 29: Integration test for `MappingProposalService`

---

## Tasks Skipped (Per Skip List)

### Blocked on Raja's Dataset
- **Task 5**: Real content of `training_examples.json` and `canonical_descriptions.json`
  - Status: Placeholder datasets created with 10 training examples and 6 canonical descriptions
  - Action needed: Replace with Raja's actual `training_dataset` and `canonical_registry`

### Blocked on Adarsh's Model Lifecycle Functions
- **Task 18**: `EmbeddingClient.java`
  - Status: Not implemented
  - Reason: Function signatures not final
  - Action needed: Implement once Adarsh's branch lands with `loadModel()`, `isLoaded()`, `markUsed()`, and scheduled auto-unload

- **Task 19**: `EmbeddingMatchingService.java`
  - Status: Not implemented
  - Reason: Depends on EmbeddingClient
  - Action needed: Implement after Task 18 is complete

- **Task 16**: `EmbeddingRepository.java`
  - Status: Not implemented
  - Reason: Only needed for Layer 4
  - Action needed: Implement when Layer 4 is built

- **Task 17**: Setup routine for `mapping_embeddings`
  - Status: Not implemented
  - Reason: Only needed for Layer 4
  - Action needed: Implement one-time embedding population routine

- **Task 27**: Unit tests for `EmbeddingMatchingService`
  - Status: Not implemented
  - Reason: Service doesn't exist yet
  - Action needed: Write tests after Task 19 is complete

### Orchestrator Layer 4 Integration
- **Task 20 Step 6**: Layer 4 blended scoring
  - Status: Placeholder implementation returns NONE with best prior score
  - Location: `MappingEngineOrchestrator.java` line 77-80
  - TODO comment: "Layer 4 (embedding hybrid) not yet implemented — pending Adarsh's model-lifecycle functions; see plan Phase 7."
  - Action needed: Replace placeholder with actual Layer 4 call

- **Task 28**: Orchestrator tests for Layer 4 accept/reject cases
  - Status: Other 6 test cases implemented, Layer 4 cases omitted
  - Action needed: Add 2 more test cases after Layer 4 is implemented

---

## What's Needed to Finish Layer 4

Once the following become available, these files need to be created or updated:

### To Create:
1. **EmbeddingClient.java** (`mapping/service/`)
   - Wrap Adarsh's four model-lifecycle functions
   - Implement `getEmbedding(String rawText) -> double[]`
   - Handle load/check/free/auto-unload lifecycle

2. **EmbeddingRepository.java** (`mapping/repository/`)
   - Read `mapping_embeddings` table
   - Load canonical field embeddings into memory
   - Cache results after first read

3. **Embedding setup routine** (location TBD, possibly a migration script)
   - One-time population of `mapping_embeddings`
   - Embed each canonical field's description sentence
   - Insert one row per canonical field

4. **EmbeddingMatchingService.java** (`mapping/service/`)
   - Implement `matchWithFallback(String rawFieldName, double bestPriorScore) -> MappingCandidate`
   - Embed incoming field, compare against canonical embeddings
   - Apply scaling formula: `clamp((rawSimilarity - 0.12) / 0.30, 0.0, 1.0)`
   - Blend with prior scores: `0.3 * bestPriorScore + 0.7 * scaledSimilarity`

5. **EmbeddingMatchingServiceTest.java** (`test/.../service/`)
   - Test blending formula with mocked embeddings
   - Test scaling/clamping at boundaries
   - Test fallback when bestPriorScore == 0.0

### To Update:
1. **MappingEngineOrchestrator.java**
   - Replace Step 6 placeholder (lines 77-80) with actual Layer 4 call
   - Remove TODO comment
   - Inject `EmbeddingMatchingService` as dependency
   - Check blended score against `config.getHybridAcceptanceThreshold()`

2. **MappingEngineOrchestratorTest.java**
   - Add test case: Layer 4 accept (blended score >= 0.50)
   - Add test case: Layer 4 reject → NONE (blended score < 0.50)

3. **training_examples.json**
   - Replace placeholder with Raja's actual `training_dataset`

4. **canonical_descriptions.json**
   - Replace placeholder with Raja's actual `canonical_registry` descriptions

---

## Open Questions (From Design Doc)

These remain open and were NOT resolved by this implementation:

1. **STRICT/STANDARD flag location**
   - No column exists yet in `vendors` or `sources` table
   - Orchestrator accepts `vendorIsStrict` as a parameter for now
   - Needs schema decision and migration

2. **Exact `mapping_json` shape**
   - Current implementation: field-keyed object
   - Needs confirmation: array vs. object, exact field names
   - Affects human review UI and event ingestion

3. **Version numbering for `mapping_versions`**
   - Who computes the next version integer?
   - Current implementation uses placeholder version=1
   - Assumed to be handled by Adarsh's eventual save function

4. **TF-IDF numerical parity with Raja's Python**
   - Hand-written Java implementation vs. scikit-learn
   - Side-by-side testing needed later (not a blocker now)

5. **Layer 3 typo threshold tuning**
   - Current: 1 edit for ≤4 chars, 30% for longer words
   - Needs validation against real vendor data

6. **Value/type evidence**
   - Using sample field values, not just names
   - Confirmed as later phase, not in this implementation

---

## Next Steps

### Immediate (to complete Layer 4):
1. Wait for Adarsh's model-lifecycle branch to land with final signatures
2. Wait for Raja's actual training dataset and canonical descriptions
3. Implement the 4 files listed in "To Create" section above
4. Update the 2 files listed in "To Update" section above
5. Run all tests again, verify Layer 4 integration

### Before Production:
1. Resolve the 6 open questions listed above with the team
2. Get team sign-off on `mapping_aliases` table schema
3. Populate `mapping_aliases` with seed data
4. Run side-by-side comparison of Java TF-IDF vs. Raja's Python
5. Tune Layer 3 thresholds against real vendor schemas
6. Replace MappingProposalService with call to Adarsh's shared save function
7. Add integration with `/v1/onboard` endpoint (Disha/Bhushan's side)

---

## Summary

**Phase 1 Status: Complete**

Layers 1-3 are fully implemented, tested, and working. The orchestrator is complete except for Layer 4, which has a clearly-marked TODO placeholder. All Phase 0-6, 8, 9 tasks are done. Test coverage is comprehensive for implemented layers.

Layer 4 is intentionally deferred, not abandoned. The implementation can proceed as soon as Adarsh's function signatures and Raja's real dataset are available. The placeholder resource files allow the rest of the system to compile and be tested today.

Total files created: 23  
Total files edited: 3  
Total tests: 47 (all passing)  
Build status: ✓ SUCCESS
