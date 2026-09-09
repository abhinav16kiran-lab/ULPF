package com.ulpf.dataplane.format;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for IBM QRadar Log Event Extended Format (LEEF 1.0 & 2.0).
 * Structure: LEEF:Version|Vendor|Product|Version|EventID|Extension
 */
public class LeefParser {

    private static final Pattern LEEF_PATTERN = Pattern.compile(
            "^LEEF:([0-9.]+)\\|([^|]*)\\|([^|]*)\\|([^|]*)\\|([^|]*)\\|?(.*)$"
    );

    private static final Pattern KV_PATTERN = Pattern.compile("([a-zA-Z0-9_.-]+)=([^\\s\"]+|\"[^\"]*\")");

    public static Map<String, Object> parse(String payload) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (payload == null || payload.isBlank()) {
            return fields;
        }

        String raw = payload.trim();
        Matcher matcher = LEEF_PATTERN.matcher(raw);

        if (matcher.find()) {
            fields.put("leef_version", matcher.group(1));
            fields.put("vendor", matcher.group(2));
            fields.put("product", matcher.group(3));
            fields.put("version", matcher.group(4));
            fields.put("event_id", matcher.group(5));

            String extension = matcher.group(6);
            if (extension != null && !extension.isBlank()) {
                parseExtension(extension, fields);
            }
        } else {
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
