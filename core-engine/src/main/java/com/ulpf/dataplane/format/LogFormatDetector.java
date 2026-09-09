package com.ulpf.dataplane.format;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
// import org.slf4j.Logger;
// import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic Header-Based Format Autodetector operating at the Data Plane
 * entry point.
 * Performs constant-time O(1) prefix inspection to detect JSON, Syslog, CEF,
 * LEEF, or RAW_TEXT
 * with zero AI/LLM overhead.
 */
@Component
public class LogFormatDetector {

    // private static final Logger log =
    // LoggerFactory.getLogger(LogFormatDetector.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern FALLBACK_KV_PATTERN = Pattern.compile("([a-zA-Z0-9_.-]+)=([^\\s\"]+|\"[^\"]*\")");

    public FormatDetectionResult detect(Object payload) {
        if (payload == null) {
            return new FormatDetectionResult(LogFormat.RAW_TEXT, Map.of(), "");
        }

        if (payload instanceof Map<?, ?> mapPayload) {
            Map<String, Object> converted = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : mapPayload.entrySet()) {
                if (entry.getKey() != null) {
                    converted.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            try {
                String rawJson = MAPPER.writeValueAsString(converted);
                return new FormatDetectionResult(LogFormat.JSON, converted, rawJson, "JSON_MAP");
            } catch (Exception e) {
                return new FormatDetectionResult(LogFormat.JSON, converted, String.valueOf(payload), "JSON_MAP");
            }
        }

        if (payload instanceof JsonNode nodePayload) {
            try {
                Map<String, Object> converted = MAPPER.convertValue(nodePayload,
                        new TypeReference<Map<String, Object>>() {
                        });
                String rawJson = MAPPER.writeValueAsString(nodePayload);
                return new FormatDetectionResult(LogFormat.JSON, converted != null ? converted : Map.of(), rawJson,
                        "JSON_NODE");
            } catch (Exception e) {
                return new FormatDetectionResult(LogFormat.JSON, Map.of(), nodePayload.toString(), "JSON_NODE");
            }
        }

        String strPayload = String.valueOf(payload);
        return detect(strPayload);
    }

    public FormatDetectionResult detect(String rawPayload) {
        if (rawPayload == null || rawPayload.isBlank()) {
            return new FormatDetectionResult(LogFormat.RAW_TEXT, Map.of(), "");
        }

        String trimmed = rawPayload.trim();

        // 1. Syslog RFC 3164 / 5424 Detection (<PRI> prefix)
        if (trimmed.startsWith("<")) {
            Map<String, Object> fields = SyslogParser.parse(trimmed);
            String headerInfo = fields.containsKey("syslog_pri") ? "Syslog PRI=" + fields.get("syslog_pri")
                    : "Syslog Header";
            return new FormatDetectionResult(LogFormat.SYSLOG, fields, rawPayload, headerInfo);
        }

        // 2. ArcSight CEF Detection (CEF: prefix)
        if (trimmed.startsWith("CEF:")) {
            Map<String, Object> fields = CefParser.parse(trimmed);
            String headerInfo = "CEF v=" + fields.getOrDefault("cef_version", "0") + " Vendor="
                    + fields.getOrDefault("device_vendor", "unknown");
            return new FormatDetectionResult(LogFormat.CEF, fields, rawPayload, headerInfo);
        }

        // 3. IBM QRadar LEEF Detection (LEEF: prefix)
        if (trimmed.startsWith("LEEF:")) {
            Map<String, Object> fields = LeefParser.parse(trimmed);
            String headerInfo = "LEEF v=" + fields.getOrDefault("leef_version", "1.0") + " Vendor="
                    + fields.getOrDefault("vendor", "unknown");
            return new FormatDetectionResult(LogFormat.LEEF, fields, rawPayload, headerInfo);
        }

        // 4. JSON / JSON Array Detection ({ or [ prefix)
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                JsonNode root = MAPPER.readTree(trimmed);
                if (root.isObject()) {
                    Map<String, Object> fields = MAPPER.convertValue(root, new TypeReference<Map<String, Object>>() {
                    });
                    return new FormatDetectionResult(LogFormat.JSON, fields != null ? fields : Map.of(), rawPayload,
                            "JSON_OBJECT");
                } else if (root.isArray()) {
                    Map<String, Object> fields = new LinkedHashMap<>();
                    fields.put("json_array_size", root.size());
                    fields.put("json_payload", MAPPER.convertValue(root, Object.class));
                    return new FormatDetectionResult(LogFormat.JSON, fields, rawPayload, "JSON_ARRAY");
                }
            } catch (Exception ignored) {
                // Not valid JSON despite starting bracket, fall through to key-value fallback
            }
        }

        // 5. Fallback RAW_TEXT with generic key-value parsing
        Map<String, Object> fields = new LinkedHashMap<>();
        Matcher kvMatcher = FALLBACK_KV_PATTERN.matcher(trimmed);
        while (kvMatcher.find()) {
            String key = kvMatcher.group(1);
            String val = kvMatcher.group(2);
            if (val.startsWith("\"") && val.endsWith("\"") && val.length() >= 2) {
                val = val.substring(1, val.length() - 1);
            }
            fields.putIfAbsent(key, val);
        }
        fields.put("raw_message", trimmed);

        return new FormatDetectionResult(LogFormat.RAW_TEXT, fields, rawPayload, "RAW_TEXT_FALLBACK");
    }
}
