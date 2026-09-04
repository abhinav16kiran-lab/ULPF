package com.ulpf.mapping.service;

import com.ulpf.mapping.config.MappingConfig;
import com.ulpf.mapping.model.MappingCandidate;
import com.ulpf.mapping.model.MappingProposal;
import com.ulpf.mapping.model.NormalizedField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Orchestrator that ties all mapping layers together.
 * Runs each field through the sequential pipeline until a confident match is found.
 */
@Service
public class MappingEngineOrchestrator {
    
    private static final Logger log = LoggerFactory.getLogger(MappingEngineOrchestrator.class);
    
    private final FieldPreprocessor preprocessor;
    private final AliasLookupService aliasLookupService;
    private final TfidfMatchingService tfidfMatchingService;
    private final TypoMatchingService typoMatchingService;
    private final EmbeddingMatchingService embeddingMatchingService;
    private final ConfidenceEvaluator confidenceEvaluator;
    private final MappingConfig config;
    
    public MappingEngineOrchestrator(
            FieldPreprocessor preprocessor,
            AliasLookupService aliasLookupService,
            TfidfMatchingService tfidfMatchingService,
            TypoMatchingService typoMatchingService,
            EmbeddingMatchingService embeddingMatchingService,
            ConfidenceEvaluator confidenceEvaluator,
            MappingConfig config) {
        this.preprocessor = preprocessor;
        this.aliasLookupService = aliasLookupService;
        this.tfidfMatchingService = tfidfMatchingService;
        this.typoMatchingService = typoMatchingService;
        this.embeddingMatchingService = embeddingMatchingService;
        this.confidenceEvaluator = confidenceEvaluator;
        this.config = config;
    }
    
    /**
     * Map a list of raw field names to canonical fields.
     * 
     * @param rawFieldNames list of vendor field names
     * @param vendorIsStrict whether vendor requires exact matches only
     * @return list of mapping proposals, one per field
     */
    public List<MappingProposal> mapFields(List<String> rawFieldNames, boolean vendorIsStrict) {
        try {
            return rawFieldNames.stream()
                .map(raw -> mapSingleField(raw, vendorIsStrict))
                .collect(Collectors.toList());
        } finally {
            // Ephemeral ML memory eviction: pop canonical embeddings out of RAM immediately after onboarding proposal generation
            embeddingMatchingService.clearCache();
        }
    }
    
    /**
     * Map a single field through the sequential pipeline.
     */
    private MappingProposal mapSingleField(String rawFieldName, boolean vendorIsStrict) {
        // Step 1: Preprocess
        NormalizedField field = preprocessor.process(rawFieldName);
        
        // Step 2: Layer 1 - Alias lookup (exact match)
        var aliasHit = aliasLookupService.lookup(field);
        if (aliasHit.isPresent()) {
            return new MappingProposal(rawFieldName, aliasHit.get(), 1.0, "ALIAS_LOOKUP");
        }
        
        // Step 3: Layer 2 - TF-IDF matching
        List<MappingCandidate> tfidfResults = safelyRun(() -> tfidfMatchingService.match(field));
        if (confidenceEvaluator.isConfident(tfidfResults)) {
            MappingCandidate top = tfidfResults.get(0);
            return new MappingProposal(rawFieldName, top.getCanonicalField(), top.getScore(), "TFIDF");
        }
        
        // Step 4: Layer 3 - Typo matching
        List<MappingCandidate> typoResults = safelyRun(() -> typoMatchingService.match(field));
        if (confidenceEvaluator.isConfident(typoResults)) {
            MappingCandidate top = typoResults.get(0);
            return new MappingProposal(rawFieldName, top.getCanonicalField(), top.getScore(), "TYPO_MATCH");
        }
        
        // Step 5: STRICT vendor check or meaningless token check
        if (vendorIsStrict || !confidenceEvaluator.isMeaningfulToken(rawFieldName)) {
            return new MappingProposal(rawFieldName, null, 0.0, "NONE");
        }
        
        // Step 6: Layer 4 - Embedding hybrid matching
        double bestPriorScore = Math.max(
            topScoreOrZero(tfidfResults), 
            topScoreOrZero(typoResults)
        );
        
        MappingCandidate hybrid = safelyRun(() -> {
            return List.of(embeddingMatchingService.matchWithFallback(rawFieldName, bestPriorScore));
        }).stream().findFirst().orElse(null);
        
        if (hybrid != null && hybrid.getScore() >= config.getHybridAcceptanceThreshold()) {
            return new MappingProposal(rawFieldName, hybrid.getCanonicalField(), hybrid.getScore(), "L4_HYBRID");
        }
        
        return new MappingProposal(rawFieldName, null, hybrid != null ? hybrid.getScore() : bestPriorScore, "NONE");
    }
    
    /**
     * Safely run a layer, catching exceptions and treating them as "no candidates".
     */
    private List<MappingCandidate> safelyRun(LayerRunner runner) {
        try {
            return runner.run();
        } catch (Exception e) {
            log.warn("Layer execution failed, treating as no candidates: {}", e.getMessage());
            return List.of();
        }
    }
    
    /**
     * Get the top score from a candidate list, or 0.0 if empty.
     */
    private double topScoreOrZero(List<MappingCandidate> candidates) {
        return (candidates != null && !candidates.isEmpty()) 
            ? candidates.get(0).getScore() 
            : 0.0;
    }
    
    @FunctionalInterface
    private interface LayerRunner {
        List<MappingCandidate> run();
    }
}
