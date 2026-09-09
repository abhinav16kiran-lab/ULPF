package com.ulpf.dataplane.format;

import java.util.Map;

/**
 * Record encapsulating the result of log format autodetection and parsing.
 *
 * @param detectedFormat the identified format type (JSON, SYSLOG, CEF, LEEF, RAW_TEXT)
 * @param parsedFields normalized key-value map extracted from the payload
 * @param rawPayload the un-altered raw payload string
 * @param headerDetails metadata extracted from format header (e.g. Syslog PRI, CEF Vendor/Product, LEEF EventID)
 */
public record FormatDetectionResult(
        LogFormat detectedFormat,
        Map<String, Object> parsedFields,
        String rawPayload,
        String headerDetails
) {
    public FormatDetectionResult(LogFormat detectedFormat, Map<String, Object> parsedFields, String rawPayload) {
        this(detectedFormat, parsedFields, rawPayload, null);
    }
}
