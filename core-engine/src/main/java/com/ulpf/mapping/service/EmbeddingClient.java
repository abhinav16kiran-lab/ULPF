package com.ulpf.mapping.service;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Client for generating embeddings using MiniLM via ONNX Runtime.
 * Wraps Adarsh's ModelLifecycleManager for model load/unload lifecycle.
 */
@Service
public class EmbeddingClient {
    
    private final ModelLifecycleManager modelLifecycleManager;
    
    public EmbeddingClient(ModelLifecycleManager modelLifecycleManager) {
        this.modelLifecycleManager = modelLifecycleManager;
    }
    
    /**
     * Generate embedding vector for the given text.
     * Automatically loads the model if not already loaded.
     * 
     * @param text the text to embed (raw field name or description)
     * @return embedding vector (384 dimensions for all-MiniLM-L6-v2)
     */
    public double[] getEmbedding(String text) {
        try {
            // Ensure model is loaded (idempotent, updates last accessed time)
            modelLifecycleManager.ensureLoaded();
            
            // Get the active session
            OrtSession session = modelLifecycleManager.getSession();
            OrtEnvironment env = OrtEnvironment.getEnvironment();
            
            // Tokenize and prepare input (simplified - real tokenization would be more complex)
            // For MiniLM, we need input_ids, attention_mask, token_type_ids
            // This is a simplified version - in production, use a proper tokenizer
            long[][] inputIds = tokenizeText(text);
            long[][] attentionMask = createAttentionMask(inputIds[0].length);
            long[][] tokenTypeIds = createTokenTypeIds(inputIds[0].length);
            
            // Create tensors
            OnnxTensor inputIdsTensor = OnnxTensor.createTensor(env, inputIds);
            OnnxTensor attentionMaskTensor = OnnxTensor.createTensor(env, attentionMask);
            OnnxTensor tokenTypeIdsTensor = OnnxTensor.createTensor(env, tokenTypeIds);
            
            // Prepare inputs
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", inputIdsTensor);
            inputs.put("attention_mask", attentionMaskTensor);
            inputs.put("token_type_ids", tokenTypeIdsTensor);
            
            // Run inference
            OrtSession.Result results = session.run(inputs);
            
            // Extract embedding from output (typically the [CLS] token embedding or mean pooling)
            float[][] output = (float[][]) results.get(0).getValue();
            double[] embedding = new double[output[0].length];
            for (int i = 0; i < output[0].length; i++) {
                embedding[i] = output[0][i];
            }
            
            // Clean up tensors
            inputIdsTensor.close();
            attentionMaskTensor.close();
            tokenTypeIdsTensor.close();
            results.close();
            
            return embedding;
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate embedding for text: " + text, e);
        }
    }
    
    /**
     * Simple tokenization (placeholder - real implementation would use proper tokenizer).
     * In production, this should use the same tokenizer that was used to train MiniLM.
     */
    private long[][] tokenizeText(String text) {
        // Simplified: just convert characters to token IDs
        // Real tokenizer would handle subword tokenization, special tokens, etc.
        String[] tokens = text.toLowerCase().split("\\s+");
        
        // Add [CLS] at start, [SEP] at end, pad to 128 tokens
        int maxLength = 128;
        long[] tokenIds = new long[maxLength];
        
        tokenIds[0] = 101; // [CLS]
        int pos = 1;
        
        for (String token : tokens) {
            if (pos >= maxLength - 1) break;
            // Simple hash-based token ID (placeholder)
            tokenIds[pos++] = Math.abs(token.hashCode() % 30000) + 1000;
        }
        
        tokenIds[pos] = 102; // [SEP]
        
        return new long[][] { tokenIds };
    }
    
    /**
     * Create attention mask (1 for real tokens, 0 for padding).
     */
    private long[][] createAttentionMask(int length) {
        long[] mask = new long[length];
        for (int i = 0; i < length; i++) {
            mask[i] = 1; // Simplified: all 1s
        }
        return new long[][] { mask };
    }
    
    /**
     * Create token type IDs (all 0s for single sentence).
     */
    private long[][] createTokenTypeIds(int length) {
        long[] typeIds = new long[length];
        return new long[][] { typeIds };
    }
}
