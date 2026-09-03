# ULPF AI Mapping Engine — Technical Specification & Architecture

## Overview

The **ULPF AI Mapping Engine** is a high-speed, 4-layer hybrid mapping pipeline designed to automatically translate raw vendor log field names (e.g., `SrcAddress`, `src_ip_v4`, `client_box_ip`) into standard ULPF canonical schema fields (e.g., `src_ip`).

To minimize operational costs and RAM overhead, the engine employs a **cascading layer evaluation strategy** coupled with an **air-gapped ONNX Model Lifecycle Manager**. Heavy machine learning models remain stored on disk and are only loaded into native C++ heap memory when lower-cost matching layers yield low confidence scores during vendor onboarding.

---

## 4-Layer Cascade Architecture

```text
Raw Log Field Name (e.g. "SrcAddress")
         │
         ▼
[ Step 0: FieldPreprocessor ] ──► Tokenize, strip separators, normalize case
         │
         ▼
[ Layer 1: AliasLookupService ] ──► Exact SQLite Dictionary Match (Instant, 1.0 Confidence)
         │ (If Miss)
         ▼
[ Layer 2: TfidfMatchingService ] ──► N-gram TF-IDF Cosine Similarity Search
         │ (If Low Confidence)
         ▼
[ Layer 3: TypoMatchingService ] ──► Levenshtein Edit Distance Typo Tolerance
         │ (If Low Confidence)
         ▼
[ Layer 4: EmbeddingMatchingService ] ──► ONNX MiniLM 384-dim Vector Cosine Similarity
         │                                (Lazy-loads model.onnx into RAM on demand)
         ▼
[ Mapping Proposal Decision ] ──► Final Canonical Match or UNKNOWN Review Proposal
```

---

## Component Inventory

### 1. Preprocessing & Models (`com.ulpf.mapping.model` / `service`)
* **`FieldPreprocessor.java`**: Normalizes raw incoming field names using three-step regex (converts `camelCase`, `snake_case`, acronyms, and punctuation to standard tokenized strings).
* **`NormalizedField.java`**: Immutable record holding preprocessed tokens and metadata.
* **`MappingCandidate.java`**: Candidate score record output by matching layers.
* **`MappingProposal.java`**: Final mapping decision output record.

### 2. Matching Layers
* **Layer 1 — `AliasLookupService.java`**: Queries `mapping_aliases` for instant exact dictionary matches.
* **Layer 2 — `TfidfMatchingService.java` & `TfidfTrainingStore.java`**: Calculates $N$-gram TF-IDF vector cosine similarity across canonical field descriptions.
* **Layer 3 — `TypoMatchingService.java`**: Uses Levenshtein edit-distance matching for typo tolerance on short and long field names.
* **Layer 4 — `EmbeddingMatchingService.java` & `EmbeddingClient.java`**: Computes 384-dimensional vector cosine similarity using the local ONNX `all-MiniLM-L6-v2` model, applies min-max scaling/clamping (`(rawSim - 0.12) / 0.30`), and blends with prior layer scores (`0.3 * priorScore + 0.7 * scaledSim`).

### 3. Pipeline Coordination & Confidence Evaluation
* **`MappingEngineOrchestrator.java`**: Coordinates sequential execution through Layers 1 $\rightarrow$ 2 $\rightarrow$ 3 $\rightarrow$ 4 and outputs `MappingProposal` decisions.
* **`ConfidenceEvaluator.java`**: Evaluates match confidence, gap ratios, and token meaningfulness across layers.
* **`MappingProposalService.java`**: Handles database persistence of generated mapping versions.

### 4. Zero-RAM Air-Gapped Model Lifecycle Management
* **`ModelLifecycleManager.java`**: Controls the local `model.onnx` file (`87 MB` at `core-engine/models/all-MiniLM-L6-v2/model.onnx`).
  * `ensureLoaded()`: Idempotently loads `model.onnx` into native C++ heap on demand in **< 250 ms**.
  * `unloadModel()`: Synchronously calls `session.close()` and `env.close()`, releasing native memory back to Linux OS (**0 MB RAM**).
  * `@Scheduled` Idle Check: Automatically unloads the model after 3 minutes of inactivity.
* **`EmbeddingRepository.java`**: Lazy-loads canonical field concept embeddings from SQLite `mapping_embeddings` on first demand, with cache invalidation on idle.

---

## Integration & API Usage Example

To invoke the mapping engine from an onboarding service or controller:

```java
@Autowired
private MappingEngineOrchestrator orchestrator;

@Autowired
private MappingProposalService proposalService;

public void processVendorOnboarding(String sourceId, List<String> rawVendorFields, boolean isStrictVendor) {
    // 1. Run the 4-layer mapping cascade
    List<MappingProposal> proposals = orchestrator.mapFields(rawVendorFields, isStrictVendor);
    
    // 2. Persist the mapping proposal version to SQLite mapping_versions
    proposalService.saveMappingVersion(sourceId, proposals);
}
```

---

## Performance Characteristics

* **Model File on Disk:** `87 MB` (`core-engine/models/all-MiniLM-L6-v2/model.onnx`)
* **RAM Allocation when Active:** ~120 MB (C++ Heap via ONNX Runtime)
* **Idle RAM Allocation:** **0 MB** (automatically deallocated after 3 minutes of inactivity)
* **Cold Start Load Time:** ~120–250 ms
* **Layer 1-3 Execution Time:** < 5 ms per batch

---

## Verification & Test Results

The engine is verified by a suite of **54 unit and integration tests** (`BUILD SUCCESS`):

- `FieldPreprocessorTest`: 9/9 PASS
- `AliasLookupServiceTest`: 4/4 PASS
- `ConfidenceEvaluatorTest`: 9/9 PASS
- `TypoMatchingServiceTest`: 7/7 PASS
- `TfidfMatchingServiceTest`: 5/5 PASS
- `MappingEngineOrchestratorTest`: 9/9 PASS
- `MappingProposalServiceTest`: 4/4 PASS
- `EmbeddingMatchingServiceTest`: 6/6 PASS
- `ModelLifecycleManagerTest`: 1/1 PASS
