package com.ulpf.controlplane.service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class OnboardingService {

    public record OnboardingRequest(
            String requestId,
            String submittedBy,
            String vendorName,
            String sourceName,
            String sourceType,
            String sampleLogsContent,
            String schemaDocsContent,
            String status,
            LocalDateTime createdAt
    ) {}

    // fake in-memory store, keyed by requestId
    private static final Map<String, OnboardingRequest> fake_requests = new ConcurrentHashMap<>();

    public OnboardingRequest submitRequest(
            String username,
            String vendorName,
            String sourceName,
            String sourceType,
            MultipartFile sampleLogFile,
            MultipartFile schemaFile
    ) {
        String requestId = UUID.randomUUID().toString();

        String sampleLogsContent = readFileSafely(sampleLogFile);
        String schemaDocsContent = readFileSafely(schemaFile);

        OnboardingRequest request = new OnboardingRequest(
                requestId,
                username,
                vendorName,
                sourceName,
                sourceType,
                sampleLogsContent,
                schemaDocsContent,
                "SUBMITTED",
                LocalDateTime.now()
        );

        fake_requests.put(requestId, request);
        return request;
    }

    private String readFileSafely(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return null;
        }
        try {
            return new String(file.getBytes());
        } catch (IOException e) {
            return "error reading file: " + e.getMessage();
        }
    }
}