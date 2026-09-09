package com.ulpf.dataplane.format;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for Micro Focus / ArcSight Common Event Format (CEF).
 * Structure: CEF:Version|Device Vendor|Device Product|Device Version|Signature ID|Name|Severity|Extension
 */
public class CefParser {

    private static final Pattern CEF_PATTERN = Pattern.compile(
            "^CEF:(\\d+)\\|([^|]*)\\|([^|]*)\\|([^|]*)\\|([^|]*)\\|([^|]*)\\|([^|]*)\\|?(.*)$"
    );

    private static final Pattern KV_PATTERN = Pattern.compile("([a-zA-Z0-9_.-]+)=([^\\s\"]+|\"[^\"]*\")");

    public static Map<String, Object> parse(String payload) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (payload == null || payload.isBlank()) {
            return fields;
        }

        String raw = payload.trim();
        Matcher matcher = CEF_PATTERN.matcher(raw);

        if (matcher.find()) {
            fields.put("cef_version", matcher.group(1));
            fields.put("device_vendor", matcher.group(2));
            fields.put("device_product", matcher.group(3));
            fields.put("device_version", matcher.group(4));
            fields.put("signature_id", matcher.group(5));
            fields.put("name", matcher.group(6));
            fields.put("severity", parseTypedValue(matcher.group(7)));

            String extension = matcher.group(8);
            if (extension != null && !extension.isBlank()) {
                parseExtension(extension, fields);
            }
        } else {
            // Fallback key-value extraction if header pipe split fails slightly
            parseExtension(raw, fields);
        }

        return fields;
    }

    private static void parseExtension(String extension, Map<String, Object> fields) {
        Matcher kvMatcher = KV_PATTERN.matcher(extension);
        while (kvMatcher.find()) {
            String key = kvMatcher.group(1);
            String val = kvMatcher.group(2);
            if (val.startsWith("\"") && val.endsWith("\"") && val.length() >= 2) {
                val = val.substring(1, val.length() - 1);
            }
            fields.putIfAbsent(key, parseTypedValue(val));
        }
    }

    private static Object parseTypedValue(String val) {
        if (val == null) return null;
        if ("true".equalsIgnoreCase(val)) return true;
        if ("false".equalsIgnoreCase(val)) return false;
        try {
            if (!val.contains(".")) {
                return Long.parseLong(val);
            } else {
                return Double.parseDouble(val);
            }
        } catch (NumberFormatException ignored) {}
        return val;
    }
}
