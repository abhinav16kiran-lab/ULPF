package com.ulpf.dataplane.service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

@Service
public class EventIngestionService {

    // fake API key -> vendorId/sourceId mapping
    // replace with real `credentials` table lookup later
    private static final Map<String, String> fakeApiKeys = new ConcurrentHashMap<>(Map.of(
        "test-api-key-vendor1", "vendor1",
        "test-api-key-vendor2", "vendor2"
    ));

    public record IngestResult(String eventId, String vendorId, String status, LocalDateTime receivedAt) {}

    public String resolveVendorFromApiKey(String apiKey) {
        return fakeApiKeys.get(apiKey); // returns null if not found
    }

    public IngestResult ingest(String vendorId, Object payload) {
        String eventId = UUID.randomUUID().toString();


        return new IngestResult(eventId, vendorId, "ACCEPTED", LocalDateTime.now());
    }
}