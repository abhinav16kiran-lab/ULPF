package com.ulpf.mapping.service;

import com.ulpf.mapping.model.NormalizedField;
import com.ulpf.mapping.repository.AliasRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Layer 1: Dictionary lookup service.
 * Performs exact-match lookup against known aliases.
 */
@Service
public class AliasLookupService {
    
    private final AliasRepository aliasRepository;
    
    public AliasLookupService(AliasRepository aliasRepository) {
        this.aliasRepository = aliasRepository;
    }
    
    /**
     * Look up a normalized field in the alias dictionary.
     * 
     * @param field the normalized field
     * @return Optional containing the canonical field name if found
     */
    public Optional<String> lookup(NormalizedField field) {
        // Key is cleaned text with all spaces removed
        String key = field.getCleanedText().replace(" ", "");
        return Optional.ofNullable(aliasRepository.getAliasMap().get(key));
    }
}
