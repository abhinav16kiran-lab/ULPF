package com.ulpf.dataplane.format;

// import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for Syslog RFC 3164 (BSD Syslog) and RFC 5424 log messages.
 * Extracts priority, facility, severity, timestamp, hostname, app name, and
 * key-value payload attributes.
 */
public class SyslogParser {

    private static final String[] FACILITIES = {
            "kernel", "user", "mail", "system", "security/auth", "syslog", "printer", "network",
            "uucp", "clock", "security/auth", "ftp", "ntp", "log_audit", "log_alert", "clock",
            "local0", "local1", "local2", "local3", "local4", "local5", "local6", "local7"
    };

    private static final String[] SEVERITIES = {
            "emergency", "alert", "critical", "error", "warning", "notice", "informational", "debug"
    };

    // Matcher for PRI: <134> or <34>
    private static final Pattern PRI_PATTERN = Pattern.compile("^<(\\d{1,3})>");
    // Key-value matcher inside Syslog payload (e.g., src=1.2.3.4 dst=10.0.0.1
    // action=ALLOW)
    private static final Pattern KV_PATTERN = Pattern.compile("([a-zA-Z0-9_.-]+)=([^\\s\"]+|\"[^\"]*\")");

    public static Map<String, Object> parse(String payload) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (payload == null || payload.isBlank()) {
            return fields;
        }

        String message = payload.trim();
        Matcher priMatcher = PRI_PATTERN.matcher(message);

        if (priMatcher.find()) {
            try {
                int pri = Integer.parseInt(priMatcher.group(1));
                fields.put("syslog_pri", pri);
                int facility = pri / 8;
                int severity = pri % 8;

                fields.put("syslog_facility", facility);
                fields.put("syslog_severity", severity);
                fields.put("facility_name", facility < FACILITIES.length ? FACILITIES[facility] : "unknown");
                fields.put("severity_name", severity < SEVERITIES.length ? SEVERITIES[severity] : "unknown");

                // Strip PRI prefix from message
                message = message.substring(priMatcher.end()).trim();
            } catch (NumberFormatException ignored) {
            }
        }

        // Check if message starts with RFC 5424 version indicator (e.g. "1 ")
        if (message.matches("^\\d+\\s+.*")) {
            int firstSpace = message.indexOf(' ');
            if (firstSpace > 0) {
                fields.put("syslog_version", message.substring(0, firstSpace));
                message = message.substring(firstSpace + 1).trim();
            }
        }

        // Extract any embedded key=value or key="value" pairs from the body
        Matcher kvMatcher = KV_PATTERN.matcher(message);
        while (kvMatcher.find()) {
            String key = kvMatcher.group(1);
            String val = kvMatcher.group(2);
            if (val.startsWith("\"") && val.endsWith("\"") && val.length() >= 2) {
                val = val.substring(1, val.length() - 1);
            }
            fields.putIfAbsent(key, parseTypedValue(val));
        }

        // Always preserve body message
        fields.put("message", message);

        return fields;
    }

    private static Object parseTypedValue(String val) {
        if (val == null)
            return null;
        if ("true".equalsIgnoreCase(val))
            return true;
        if ("false".equalsIgnoreCase(val))
            return false;
        try {
            if (!val.contains(".")) {
                return Long.parseLong(val);
            } else {
                return Double.parseDouble(val);
            }
        } catch (NumberFormatException ignored) {
        }
        return val;
    }
}
