package com.ulpf.mapping.service;

import com.ulpf.mapping.repository.AliasRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MappingLearningServiceTest {
    
    @Mock
    private CorrectionExtractor correctionExtractor;
    
    @Mock
    private FieldPreprocessor fieldPreprocessor;
    
    @Mock
    private AliasRepository aliasRepository;
    
    private MappingLearningService mappingLearningService;
    
    @BeforeEach
    void setUp() {
        mappingLearningService = new MappingLearningService(
            correctionExtractor,
            fieldPreprocessor,
            aliasRepository
        );
    }
    
    @Test
    void testLearnFromSingleCorrection() {
        // Given
        String oldJson = "{}";
        String newJson = "{}";
        
        CorrectionExtractor.Correction correction = 
            new CorrectionExtractor.Correction("SourceAddress", "url", "src_ip");
        
        when(correctionExtractor.extractCorrections(oldJson, newJson))
            .thenReturn(List.of(correction));
        
        // Mock field preprocessing
        var mockNormalized = mock(com.ulpf.mapping.model.NormalizedField.class);
        when(mockNormalized.getCleanedText()).thenReturn("source address");
        when(fieldPreprocessor.process("SourceAddress")).thenReturn(mockNormalized);
        
        // When
        int aliasesLearned = mappingLearningService.learnFromCorrections(oldJson, newJson);
        
        // Then
        assertEquals(1, aliasesLearned);
        
        // Verify aliasRepository.insertAlias was called with correct parameters
        ArgumentCaptor<String> canonicalCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> aliasKeyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> sourceCaptor = ArgumentCaptor.forClass(String.class);
        
        verify(aliasRepository).insertAlias(
            canonicalCaptor.capture(),
            aliasKeyCaptor.capture(),
            sourceCaptor.capture()
        );
        
        assertEquals("src_ip", canonicalCaptor.getValue());
        assertEquals("sourceaddress", aliasKeyCaptor.getValue()); // "source address" with spaces removed
        assertEquals("human_correction", sourceCaptor.getValue());
    }
    
    @Test
    void testLearnFromMultipleCorrections() {
        // Given
        String oldJson = "{}";
        String newJson = "{}";
        
        List<CorrectionExtractor.Correction> corrections = List.of(
            new CorrectionExtractor.Correction("SrcAddr", "url", "src_ip"),
            new CorrectionExtractor.Correction("DestAddr", "timestamp", "dest_ip")
        );
        
        when(correctionExtractor.extractCorrections(oldJson, newJson))
            .thenReturn(corrections);
        
        // Mock field preprocessing for both
        var mockNormalized1 = mock(com.ulpf.mapping.model.NormalizedField.class);
        when(mockNormalized1.getCleanedText()).thenReturn("src addr");
        when(fieldPreprocessor.process("SrcAddr")).thenReturn(mockNormalized1);
        
        var mockNormalized2 = mock(com.ulpf.mapping.model.NormalizedField.class);
        when(mockNormalized2.getCleanedText()).thenReturn("dest addr");
        when(fieldPreprocessor.process("DestAddr")).thenReturn(mockNormalized2);
        
        // When
        int aliasesLearned = mappingLearningService.learnFromCorrections(oldJson, newJson);
        
        // Then
        assertEquals(2, aliasesLearned);
        verify(aliasRepository, times(2)).insertAlias(anyString(), anyString(), eq("human_correction"));
    }
    
    @Test
    void testNoCorrectionsReturnsZero() {
        // Given
        String oldJson = "{}";
        String newJson = "{}";
        
        when(correctionExtractor.extractCorrections(oldJson, newJson))
            .thenReturn(List.of());
        
        // When
        int aliasesLearned = mappingLearningService.learnFromCorrections(oldJson, newJson);
        
        // Then
        assertEquals(0, aliasesLearned);
        verify(aliasRepository, never()).insertAlias(anyString(), anyString(), anyString());
    }
    
    @Test
    void testSkipCorrectionWithNullCanonicalField() {
        // Given - human set canonical field to null (marked as UNKNOWN)
        String oldJson = "{}";
        String newJson = "{}";
        
        CorrectionExtractor.Correction correction = 
            new CorrectionExtractor.Correction("GibberishField", "url", null);
        
        when(correctionExtractor.extractCorrections(oldJson, newJson))
            .thenReturn(List.of(correction));
        
        // When
        int aliasesLearned = mappingLearningService.learnFromCorrections(oldJson, newJson);
        
        // Then - should not learn
        assertEquals(0, aliasesLearned);
        verify(aliasRepository, never()).insertAlias(anyString(), anyString(), anyString());
    }
    
    @Test
    void testSkipCorrectionWithBlankCanonicalField() {
        // Given
        String oldJson = "{}";
        String newJson = "{}";
        
        CorrectionExtractor.Correction correction = 
            new CorrectionExtractor.Correction("EmptyField", "url", "   ");
        
        when(correctionExtractor.extractCorrections(oldJson, newJson))
            .thenReturn(List.of(correction));
        
        // When
        int aliasesLearned = mappingLearningService.learnFromCorrections(oldJson, newJson);
        
        // Then - should not learn
        assertEquals(0, aliasesLearned);
        verify(aliasRepository, never()).insertAlias(anyString(), anyString(), anyString());
    }
    
    @Test
    void testHandleInsertionFailureGracefully() {
        // Given
        String oldJson = "{}";
        String newJson = "{}";
        
        CorrectionExtractor.Correction correction = 
            new CorrectionExtractor.Correction("SourceAddress", "url", "src_ip");
        
        when(correctionExtractor.extractCorrections(oldJson, newJson))
            .thenReturn(List.of(correction));
        
        var mockNormalized = mock(com.ulpf.mapping.model.NormalizedField.class);
        when(mockNormalized.getCleanedText()).thenReturn("source address");
        when(fieldPreprocessor.process("SourceAddress")).thenReturn(mockNormalized);
        
        // Mock insertion failure
        doThrow(new RuntimeException("DB error"))
            .when(aliasRepository).insertAlias(anyString(), anyString(), anyString());
        
        // When - should not crash
        int aliasesLearned = mappingLearningService.learnFromCorrections(oldJson, newJson);
        
        // Then
        assertEquals(0, aliasesLearned); // Failed to learn, but didn't crash
    }
    
    @Test
    void testFieldNormalization() {
        // Given - test that field normalization matches Layer 1 logic
        String oldJson = "{}";
        String newJson = "{}";
        
        CorrectionExtractor.Correction correction = 
            new CorrectionExtractor.Correction("SourceIPAddress", "url", "src_ip");
        
        when(correctionExtractor.extractCorrections(oldJson, newJson))
            .thenReturn(List.of(correction));
        
        // Mock preprocessing: "SourceIPAddress" → "source ip address"
        var mockNormalized = mock(com.ulpf.mapping.model.NormalizedField.class);
        when(mockNormalized.getCleanedText()).thenReturn("source ip address");
        when(fieldPreprocessor.process("SourceIPAddress")).thenReturn(mockNormalized);
        
        // When
        mappingLearningService.learnFromCorrections(oldJson, newJson);
        
        // Then - verify spaces removed
        ArgumentCaptor<String> aliasKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(aliasRepository).insertAlias(
            eq("src_ip"),
            aliasKeyCaptor.capture(),
            eq("human_correction")
        );
        
        assertEquals("sourceipaddress", aliasKeyCaptor.getValue()); // No spaces
    }
}
