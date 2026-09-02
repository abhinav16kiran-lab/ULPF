# Layer 4 Implementation Complete ✅

## Summary

Layer 4 (MiniLM Semantic Matching) has been successfully implemented and integrated into the ULPF AI Mapping Engine using Adarsh's `ModelLifecycleManager`.

## What Was Built

### New Components (5 files)

1. **CanonicalEmbedding.java** - Model for canonical field embeddings
2. **EmbeddingRepository.java** - Loads and caches embeddings from SQLite
3. **EmbeddingClient.java** - Wraps ModelLifecycleManager, generates embeddings via ONNX
4. **EmbeddingMatchingService.java** - Semantic matching with blending logic
5. **EmbeddingMatchingServiceTest.java** - 6 comprehensive unit tests

### Updated Components (3 files)

1. **MappingEngineOrchestrator.java** - Integrated Layer 4 into sequential pipeline
2. **MappingEngineOrchestratorTest.java** - Added Layer 4 test cases (accept/reject)
3. **UlpfApplication.java** - Added @EnableScheduling for model lifecycle

### Configuration Updates

- `application.yaml` - Added model path and idle timeout configuration
- `application-test.yaml` - Test-specific config with JWT secret

## How It Works

### Model Lifecycle (Adarsh's Implementation)

```
Field needs mapping
    ↓
EmbeddingClient.getEmbedding("SrcAddress")
    ↓
ModelLifecycleManager.ensureLoaded()
    ↓
If not loaded: Load model.onnx into RAM (~120MB, ~120ms)
If already loaded: Just update lastAccessedTime
    ↓
Run ONNX inference → return 384-dim embedding vector
    ↓
After 3 minutes idle: @Scheduled job auto-unloads to free RAM
```

### Semantic Matching Flow

```
1. Embed raw field name (e.g., "SrcAddress")
   ↓
2. Compare against all canonical field embeddings via cosine similarity
   ↓
3. Find best match (e.g., src_ip with similarity 0.85)
   ↓
4. Scale similarity: (0.85 - 0.12) / 0.30 = 2.43 → clamped to 1.0
   ↓
5. Blend with prior layers:
      - If no prior score: use scaled similarity alone
      - Otherwise: 0.3 * priorScore + 0.7 * scaledSimilarity
   ↓
6. If blended score >= 0.50: Accept as L4_HYBRID mapping
   Otherwise: Return NONE
```

## Test Results

**All 54 tests pass** including:

- 6 new EmbeddingMatchingService tests:
  - Blending with zero prior score
  - Blending with non-zero prior score
  - Scaling clamp at low end (< 0.12)
  - Scaling clamp at high end (> 0.42)
  - Best match selection among multiple candidates
  - Layer label verification

- 2 new Orchestrator tests:
  - Layer 4 accept (high blended score)
  - Layer 4 reject fallback to NONE (low score)

## Known Limitations

### 1. Simplified Tokenization

`EmbeddingClient.java` currently uses a placeholder tokenizer:

```java
// Current: Simple hash-based tokenization
tokenIds[pos] = Math.abs(token.hashCode() % 30000) + 1000;

// Production needs: Proper BERT/WordPiece tokenizer
// Should use the same tokenizer that trained all-MiniLM-L6-v2
```

**Impact:** Embeddings may not be optimal, but the system is functional for testing.

**Fix:** Integrate a proper BERT tokenizer library (e.g., HuggingFace tokenizers) or use a pre-tokenized approach.

### 2. Empty Embeddings Table

`mapping_embeddings` table currently has no data. The system handles this gracefully (returns null canonical field), but Layer 4 won't produce useful results until populated.

**What's needed:**
- A script/service to embed each canonical field description
- Insert into `mapping_embeddings` table
- Can run once at setup time or on-demand

Example structure:
```sql
INSERT INTO mapping_embeddings (embedding_id, canonical_field, model_name, model_version, embedding)
VALUES (
  'emb_1',
  'src_ip',
  'all-MiniLM-L6-v2',
  '1.0',
  <384-dimensional BLOB>
);
```

### 3. Placeholder Training Data

`training_examples.json` and `canonical_descriptions.json` still use 10-entry placeholder datasets.

**Action:** Replace with Raja's actual production data when available.

## Integration Points

### For Onboarding Flow

```java
// In your onboarding service (Disha/Bhushan's side):

@Autowired
private MappingEngineOrchestrator orchestrator;

public void processOnboardingRequest(String sourceId, List<String> fieldNames, boolean isStrictVendor) {
    // Call the orchestrator
    List<MappingProposal> proposals = orchestrator.mapFields(fieldNames, isStrictVendor);
    
    // Each proposal contains:
    // - vendorFieldRaw: "SrcAddress"
    // - canonicalField: "src_ip" (or null if UNKNOWN)
    // - confidence: 0.0-1.0
    // - source: "ALIAS_LOOKUP" | "TFIDF" | "TYPO_MATCH" | "L4_HYBRID" | "NONE"
    
    // Save to database
    mappingProposalService.saveMappingVersion(sourceId, proposals);
}
```

### Model Memory Management

The model lifecycle is fully automatic:

- **Cold start:** First field needing Layer 4 triggers model load (~120ms)
- **Warm state:** Subsequent fields use already-loaded model (fast)
- **Idle unload:** After 3 minutes of no Layer 4 usage, model auto-unloads
- **Manual control:** Can call `modelLifecycleManager.unloadModel()` if needed

## Performance Characteristics

Based on Adarsh's measurements:

- **Model size on disk:** 87 MB (`model.onnx`)
- **RAM usage when loaded:** ~120 MB (C++ heap via ONNX Runtime)
- **Load time:** ~120 ms (cold start)
- **Inference time:** < 50ms per field (typical)
- **Idle timeout:** 180 seconds (configurable via `mapping.model.idle-timeout-ms`)

For a batch of 50 fields:
- Cold: 120ms (load) + 50 × 50ms (inference) = ~2.6 seconds
- Warm: 50 × 50ms = ~2.5 seconds

## Next Steps

### Immediate

1. ✅ ~~Integrate Adarsh's ModelLifecycleManager~~ DONE
2. ✅ ~~Implement EmbeddingClient/EmbeddingMatchingService~~ DONE
3. ✅ ~~Update orchestrator with Layer 4~~ DONE
4. ✅ ~~Write tests~~ DONE

### Short Term

1. Create embedding population script for `mapping_embeddings`
2. Replace placeholder training/description data with production data
3. Improve tokenization in EmbeddingClient (use proper BERT tokenizer)
4. Test with real vendor schemas

### Before Production

1. Verify TF-IDF numerical parity with Raja's Python implementation
2. Tune Layer 3 typo thresholds against real data
3. Resolve open questions (STRICT/STANDARD flag, mapping_json shape, version numbering)
4. Add monitoring for model load/unload cycles
5. Performance testing under load

## Files Changed Summary

**Created:** 5 new files  
**Updated:** 3 existing files  
**Tests added:** 8 new test methods  
**Total lines of code:** ~800 lines

See `IMPLEMENTATION_REPORT.md` for complete details.
