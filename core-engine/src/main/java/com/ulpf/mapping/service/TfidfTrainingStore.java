package com.ulpf.mapping.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulpf.mapping.model.TrainingExample;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * TF-IDF training store that builds vocabulary and vectorizes training examples.
 * Loads training data once at startup and maintains indexed vectors for nearest-neighbor search.
 */
@Service
public class TfidfTrainingStore {
    
    private final FieldPreprocessor preprocessor;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    private List<TrainingExample> trainingExamples;
    private Map<String, Double> idfWeights;
    private List<String> vocabulary;
    private List<double[]> indexedVectors;
    private List<String> indexedLabels;
    
    public TfidfTrainingStore(FieldPreprocessor preprocessor) {
        this.preprocessor = preprocessor;
    }
    
    @PostConstruct
    public void buildIndex() throws IOException {
        trainingExamples = loadTrainingExamples();
        vocabulary = buildNgramVocabulary(trainingExamples, 1, 3);
        idfWeights = computeIdfWeights(trainingExamples, vocabulary);
        
        indexedVectors = new ArrayList<>();
        indexedLabels = new ArrayList<>();
        
        for (TrainingExample ex : trainingExamples) {
            String preprocessed = preprocessor.process(ex.getText()).getCleanedText();
            double[] vector = vectorize(preprocessed);
            indexedVectors.add(vector);
            indexedLabels.add(ex.getCanonicalField());
        }
    }
    
    private List<TrainingExample> loadTrainingExamples() throws IOException {
        ClassPathResource resource = new ClassPathResource("mapping/training_examples.json");
        List<Map<String, String>> rawData = objectMapper.readValue(
            resource.getInputStream(), 
            new TypeReference<List<Map<String, String>>>() {}
        );
        
        return rawData.stream()
            .map(m -> new TrainingExample(m.get("text"), m.get("canonicalField")))
            .collect(Collectors.toList());
    }
    
    private List<String> buildNgramVocabulary(List<TrainingExample> examples, int minN, int maxN) {
        Set<String> ngramSet = new HashSet<>();
        
        for (TrainingExample ex : examples) {
            String preprocessed = preprocessor.process(ex.getText()).getCleanedText();
            List<String> tokens = Arrays.asList(preprocessed.split(" "));
            
            for (int n = minN; n <= maxN; n++) {
                for (int i = 0; i <= tokens.size() - n; i++) {
                    String ngram = String.join(" ", tokens.subList(i, i + n));
                    ngramSet.add(ngram);
                }
            }
        }
        
        return new ArrayList<>(ngramSet);
    }
    
    private Map<String, Double> computeIdfWeights(List<TrainingExample> examples, List<String> vocab) {
        Map<String, Integer> documentFreq = new HashMap<>();
        
        for (TrainingExample ex : examples) {
            String preprocessed = preprocessor.process(ex.getText()).getCleanedText();
            Set<String> docNgrams = new HashSet<>();
            
            List<String> tokens = Arrays.asList(preprocessed.split(" "));
            for (int n = 1; n <= 3; n++) {
                for (int i = 0; i <= tokens.size() - n; i++) {
                    String ngram = String.join(" ", tokens.subList(i, i + n));
                    docNgrams.add(ngram);
                }
            }
            
            for (String ngram : docNgrams) {
                documentFreq.merge(ngram, 1, Integer::sum);
            }
        }
        
        Map<String, Double> idf = new HashMap<>();
        int numDocs = examples.size();
        
        for (String term : vocab) {
            int df = documentFreq.getOrDefault(term, 0);
            if (df > 0) {
                idf.put(term, Math.log((double) numDocs / df));
            } else {
                idf.put(term, 0.0);
            }
        }
        
        return idf;
    }
    
    public double[] vectorize(String cleanedText) {
        double[] vector = new double[vocabulary.size()];
        Map<String, Integer> termFreq = new HashMap<>();
        
        List<String> tokens = Arrays.asList(cleanedText.split(" "));
        
        // Count term frequencies for all n-grams
        for (int n = 1; n <= 3; n++) {
            for (int i = 0; i <= tokens.size() - n; i++) {
                String ngram = String.join(" ", tokens.subList(i, i + n));
                termFreq.merge(ngram, 1, Integer::sum);
            }
        }
        
        // Compute TF-IDF vector
        for (int i = 0; i < vocabulary.size(); i++) {
            String term = vocabulary.get(i);
            int tf = termFreq.getOrDefault(term, 0);
            double idf = idfWeights.getOrDefault(term, 0.0);
            vector[i] = tf * idf;
        }
        
        // Normalize the vector
        double norm = 0.0;
        for (double v : vector) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= norm;
            }
        }
        
        return vector;
    }
    
    public List<ScoredExample> findNearestNeighbors(double[] queryVector, int k) {
        List<ScoredExample> results = new ArrayList<>();
        
        for (int i = 0; i < indexedVectors.size(); i++) {
            double similarity = cosineSimilarity(queryVector, indexedVectors.get(i));
            results.add(new ScoredExample(indexedLabels.get(i), similarity));
        }
        
        results.sort(Comparator.comparingDouble(ScoredExample::getSimilarity).reversed());
        
        return results.stream().limit(k).collect(Collectors.toList());
    }
    
    private double cosineSimilarity(double[] v1, double[] v2) {
        double dot = 0.0;
        for (int i = 0; i < v1.length; i++) {
            dot += v1[i] * v2[i];
        }
        return dot;
    }
    
    public static class ScoredExample {
        private final String canonicalField;
        private final double similarity;
        
        public ScoredExample(String canonicalField, double similarity) {
            this.canonicalField = canonicalField;
            this.similarity = similarity;
        }
        
        public String getCanonicalField() {
            return canonicalField;
        }
        
        public double getSimilarity() {
            return similarity;
        }
    }
}
