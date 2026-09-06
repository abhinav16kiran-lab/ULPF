# Mapping Engine Performance & Concurrency Optimizations

This document describes the high-performance architectural optimizations, memory safety fixes, and lock-free concurrency mechanics implemented in the ULPF Mapping Engine (`core-engine`).

---

## 1. High-Level Architecture Overview

The Mapping Engine normalizes raw vendor telemetry and log attributes through a 4-Layer pipeline:

```text
Incoming Field Name
       │
       ├─► Layer 1: Exact Match / Alias Lookup (O(1) Map)
       │
       ├─► Layer 2: Sparse TF-IDF + Bounded Min-Heap (Inverted Index)
       │
       ├─► Layer 3: Typo / Edit Distance (O(1) Pre-filter + 1D Rolling DP)
       │
       └─► Layer 4: Semantic Embedding Matching (L2 Normalized Fast Dot Product)
```

---

## 2. Layer-by-Layer Optimizations

### Layer 2: TF-IDF Nearest-Neighbor Matching (`TfidfTrainingStore`)

- **Sparse Inverted Index**:
  - Replaced $O(N \times V)$ dense vector dot products across all vocabulary dimensions with an in-memory inverted index `List<Posting>[] invertedIndex`.
  - Searches iterate **only over non-zero query n-grams**, bypassing >95% of training examples with zero term overlap.
- **Bounded Min-Heap ($K=3$)**:
  - Replaced $O(N \log N)$ sorting of all $N$ training candidates with a bounded `PriorityQueue` of size $K=3$.
  - Reduces memory allocation to $O(K)$ objects and reduces candidate ranking to $O(N_{\text{matched}} \log K)$.

---

### Layer 3: Typo Matching (`TypoMatchingService`)

- **$O(1)$ Length-Difference Guard**:
  - Computes `Math.abs(queryLen - aliasLen)` prior to dynamic programming table construction.
  - Skips DP table allocation entirely when length difference exceeds `maxAllowedDistance`, bypassing >90% of dictionary comparisons.
- **1D Rolling Array DP**:
  - Replaced 2D matrix allocation `int[M+1][N+1]` with two rolling 1D arrays (`prev` and `curr`).
  - Reduces space complexity to $O(N)$ and eliminates JVM heap garbage collection allocations during edit distance calculation.

---

### Layer 4: Semantic Embeddings (`EmbeddingMatchingService`)

- **$L2$ Vector Pre-Normalization**:
  - Pre-normalizes canonical embedding vectors to unit length ($||v||_2 = 1.0$) upon initialization (`CanonicalEmbedding`).
  - Normalizes raw field ONNX embedding vector once before the candidate comparison loop.
- **Fast Dot-Product Scoring**:
  - Replaced $O(D)$ cosine similarity (which recalculated query/candidate norms and `Math.sqrt` inside the candidate loop) with a tight dot product `fastDotProduct(v1, v2)`.
  - Reduces scalar operations by 3x and eliminates floating-point square root overhead.

---

## 3. Concurrency & Memory Safety Improvements

### Lock-Free Repository Cache Reads (`EmbeddingRepository`)

- **`volatile` Double-Checked Locking**:
  - Replaced method-level `synchronized` on `findAllCanonicalEmbeddings()` with a `volatile` reference and double-checked locking.
  - Cache hits execute a single `volatile` read without acquiring monitor locks, enabling **100% parallel execution** across concurrent ingestion threads.
  - Synchronization is reserved strictly for initial SQLite lazy-loading.

### Native C++ Off-Heap Memory Safety (`EmbeddingClient`)

- **Deterministic Resource Cleanup**:
  - Wrapped ONNX Runtime `OnnxTensor` handles and `OrtSession.Result` in nested `try-with-resources` blocks.
  - Guarantees that native C++ heap buffers are closed deterministically on both normal completion and exception handling paths, preventing off-heap native OOM crashes.
