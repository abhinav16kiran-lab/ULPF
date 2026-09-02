package com.ulpf.mapping.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the AI Mapping Engine.
 * Loads threshold values from application.yaml under the 'mapping' prefix.
 */
@Component
@ConfigurationProperties(prefix = "mapping")
public class MappingConfig {
    
    private double confidenceThreshold;
    private double gapThreshold;
    private double hybridAcceptanceThreshold;
    
    public double getConfidenceThreshold() {
        return confidenceThreshold;
    }
    
    public void setConfidenceThreshold(double confidenceThreshold) {
        this.confidenceThreshold = confidenceThreshold;
    }
    
    public double getGapThreshold() {
        return gapThreshold;
    }
    
    public void setGapThreshold(double gapThreshold) {
        this.gapThreshold = gapThreshold;
    }
    
    public double getHybridAcceptanceThreshold() {
        return hybridAcceptanceThreshold;
    }
    
    public void setHybridAcceptanceThreshold(double hybridAcceptanceThreshold) {
        this.hybridAcceptanceThreshold = hybridAcceptanceThreshold;
    }
}
