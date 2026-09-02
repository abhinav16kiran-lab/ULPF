package com.ulpf.mapping.service;

import com.ulpf.mapping.model.MappingCandidate;
import com.ulpf.mapping.model.NormalizedField;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Layer 2: TF-IDF + Nearest-Neighbor matching service.
 * Finds the top k closest training examples to score a field.
 */
@Service
public class TfidfMatchingService {
    
    private final TfidfTrainingStore trainingStore;
    
    public TfidfMatchingService(TfidfTrainingStore trainingStore) {
        this.trainingStore = trainingStore;
    }
    
    /**
     * Match a normalized field against training examples using TF-IDF similarity.
     * 
     * @param field the normalized field
     * @return list of top 3 candidates, sorted by similarity descending
     */
    public List<MappingCandidate> match(NormalizedField field) {
        double[] queryVector = trainingStore.vectorize(field.getCleanedText());
        
        // Find top 3 nearest neighbors (k=3 per design doc)
        List<TfidfTrainingStore.ScoredExample> nearest = 
            trainingStore.findNearestNeighbors(queryVector, 3);
        
        return nearest.stream()
            .map(n -> new MappingCandidate(
                n.getCanonicalField(), 
                n.getSimilarity(), 
                "TFIDF"
            ))
            .collect(Collectors.toList());
    }
}
