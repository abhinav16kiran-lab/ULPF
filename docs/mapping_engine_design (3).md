# ULPF — AI Mapping Engine: Design & File Reference

## What this document is for

This is the build reference for the **AI Mapping Engine** — the part of ULPF that looks at a
vendor's weird field name (like `SrcAddress` or `client_addr`) and figures out which of our
canonical fields it means (like `src_ip`).

This only runs **once per vendor, during onboarding** (`POST /v1/onboard`). It never runs on the
live event stream — once a mapping is approved by a human, the real-time ingestion pipeline
(`POST /v1/events`) just applies that already-approved mapping, no guessing involved. That
separation is intentional and explained more at the end of this doc.

> **Update (this revision):** the earlier version of this doc was based only on the PDF/diagram.
> This revision has been updated to match `HybridSchemaMapper` — Raja's tested Python prototype
> — which turned out to work a bit differently in a few important places. Anywhere this doc now
> follows Raja's code instead of the original plan, it says so. Anywhere Claude filled a genuine
> gap (something Raja's script never had to handle), it's marked **Decision by Claude** same as
> before.
>
> **Second update (same revision):** the sections on saving to `mapping_versions` — Input,
> Output, the connects-to-`/v1/onboard` diagram, and `MappingProposalService` — have now also
> been cross-checked against the real `docs/DATABASE_SCHEMA.md` and corrected. The headline
> change: a mapping result saves as **one row per source**, holding every field's mapping
> together in a JSON blob, not one row per field like this doc previously assumed. Flagged
> inline everywhere it changes something.

---

## The full picture, in one flow

```
Vendor field (raw string, e.g. "SrcAddress")
        |
        v
[Preprocessing] — clean up the text, split words apart
        |
        v
[Layer 1: Dictionary Lookup] — "do we already know this exact spelling?"
        |
   hit? -- yes --> Proposal (source: ALIAS_LOOKUP, confidence: 1.0) --> DONE
        |
        no
        v
[Layer 2: TF-IDF + Nearest-Neighbor Matching] — "which known example looks most like this?"
        |
        v
[Confidence Check] — good score AND clearly better than 2nd place?
        |
   yes -------------------------------------------> Proposal (source: TFIDF) --> DONE
        |
        no
        v
[Layer 3: Typo Matching] — "is this just a misspelling of something we know?"
        |
        v
[Confidence Check] — same test again
        |
   yes -------------------------------------------> Proposal (source: TYPO_MATCH) --> DONE
        |
        no
        v
   Is this vendor STRICT? -- yes --> Proposal (source: NONE, status: UNKNOWN) --> DONE
        |
        no (vendor is STANDARD)
        v
   Is the raw field text meaningful (not blank/gibberish)? -- no --> Proposal (UNKNOWN) --> DONE
        |
        yes
        v
[Layer 4: MiniLM Semantic Match] — blended with the best score Layers 2/3 already found
        |
        v
   combined score >= 0.50 ? -- yes --> Proposal (source: L4_HYBRID) --> DONE
        |
        no
        v
   Proposal (source: NONE, status: UNKNOWN) --> DONE
```

Every field goes through this, one at a time. As soon as any stage is confident, we stop —
cheaper stages run for free on every field, the expensive one only runs when it has to.

> **Note on the diagram vs. the PDF:** the original diagram shows alias lookup and TF-IDF
> running side-by-side with no separate typo layer. The team decided (Sept 1 discussion) to
> follow the **PDF's** four-sequential-layers description instead, and to **keep the typo
> layer** even though Raja's tested prototype doesn't have one yet (his prototype only has
> three stages: dictionary → TF-IDF → MiniLM). This document builds Layer 3 in *addition* to
> what Raja tested, sitting between his TF-IDF stage and his MiniLM stage.

---

## Shared building blocks (used by more than one layer)

These aren't "layers" — they're small shared pieces that every layer above reads or writes
through, so the layers all speak the same language.

### `NormalizedField` — what a field looks like after cleanup

```java
// mapping/model/NormalizedField.java
public class NormalizedField {
    private final String rawText;       // exactly what the vendor sent, e.g. "SrcAddress"
    private final String cleanedText;   // full cleaned phrase, e.g. "source address"
    private final List<String> tokens;  // individual words, e.g. ["source", "address"]

    // Getters only — this object never changes after preprocessing builds it.
}
```

### `MappingCandidate` — one guess, with a score

Every scoring layer (2, 3, 4) returns a list of these. Keeping the shape identical across
layers means the confidence-checking logic only has to be written once.

```java
// mapping/model/MappingCandidate.java
public class MappingCandidate {
    private final String canonicalField;  // e.g. "src_ip"
    private final double score;           // always 0.0–1.0, so every layer is comparable
    private final String producedByLayer; // "TFIDF", "TYPO_MATCH", "L4_HYBRID" — for audit/debug
}
```

> **Decision by Claude:** scores from every layer are normalized to the same 0.0–1.0 scale
> (Layer 3's edit-distance becomes a similarity score the same way Layer 2's and Layer 4's
> similarity scores already are). Purpose: one confidence-checking function can be reused
> everywhere instead of writing separate "is this good enough" logic per layer.

### `MappingProposal` — the final output of the whole pipeline, for one field

```java
// mapping/model/MappingProposal.java
public class MappingProposal {
    private final String vendorFieldRaw;   // "SrcAddress"
    private final String canonicalField;   // "src_ip", or null if UNKNOWN
    private final double confidence;       // 0.0–1.0
    private final String source;           // ALIAS_LOOKUP / TFIDF / TYPO_MATCH / L4_HYBRID / NONE
}
```

### Where the dictionary lives — **updated, based on Raja's code**

The original plan had two separate steps: an "abbreviation expansion" pass (`src` → `source`)
followed by a whole-phrase "alias lookup." Raja's tested prototype does this more simply — one
flat dictionary that lists every known short and long form directly (`srcip`, `sourceip`,
`clientip`, `originip` all just map straight to `src_ip`), checked as a single lookup with no
separate expansion step. That's simpler, already tested, and does the same job — this doc now
follows that instead of the two-stage version.

The lookup key is the cleaned text with spaces removed entirely (`"source address"` becomes
`"sourceaddress"`), matching exactly how Raja's `alias_dictionary` keys are written.

> **Decision by Claude (kept from the last revision):** this dictionary still lives in a SQLite
> table, not a static file, even though Raja's prototype hardcodes it in Python. A table lets it
> grow from human corrections during review without a redeploy — the PDF's own roadmap idea.
> Loaded into an in-memory map at startup so lookups stay instant.

```sql
-- Add this to core-engine/src/main/resources/sqlite/schema.sql — the schema file
-- moved here (from the old sqlite-init/ folder) because Maven can't see files
-- outside src/main/. (Adarsh, Sept 2.)
CREATE TABLE mapping_aliases (
    alias_id        INTEGER PRIMARY KEY AUTOINCREMENT,
    canonical_field TEXT NOT NULL,
    alias_key       TEXT NOT NULL,   -- normalized, NO SPACES, e.g. "sourceaddress"
    source          TEXT NOT NULL DEFAULT 'seed',  -- 'seed' or 'human_correction'
    created_at      TEXT NOT NULL DEFAULT (datetime('now'))
);
```

### Where the confidence thresholds live

> **Decision by Claude:** thresholds go in `application.yaml`, read into one config class, so
> every layer reads the same numbers from the same place. The main threshold/gap numbers
> (0.60 / 0.20) come from the diagram, unchanged. The **hybrid acceptance threshold (0.50)** is
> new in this revision — it's the bar Layer 4's *blended* score has to clear, taken directly
> from Raja's tested code, and it's deliberately a different, lower number than the main
> threshold because a blended score is already a compromise between two signals.

```yaml
# application.yaml
mapping:
  confidence-threshold: 0.60
  gap-threshold: 0.20
  hybrid-acceptance-threshold: 0.50
```

```java
// mapping/config/MappingConfig.java
@ConfigurationProperties(prefix = "mapping")
public class MappingConfig {
    private double confidenceThreshold;       // 0.60
    private double gapThreshold;              // 0.20
    private double hybridAcceptanceThreshold; // 0.50
    // getters/setters
}
```

---

## Step 0 — Preprocessing (runs on every field, always)

**Purpose:** turn a messy raw field name into clean, comparable text before any matching starts.

**Input:** the raw field name string, e.g. `"SrcAddress"`.
**Output:** a `NormalizedField`.
**File:** `mapping/service/FieldPreprocessor.java`
**Called by:** the orchestrator, first thing, before anything else.

**Updated to match Raja's tested regex** — it's a bit more careful than a simple
lowercase-then-split: it also handles acronym boundaries (`HTTPMethod` → `HTTP Method`) and
treats dots the same as underscores/hyphens.

```java
// mapping/service/FieldPreprocessor.java
public NormalizedField process(String rawFieldName) {
    // 1. Split "ABCDef" style boundaries first (acronym followed by a capitalized word).
    String step1 = rawFieldName.replaceAll("(.)([A-Z][a-z]+)", "$1 $2");

    // 2. Then split ordinary camelCase/digit-to-capital boundaries.
    String step2 = step1.replaceAll("([a-z0-9])([A-Z])", "$1 $2");

    // 3. Normalize underscores, hyphens, and dots to spaces, then lowercase + trim.
    String cleanedText = step2.replaceAll("[_\\-.]+", " ").toLowerCase().trim();
    cleanedText = cleanedText.replaceAll("\\s+", " ");

    List<String> tokens = Arrays.asList(cleanedText.split(" "));
    return new NormalizedField(rawFieldName, cleanedText, tokens);
}
```

*(No abbreviation-expansion step here anymore — see the dictionary note above. The known short
forms are handled directly by Layer 1's dictionary instead.)*

---

## Layer 1 — Dictionary Lookup

**Purpose:** "have we seen this exact spelling before, with an already-known answer?" Cheapest,
most certain check — if it hits, we're done, no scoring needed.

**Input:** `NormalizedField`.
**Output:** either a canonical field name (a hit) or nothing.
**File:** `mapping/service/AliasLookupService.java`
**Depends on:** the `mapping_aliases` table (loaded into memory at startup).
**Called by:** orchestrator, right after preprocessing. If it hits, skips straight to building a
proposal — nothing else runs for this field.

> **Decision by Claude (kept from last revision):** exact match only, no partial/fuzzy scoring
> here — that's what Layer 2 is for. A hit here means "we already knew this," not "we guessed
> well."

```java
// mapping/service/AliasLookupService.java
public Optional<String> lookup(NormalizedField field) {
    String key = field.getCleanedText().replace(" ", ""); // "source address" -> "sourceaddress"
    return Optional.ofNullable(aliasMap.get(key)); // aliasMap loaded once at startup
}
```

---

## Layer 2 — TF-IDF + Nearest-Neighbor Matching — **rewritten to match Raja's code**

**This is different from the previous revision of this doc.** The old plan had *one reference
vector per canonical field*. Raja's tested version does something smarter: it trains on a list
of **labeled example spellings** (several real-world variants per canonical field, e.g.
`"src_ip"`, `"source_ip"`, `"srcAddr"`, `"SourceAddress"`, `"client_ip"` all labeled `src_ip`),
and for a new field, finds the **closest known example** rather than the closest bare field
name. This is a genuinely different technique — nearest-neighbor search over examples, not
one-vector-per-category — so it needs its own storage shape.

**Purpose:** score how similar this field is to the closest known labeled example.

**Input:** `NormalizedField`.
**Output:** `List<MappingCandidate>`, top 3 nearest examples (so the confidence check has a
top-vs-second-place gap to compare, same as Raja's `n_neighbors=3`).
**Files:**
- `mapping/service/TfidfTrainingStore.java` — vectorizes every labeled training example once,
  at startup, and holds them for nearest-neighbor search.
- `mapping/service/TfidfMatchingService.java` — vectorizes one incoming field, finds its 3
  closest training examples.
**Depends on:** a training-example list — `src/main/resources/mapping/training_examples.json`,
one JSON array of `{ "text": "srcAddr", "canonicalField": "src_ip" }` pairs, ported directly
from Raja's Python `training_dataset`.
**Called by:** orchestrator, only if Layer 1 found nothing.

> **Open engineering note (not fully solved yet):** Raja's version uses scikit-learn's
> `TfidfVectorizer` (with 1–3 word n-grams) and `NearestNeighbors`. Plain Java has no exact
> equivalent — this needs to be hand-written (build a vocabulary of n-grams, weight rarer ones
> higher, compare vectors by cosine distance) or brought in via a small Java ML library. The
> sketch below shows the *shape* of it, but getting numerically identical behavior to the
> Python version will need side-by-side testing once it's built.

```java
// mapping/service/TfidfTrainingStore.java
@PostConstruct
public void buildIndex() {
    List<TrainingExample> examples = loadTrainingExamples(); // from training_examples.json
    this.vocabulary = buildNgramVocabulary(examples, 1, 3);  // every distinct 1-3 word n-gram
    this.idfWeights = computeIdfWeights(examples, vocabulary); // rarer n-grams score higher

    for (TrainingExample ex : examples) {
        double[] vector = vectorize(preprocess(ex.getText()));
        indexedVectors.add(vector);
        indexedLabels.add(ex.getCanonicalField());
    }
}
```

```java
// mapping/service/TfidfMatchingService.java
public List<MappingCandidate> match(NormalizedField field) {
    double[] queryVector = trainingStore.vectorize(field.getCleanedText());

    // Find the 3 closest training examples by cosine similarity — same k=3 Raja used,
    // so the confidence check has a real top-vs-second-place gap to compare.
    List<ScoredExample> nearest = trainingStore.findNearestNeighbors(queryVector, 3);

    return nearest.stream()
        .map(n -> new MappingCandidate(n.getCanonicalField(), n.getSimilarity(), "TFIDF"))
        .toList(); // already sorted closest-first
}
```

*(The "value/type evidence" idea from the earlier chat — using sample data, not just field
names — is still a **later item**, not built into this version.)*

---

## Confidence Check (shared by Layers 2 and 3)

**Purpose:** decide, after Layer 2 or Layer 3 runs, whether the top answer is trustworthy
enough to stop here.

**File:** `mapping/service/ConfidenceEvaluator.java`
**Depends on:** `MappingConfig`.
**Called by:** orchestrator, right after Layer 2 and right after Layer 3.

```java
// mapping/service/ConfidenceEvaluator.java
public boolean isConfident(List<MappingCandidate> ranked) {
    if (ranked.isEmpty()) return false;

    double topScore = ranked.get(0).getScore();
    double secondScore = ranked.size() > 1 ? ranked.get(1).getScore() : 0.0;
    double gap = topScore - secondScore;

    // Both must hold: the top guess is good on its own, AND clearly better than
    // the runner-up (not a coin flip between two similar candidates).
    return topScore >= config.getConfidenceThreshold()
        && gap >= config.getGapThreshold();
}

public boolean isMeaningfulToken(String rawFieldName) {
    // Filters out blank/junk input before paying for the expensive model —
    // matches the gibberish check in the tested prototype.
    String cleaned = rawFieldName.replaceAll("[^a-zA-Z0-9]", "");
    return cleaned.length() >= 2;
}
```

---

## Layer 3 — Typo / Edit-Distance Matching

**This layer doesn't exist in Raja's tested script — it's being added on top of his prototype**,
per the team's decision to keep the PDF's four-layer design.

**Purpose:** catch misspellings and truncations that Layer 2 can't see —
`ClientAdd` vs `ClientAddr`.

**Input:** `NormalizedField`.
**Output:** `List<MappingCandidate>`, same shape as Layer 2's output.
**File:** `mapping/service/TypoMatchingService.java`
**Depends on:** the same `mapping_aliases` dictionary Layer 1 uses (compares against known
variant spellings, not just the six bare canonical names — that's where real typos happen).
**Called by:** orchestrator, only if Layer 2 wasn't confident.

> **Decision by Claude:** the acceptance threshold scales with word length instead of using a
> flat edit-distance cutoff — for words 4 characters or shorter, allow at most **1 edit**; for
> longer words, allow up to **30% of the word's length** in edits. This is a starting point, not
> a settled number.

```java
// mapping/service/TypoMatchingService.java
public List<MappingCandidate> match(NormalizedField field) {
    List<MappingCandidate> results = new ArrayList<>();
    String queryKey = field.getCleanedText().replace(" ", "");

    for (var alias : aliasVocabulary) {  // same source as Layer 1's dictionary
        int distance = levenshtein(queryKey, alias.getAliasKey());
        int maxLen = Math.max(queryKey.length(), alias.getAliasKey().length());

        boolean withinThreshold = (maxLen <= 4)
            ? distance <= 1
            : ((double) distance / maxLen) <= 0.30;

        if (withinThreshold) {
            double similarity = 1.0 - ((double) distance / maxLen); // keep it 0.0-1.0
            results.add(new MappingCandidate(alias.getCanonicalField(), similarity, "TYPO_MATCH"));
        }
    }
    return results.stream()
        .sorted(Comparator.comparingDouble(MappingCandidate::getScore).reversed())
        .toList();
}
```

---

## Layer 4 — MiniLM Semantic Match — **rewritten to match Raja's code**

**Purpose:** understand actual *meaning*, catching true synonyms with zero shared spelling
(`origin_ip` vs `src_ip`). Only reached when Layers 2 and 3 both fail, the field isn't gibberish,
and the vendor is STANDARD (never for STRICT vendors).

Two real differences from the previous revision of this doc, both taken directly from tested
code:

**1. It embeds the raw field text, not the cleaned/expanded version.** Raja's script passes
`raw_field` (e.g. `"SrcAddress"`) straight into the embedding model, not the preprocessed
`"source address"`. Sentence-embedding models read natural strings fine on their own — this
doc's earlier assumption (pass the cleaned phrase) is corrected to match what was actually
tested.

**2. Canonical fields are compared as full descriptive sentences, not short alias lists.**
Instead of embedding `"source ip"`, each canonical field gets a proper description —
`"The originating internet protocol address, client IP, sender network endpoint, or source node
identifier."` for `src_ip`, for example. A sentence-transformer model works much better against
natural language than against keyword fragments.

**Input:** the raw field string (untouched).
**Output:** one blended `MappingCandidate`.
**Files:**
- `mapping/service/EmbeddingClient.java` — talks to the local llama.cpp sidecar over HTTP.
- `mapping/service/EmbeddingMatchingService.java` — does the comparison and blending.
**Depends on:**
- `mapping_embeddings` SQLite table — now storing an embedding per canonical field's
  **description sentence**, not its alias text.
- `src/main/resources/mapping/canonical_descriptions.json` — the description text itself,
  ported from Raja's `canonical_registry`.

> **On cold start:** Raja's script loads the MiniLM model straight into memory at startup
> (there's even a comment in his code acknowledging the delay this causes). The team's earlier
> decision — keep the model on disk, load into RAM only when a field actually needs Layer 4 —
> still stands; cold-start optimization isn't needed right now, confirmed Sept 1. Raja's script
> is a fine standalone test tool as-is, but the real Java service should keep the lazy-load
> behavior.

> **Decision by Claude:** when Layer 4 runs, it blends its score with **whichever of Layer 2 or
> Layer 3 scored higher**, not just Layer 2 like Raja's script does — because Raja's prototype
> never had a Layer 3 to consider. If neither found anything, the blend falls back to the
> semantic score alone, exactly like the tested code does when TF-IDF found nothing.

```java
// mapping/service/EmbeddingMatchingService.java
public MappingCandidate matchWithFallback(String rawFieldName, double bestPriorScore) {
    double[] fieldEmbedding = embeddingClient.getEmbedding(rawFieldName); // raw text, not cleaned

    CanonicalEmbedding best = null;
    double bestRawSimilarity = -1.0;
    for (CanonicalEmbedding candidate : embeddingRepository.findAllCanonicalEmbeddings()) {
        double sim = cosineSimilarity(fieldEmbedding, candidate.getEmbeddingVector());
        if (sim > bestRawSimilarity) {
            bestRawSimilarity = sim;
            best = candidate;
        }
    }

    // Raw cosine similarity from sentence embeddings bunches up in a narrow band,
    // so it's stretched to use the full 0.0-1.0 range. These two constants (0.12, 0.30)
    // came from Raja's testing against the canonical descriptions — revisit if the
    // descriptions change meaningfully.
    double scaledSimilarity = clamp((bestRawSimilarity - 0.12) / 0.30, 0.0, 1.0);

    // Blend with whatever the earlier layers already found, so a weak-but-real
    // signal from Layer 2/3 still counts instead of being thrown away.
    double combined = (bestPriorScore == 0.0)
        ? scaledSimilarity
        : (0.3 * bestPriorScore) + (0.7 * scaledSimilarity);

    return new MappingCandidate(best.getCanonicalField(), combined, "L4_HYBRID");
}
```

```java
// mapping/service/EmbeddingClient.java
public double[] getEmbedding(String text) {
    // POST to the local llama.cpp sidecar — localhost only, nothing leaves the machine.
    var request = Map.of("input", text);
    var response = restTemplate.postForObject(
        "http://localhost:8081/embedding", request, EmbeddingResponse.class);
    return response.getVector();
}
```

### How this talks to SQLite

```sql
CREATE TABLE mapping_embeddings (
    embedding_id     INTEGER PRIMARY KEY AUTOINCREMENT,
    canonical_field  TEXT NOT NULL,
    model_name       TEXT NOT NULL,
    model_version    TEXT NOT NULL,
    embedding        BLOB NOT NULL,   -- embedding of the DESCRIPTION sentence, not alias text
    created_at       TEXT NOT NULL DEFAULT (datetime('now'))
);
```

```java
// mapping/repository/EmbeddingRepository.java
public List<CanonicalEmbedding> findAllCanonicalEmbeddings() {
    // Simple JDBC read — this table almost never changes, safe to cache in memory
    // after the first read.
    String sql = "SELECT canonical_field, embedding FROM mapping_embeddings";
    return jdbcTemplate.query(sql, (rs, rowNum) -> new CanonicalEmbedding(
        rs.getString("canonical_field"),
        bytesToDoubleArray(rs.getBytes("embedding"))
    ));
}
```

This table is written to **once**, as a setup step (embed each canonical field's description
sentence when the app is first configured, or whenever a description changes), and read from
many times cheaply — only the incoming vendor field gets embedded live, never the canonical side.

---

## Closing the loop — turning a batch of results into one mapping version — **rewritten this revision**

**File:** `mapping/service/MappingProposalService.java`
**Depends on:** the control plane's real `mapping_versions` table — confirmed columns:
`mapping_id, source_id, version, mapping_json, status, created_at`. This is **one row per
mapping attempt for one source**, not one row per field — the previous revision of this doc
(and this file's own diagram, until now) assumed per-field rows, which doesn't match the real
schema. Every field's result — mapped or unknown — lives together inside `mapping_json`;
whether a given field was actually mapped is shown by whether that entry's `canonical_field` is
null, and which layer decided it is shown by that entry's `source`.

> **Placeholder — not final, and now also incomplete.** Adarsh confirmed (Sept 1) he's providing
> a shared save function; Raja and Abhinav call that function instead of writing this INSERT
> themselves. The exact table name is now confirmed (`mapping_versions`), but two things are
> still open: (1) the exact shape of `mapping_json` — array vs. field-keyed object, sketched
> below as a guess — and (2) who computes the next `version` integer for a given `source_id`
> before insert. The sketch assumes the save function handles version numbering, since Adarsh
> said not to worry about it — worth confirming rather than assuming.

```java
// mapping/service/MappingProposalService.java — TEMPORARY, until Adarsh's function is shared
public void saveMappingVersion(String sourceId, List<MappingProposal> proposals) {
    // Assemble every field's result into one JSON object — shape here is a guess
    // (field-keyed object) until the real contract is settled; see "Still to confirm."
    String mappingJson = buildMappingJson(proposals);

    String sql = """
        INSERT INTO mapping_versions (source_id, mapping_json, status)
        VALUES (?, ?, 'CANDIDATE')
        """;
    // `version` is intentionally not set here — assumed to be computed by Adarsh's
    // eventual save function (next version for this source_id). Confirm this assumption.
    jdbcTemplate.update(sql, sourceId, mappingJson);
}

private String buildMappingJson(List<MappingProposal> proposals) {
    // { "SrcAddress": { "canonicalField": "src_ip", "confidence": 1.0, "source": "ALIAS_LOOKUP" }, ... }
    Map<String, Object> byField = new LinkedHashMap<>();
    for (MappingProposal p : proposals) {
        byField.put(p.getVendorFieldRaw(), Map.of(
            "canonicalField", p.getCanonicalField(),   // null if UNKNOWN
            "confidence", p.getConfidence(),
            "source", p.getSource()
        ));
    }
    return objectMapper.writeValueAsString(byField);
}

// Once Adarsh's function exists, this becomes something like:
// public void saveMappingVersion(String sourceId, List<MappingProposal> proposals) {
//     sharedMappingSaver.save(sourceId, proposals); // exact signature TBD
// }
```

> **Caller change:** this now needs to be invoked **once per onboarding batch**, after
> `MappingEngineOrchestrator.mapFields(...)` returns the full `List<MappingProposal>` — not once
> per field inside the orchestrator's loop. The orchestrator itself doesn't change; only when
> and how its output gets saved does.

---

## The Orchestrator — ties everything together

**File:** `mapping/service/MappingEngineOrchestrator.java`
**Called by:** the `/v1/onboard` endpoint's service layer (Disha & Bhushan's side).

```java
// mapping/service/MappingEngineOrchestrator.java
public List<MappingProposal> mapFields(List<String> rawFieldNames, boolean vendorIsStrict) {
    return rawFieldNames.stream()
        .map(raw -> mapSingleField(raw, vendorIsStrict))
        .toList();
}

private MappingProposal mapSingleField(String rawFieldName, boolean vendorIsStrict) {
    NormalizedField field = preprocessor.process(rawFieldName);

    // Layer 1 — cheapest, checked first, skips everything else on a hit.
    var aliasHit = aliasLookupService.lookup(field);
    if (aliasHit.isPresent()) {
        return buildProposal(rawFieldName, aliasHit.get(), 1.0, "ALIAS_LOOKUP");
    }

    // Layer 2
    var tfidfResults = safelyRun(() -> tfidfMatchingService.match(field));
    if (confidenceEvaluator.isConfident(tfidfResults)) {
        return acceptTop(rawFieldName, tfidfResults, "TFIDF");
    }

    // Layer 3
    var typoResults = safelyRun(() -> typoMatchingService.match(field));
    if (confidenceEvaluator.isConfident(typoResults)) {
        return acceptTop(rawFieldName, typoResults, "TYPO_MATCH");
    }

    // Neither Layer 2 nor Layer 3 was confident. STRICT vendors stop here, always.
    // STANDARD vendors also stop here if the field text isn't even meaningful —
    // no point paying for the embedding model on blank/gibberish input.
    if (vendorIsStrict || !confidenceEvaluator.isMeaningfulToken(rawFieldName)) {
        return buildProposal(rawFieldName, null, 0.0, "NONE"); // -> UNKNOWN
    }

    // Layer 4 — blend with whichever of Layer 2/3 scored higher, if either found anything.
    double bestPriorScore = Math.max(topScoreOrZero(tfidfResults), topScoreOrZero(typoResults));
    MappingCandidate hybrid = safelyRun(
        () -> embeddingMatchingService.matchWithFallback(rawFieldName, bestPriorScore));

    if (hybrid != null && hybrid.getScore() >= config.getHybridAcceptanceThreshold()) {
        return buildProposal(rawFieldName, hybrid.getCanonicalField(), hybrid.getScore(), "L4_HYBRID");
    }
    return buildProposal(rawFieldName, null, hybrid != null ? hybrid.getScore() : 0.0, "NONE");
}
```

> **Decision by Claude (kept from last revision):** a layer *failing* (not just scoring low —
> e.g. the embedding sidecar isn't running) is caught and treated as "no candidates," so one
> broken layer can't crash a whole vendor's onboarding. The orchestrator itself never contains
> scoring logic — only sequencing.

---

## How this connects to `/v1/onboard`

> **Diagram corrected this revision** — the last step used to say "writes each one... as
> CANDIDATE" (implying one row per field). Fixed to match the real `mapping_versions` shape:
> one row per source, holding every field's result together.

```
Onboard request arrives (sample schema + source_id + vendor info)
        |
        v
Onboarding controller (Disha/Bhushan) reads the vendor's STRICT/STANDARD setting
   (** currently has no column to read from — see Input section below **)
        |
        v
Calls MappingEngineOrchestrator.mapFields(fieldNames, vendorIsStrict)
        |
        v
Gets back a List<MappingProposal> — one per field, whole batch
        |
        v
MappingProposalService assembles all of them into one mapping_json object
        |
        v
Computes the next version for this source_id, writes ONE row into
mapping_versions (source_id, version, mapping_json, status='CANDIDATE')
        |
        v
Human review (Approve / Edit / Reject) updates that one row's status later
```

Worth writing this handoff into `docs/API_SPECIFICATION.md` as its own small contract, same as
any other endpoint.

### Input — what the mapping engine needs, per onboarding batch

- **Source ID** — the real key. Each source (not each vendor) gets its own mapping.
- **Vendor ID** — context only, e.g. for STRICT/STANDARD lookup. Not the DB key.
- **List of raw field names** — settled, e.g. `["SrcAddress", "end_Point", "temp"]`.
- **Batch ID** — confirmed: `onboarding_requests.request_id`.
- **STRICT/STANDARD flag** — open. No column for it exists yet in `vendors` or `sources`. Needs a decision + a new column, not just a lookup. Raise with Disha/Bhushan or Adarsh.
- **Sample values per field** — open. Not used yet. `sample_metadata` (JSON) may already cover it — confirm with Disha/Bhushan.

### Output — what the mapping engine hands back, for the whole batch

> Corrected this revision: results save as **one row per source**, not one row per field.

- Not returned directly in the HTTP response — always saved to the DB first.
- One `mapping_versions` row per batch, holding **all** fields' results together in `mapping_json`.
- Per field, inside `mapping_json`:
  - raw field name
  - canonical field (or null)
  - confidence (0.0–1.0)
  - source layer (`ALIAS_LOOKUP` / `TFIDF` / `TYPO_MATCH` / `L4_HYBRID` / `NONE`)
- Row itself carries: `source_id`, `version`, `status` (`CANDIDATE`), `created_at`.
- **Frontend impact:** Disha/Bhushan can't stream per-field anymore — they read one row's JSON and unpack it themselves. Needs confirming with them.
- Still open:
  - `mapping_json` shape — array or field-keyed object?
  - Confidence format — raw number, %, or label?
  - Partial-batch failure — save partial JSON, or fail the whole batch?

### Still to confirm before this is final

- ~~Exact table name~~ — **confirmed**: `mapping_versions`, matching this doc, checked against
  the real `docs/DATABASE_SCHEMA.md`.
- The save function's real signature — not shared yet. `MappingProposalService` above is a
  placeholder until it exists, and now needs to accept a whole batch's worth of proposals (or
  the already-assembled JSON), not one `MappingProposal` at a time.
- **New:** exact shape of the `mapping_json` blob — array vs. field-keyed object, and the exact
  field names inside it. This is now a real contract (human review reads it, and later the
  per-event mapping-application code reads it too), worth locking down before either side builds
  against it.
- **New:** who computes the next `version` integer for a given `source_id` before insert —
  possibly already inside Adarsh's save function, since he said not to worry about it, but worth
  confirming rather than assuming.
- **New:** where the STRICT/STANDARD flag actually lives — no column for it exists yet in
  `vendors` or `sources`. Needs a schema decision and a migration, not just a lookup.
- **New:** `mapping_aliases` (the dictionary table defined earlier in this doc) is a proposal
  drafted here, not yet part of the official `docs/DATABASE_SCHEMA.md`. Needs to actually be
  raised with the team and merged in, the same way `mapping_embeddings` was.
- Confirmed, no action needed: `mapping_embeddings` (used by Layer 4, defined above) already
  matches the official baseline schema — previously just this doc's proposal, now team-agreed.

---

## The bigger picture: what happens *after* a mapping is approved

Out of scope for the mapping engine itself, included for context only.

Once approved, a mapping moves to `ACTIVE`. Every real event that vendor sends through
`POST /v1/events` then gets that already-approved mapping applied — no layers, no scoring:

> **Note:** the sketch below still looks up the active mapping by `vendorId`, kept as-is since
> this section is out of scope here. Given `mapping_versions` is actually keyed to `source_id`
> (see above), this will need the same correction when the dataplane is actually designed —
> flagging it here so it isn't missed later.

```java
// (future — dataplane, not mapping/) sketch only
public NormalizedEvent applyMapping(RawEvent event, String vendorId) {
    Map<String, String> activeMapping = mappingVersionRepository.getActiveMapping(vendorId);
    Map<String, Object> normalizedFields = new HashMap<>();
    for (var entry : event.getRawFields().entrySet()) {
        String canonicalField = activeMapping.get(entry.getKey());
        if (canonicalField != null) {
            normalizedFields.put(canonicalField, entry.getValue());
        }
    }
    return new NormalizedEvent(event.getEventId(), vendorId, normalizedFields);
}
```

```sql
-- raw_events (already built, per current project state)
INSERT INTO raw_events (event_id, vendor_id, mapping_version, received_at, raw_payload)
VALUES (?, ?, ?, now(), ?);

-- normalized_events (future table — not built yet)
INSERT INTO normalized_events (event_id, vendor_id, src_ip, url, ...)
VALUES (?, ?, ?, ?, ...);
```

The mapping engine never touches ClickHouse directly, and never runs again for a vendor once
their mapping is approved.

---

## Quick file index

| File | Purpose |
|---|---|
| `mapping/service/FieldPreprocessor.java` | Cleans and tokenizes a raw field name |
| `mapping/service/AliasLookupService.java` | Layer 1 — exact dictionary match |
| `mapping/service/TfidfTrainingStore.java` | Vectorizes training examples once, at startup |
| `mapping/service/TfidfMatchingService.java` | Layer 2 — nearest-neighbor matching |
| `mapping/service/ConfidenceEvaluator.java` | Shared "good enough?" + "meaningful?" checks |
| `mapping/service/TypoMatchingService.java` | Layer 3 — edit-distance scoring |
| `mapping/service/EmbeddingClient.java` | Talks to the local llama.cpp embedding sidecar |
| `mapping/service/EmbeddingMatchingService.java` | Layer 4 — semantic + blended scoring |
| `mapping/repository/EmbeddingRepository.java` | Reads `mapping_embeddings` (SQLite BLOB) |
| `mapping/service/MappingProposalService.java` | Assembles a batch's results into one `mapping_json` row in `mapping_versions` |
| `mapping/service/MappingEngineOrchestrator.java` | Runs the whole sequence, field by field |
| `mapping/model/NormalizedField.java` | Shared: cleaned text + tokens |
| `mapping/model/MappingCandidate.java` | Shared: one scored guess |
| `mapping/model/MappingProposal.java` | Shared: final result for one field |
| `mapping/config/MappingConfig.java` | All thresholds, from `application.yaml` |
| `src/main/resources/mapping/training_examples.json` | Labeled examples for Layer 2 |
| `src/main/resources/mapping/canonical_descriptions.json` | Natural-language sentences for Layer 4 |

---

## All Claude-made decisions, in one place

| Decision | Reason |
|---|---|
| Scores normalized to 0.0–1.0 across all layers | One shared confidence-check function works everywhere |
| Dictionary stored in a SQLite table, not a file | Supports growing from human corrections without redeploying |
| Confidence/gap/hybrid thresholds centralized in `MappingConfig` | One place to tune, instead of scattered across files |
| Layer 1 is exact-match only, no fuzzy scoring | Avoids duplicating Layer 2's job |
| Layer 3 compares against the dictionary, not bare canonical names | Real typos happen on vendor-style names, not short internal ones |
| Layer 3 threshold scales with word length | A flat edit-distance cutoff breaks on very short words |
| Layer 4 blends with the higher of Layer 2/3's score | Raja's script only ever had Layer 2 to blend with — this doc adds a Layer 3, so the blend rule had to be extended |
| `EmbeddingClient` and `EmbeddingMatchingService` kept separate | HTTP plumbing can be tested apart from matching logic |
| STRICT checked before the meaningful-token check | STRICT vendors should never even consider Layer 4, cheapest check first |
| Layer failures caught and treated as "no candidates" | One broken layer shouldn't fail an entire vendor's onboarding |
| Orchestrator never contains scoring logic | Keeps the pipeline's sequencing readable top to bottom |
| `MappingProposalService` added as its own file | Bridges "engine's answer" and "DB row for review" |

## Still open (not decided by Claude — needs the team)

- Whether "value/type evidence" (using sample data, not just field names) gets built into this
  version or stays a later phase.
- Whether the Layer 3 typo threshold numbers (1 edit for short words, 30% for longer ones) hold
  up once tested against real vendor data.
- Exact request/response JSON shape for the `EmbeddingClient` ↔ llama.cpp sidecar call.
- How closely the hand-written Java TF-IDF + nearest-neighbor logic needs to numerically match
  Raja's scikit-learn results — worth testing both side by side on the same input list once the
  Java version exists.
