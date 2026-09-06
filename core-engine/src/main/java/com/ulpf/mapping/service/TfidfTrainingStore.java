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
    private List<Posting>[] invertedIndex;
    
    public TfidfTrainingStore(FieldPreprocessor preprocessor) {
        this.preprocessor = preprocessor;
    }
    
    @SuppressWarnings("unchecked")
    @PostConstruct
    public void buildIndex() throws IOException {
        trainingExamples = loadTrainingExamples();
        vocabulary = buildNgramVocabulary(trainingExamples, 1, 3);
        idfWeights = computeIdfWeights(trainingExamples, vocabulary);
        
        indexedVectors = new ArrayList<>();
        indexedLabels = new ArrayList<>();
        invertedIndex = new List[vocabulary.size()];
        for (int i = 0; i < vocabulary.size(); i++) {
            invertedIndex[i] = new ArrayList<>();
        }
        
        for (int docId = 0; docId < trainingExamples.size(); docId++) {
            TrainingExample ex = trainingExamples.get(docId);
            String preprocessed = preprocessor.process(ex.getText()).getCleanedText();
            double[] vector = vectorize(preprocessed);
            indexedVectors.add(vector);
            indexedLabels.add(ex.getCanonicalField());
            
            for (int termIdx = 0; termIdx < vector.length; termIdx++) {
                if (vector[termIdx] > 0.0) {
                    invertedIndex[termIdx].add(new Posting(docId, vector[termIdx]));
                }
            }
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
    
    /**
     * High-performance nearest neighbor search using Sparse Inverted Index and Bounded Min-Heap.
     * Only calculates dot products for documents with non-zero term overlaps, and avoids full N-element sorting.
     */
    public List<ScoredExample> findNearestNeighbors(double[] queryVector, int k) {
        if (indexedVectors.isEmpty() || k <= 0) {
            return Collections.emptyList();
        }
        
        int numDocs = indexedVectors.size();
        double[] docScores = new double[numDocs];
        boolean[] touchedDocs = new boolean[numDocs];
        List<Integer> candidateDocIds = new ArrayList<>();
        
        // Sparse inverted index traversal: only compute for documents with overlapping terms
        for (int termIdx = 0; termIdx < queryVector.length; termIdx++) {
            double qWeight = queryVector[termIdx];
            if (qWeight > 0.0 && invertedIndex != null && termIdx < invertedIndex.length) {
                List<Posting> postings = invertedIndex[termIdx];
                for (Posting p : postings) {
                    int docId = p.getDocId();
                    if (!touchedDocs[docId]) {
                        touchedDocs[docId] = true;
                        candidateDocIds.add(docId);
                    }
                    docScores[docId] += qWeight * p.getWeight();
                }
            }
        }
        
        // Bounded Min-Heap PriorityQueue to track top K candidates without sorting all N documents
        PriorityQueue<ScoredExample> minHeap = new PriorityQueue<>(
            Comparator.comparingDouble(ScoredExample::getSimilarity)
        );
        
        for (int docId : candidateDocIds) {
            double similarity = docScores[docId];
            ScoredExample example = new ScoredExample(indexedLabels.get(docId), similarity);
            if (minHeap.size() < k) {
                minHeap.offer(example);
            } else if (similarity > minHeap.peek().getSimilarity()) {
                minHeap.poll();
                minHeap.offer(example);
            }
        }
        
        // Fill remaining top K entries with 0.0 similarity documents if non-zero matches < k
        if (minHeap.size() < k) {
            for (int i = 0; i < indexedLabels.size() && minHeap.size() < k; i++) {
                if (!touchedDocs[i]) {
                    minHeap.offer(new ScoredExample(indexedLabels.get(i), 0.0));
                    touchedDocs[i] = true;
                }
            }
        }
        
        List<ScoredExample> results = new ArrayList<>(minHeap.size());
        while (!minHeap.isEmpty()) {
            results.add(minHeap.poll());
        }
        Collections.reverse(results);
        return results;
    }
    
    public static class Posting {
        private final int docId;
        private final double weight;
        
        public Posting(int docId, double weight) {
            this.docId = docId;
            this.weight = weight;
        }
        
        public int getDocId() {
            return docId;
        }
        
        public double getWeight() {
            return weight;
        }
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
