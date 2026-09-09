package com.ulpf.dataplane.format;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LogFormatDetectorTest {

    private LogFormatDetector detector;

    @BeforeEach
    void setUp() {
        detector = new LogFormatDetector();
    }

    @Test
    @DisplayName("Should autodetect and parse Syslog RFC 3164 payload")
    void testSyslogRfc3164DetectionAndParsing() {
        String payload = "<34>Oct 11 22:14:15 mymachine su: 'su root' failed for lonvick on /dev/pts/8";
        FormatDetectionResult result = detector.detect(payload);

        assertEquals(LogFormat.SYSLOG, result.detectedFormat());
        Map<String, Object> fields = result.parsedFields();

        assertEquals(34, fields.get("syslog_pri"));
        assertEquals(4, fields.get("syslog_facility")); // 34 / 8 = 4 (security/auth)
        assertEquals(2, fields.get("syslog_severity")); // 34 % 8 = 2 (critical)
        assertEquals("security/auth", fields.get("facility_name"));
        assertEquals("critical", fields.get("severity_name"));
    }

    @Test
    @DisplayName("Should autodetect and parse Syslog RFC 5424 payload with key-value attributes")
    void testSyslogRfc5424DetectionAndParsing() {
        String payload = "<134>1 2026-09-09T12:00:00Z firewall.east.net fw_service 4912 ID47 src=192.168.1.50 dst=10.0.0.12 action=ALLOW bytes=1420";
        FormatDetectionResult result = detector.detect(payload);

        assertEquals(LogFormat.SYSLOG, result.detectedFormat());
        Map<String, Object> fields = result.parsedFields();

        assertEquals(134, fields.get("syslog_pri"));
        assertEquals(16, fields.get("syslog_facility")); // local4
        assertEquals(6, fields.get("syslog_severity")); // info
        assertEquals("192.168.1.50", fields.get("src"));
        assertEquals("10.0.0.12", fields.get("dst"));
        assertEquals("ALLOW", fields.get("action"));
        assertEquals(1420L, fields.get("bytes"));
    }

    @Test
    @DisplayName("Should autodetect and parse ArcSight Common Event Format (CEF)")
    void testCefDetectionAndParsing() {
        String payload = "CEF:0|Security|Firewall|1.0|100|Connection Allowed|5|src=192.168.1.50 dst=10.0.0.12 act=ALLOW spt=443";
        FormatDetectionResult result = detector.detect(payload);

        assertEquals(LogFormat.CEF, result.detectedFormat());
        Map<String, Object> fields = result.parsedFields();

        assertEquals("0", fields.get("cef_version"));
        assertEquals("Security", fields.get("device_vendor"));
        assertEquals("Firewall", fields.get("device_product"));
        assertEquals("1.0", fields.get("device_version"));
        assertEquals("100", fields.get("signature_id"));
        assertEquals("Connection Allowed", fields.get("name"));
        assertEquals(5L, fields.get("severity"));
        assertEquals("192.168.1.50", fields.get("src"));
        assertEquals("10.0.0.12", fields.get("dst"));
        assertEquals("ALLOW", fields.get("act"));
        assertEquals(443L, fields.get("spt"));
    }

    @Test
    @DisplayName("Should autodetect and parse IBM QRadar LEEF")
    void testLeefDetectionAndParsing() {
        String payload = "LEEF:2.0|IBM|QRadar|7.3|AuthFailed|usrName=admin src=10.0.0.5 proto=TCP";
        FormatDetectionResult result = detector.detect(payload);

        assertEquals(LogFormat.LEEF, result.detectedFormat());
        Map<String, Object> fields = result.parsedFields();

        assertEquals("2.0", fields.get("leef_version"));
        assertEquals("IBM", fields.get("vendor"));
        assertEquals("QRadar", fields.get("product"));
        assertEquals("7.3", fields.get("version"));
        assertEquals("AuthFailed", fields.get("event_id"));
        assertEquals("admin", fields.get("usrName"));
        assertEquals("10.0.0.5", fields.get("src"));
        assertEquals("TCP", fields.get("proto"));
    }

    @Test
    @DisplayName("Should autodetect and parse JSON payload")
    void testJsonDetectionAndParsing() {
        String payload = "{\"src_ip\":\"192.168.1.50\", \"status_code\":200, \"action\":\"ALLOW\"}";
        FormatDetectionResult result = detector.detect(payload);

        assertEquals(LogFormat.JSON, result.detectedFormat());
        Map<String, Object> fields = result.parsedFields();

        assertEquals("192.168.1.50", fields.get("src_ip"));
        assertEquals(200, fields.get("status_code"));
        assertEquals("ALLOW", fields.get("action"));
    }
}
