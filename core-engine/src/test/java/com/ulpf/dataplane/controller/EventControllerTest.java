package com.ulpf.dataplane.controller;

import com.ulpf.common.GlobalExceptionHandler;
import com.ulpf.dataplane.service.EventIngestionService;
import com.ulpf.dataplane.service.EventIngestionService.IngestResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The ingest endpoint accepts a JSON object, or one raw log line (Syslog, CEF, LEEF) as plain text.
 * Before 2026-11-15, every plain-text body got 415, because the body was bound to {@code Object} and Spring has no
 * converter from text/plain to Object.
 */
@ExtendWith(MockitoExtension.class)
class EventControllerTest {

    private static final String KEY = "ulpf_live_test";
    private static final String SYSLOG = "<134>1 2026-11-15T10:00:00.000Z fw01 firewall 7486 - - action=DENY src=10.0.0.1";

    @Mock
    private EventIngestionService service;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new EventController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        lenient().when(service.resolveVendorFromApiKey(KEY)).thenReturn("vendor-1");
        lenient().when(service.ingest(eq(KEY), any(), any())).thenReturn(
                new IngestResult("evt-1", "vendor-1", "source-1", "ACCEPTED", LocalDateTime.now(), null, "SYSLOG"));
    }

    private Object ingestedPayload() {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(service).ingest(eq(KEY), payload.capture(), any());
        return payload.getValue();
    }

    @Test
    void aRawSyslogLineAsTextPlainIsAcceptedAndPassedOnUnchanged() throws Exception {
        mvc.perform(post("/v1/events").header("X-API-Key", KEY).contentType(MediaType.TEXT_PLAIN).content(SYSLOG))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        assertEquals(SYSLOG, ingestedPayload());
    }

    @Test
    void textWithoutACharsetIsReadAsUtf8() throws Exception {
        String cef = "CEF:0|Perf|Firewall|1.0|100|Zugriff verweigert für Müller|3|src=10.0.0.1";

        mvc.perform(post("/v1/events").header("X-API-Key", KEY)
                        .contentType("text/plain").content(cef.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isAccepted());

        assertEquals(cef, ingestedPayload(), "ISO-8859-1 (the HTTP default) would garble the umlauts");
    }

    @Test
    void aDeclaredCharsetIsUsed() throws Exception {
        String leef = "LEEF:2.0|Perf|Firewall|1.0|traffic|^|usrName=Müller";

        mvc.perform(post("/v1/events").header("X-API-Key", KEY)
                        .contentType("text/plain; charset=ISO-8859-1").content(leef.getBytes(StandardCharsets.ISO_8859_1)))
                .andExpect(status().isAccepted());

        assertEquals(leef, ingestedPayload());
    }

    @Test
    void octetStreamIsReadAsText() throws Exception {
        mvc.perform(post("/v1/events").header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(SYSLOG.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isAccepted());

        assertEquals(SYSLOG, ingestedPayload());
    }

    @Test
    void aBodyWithoutAContentTypeIsReadAsText() throws Exception {
        mvc.perform(post("/v1/events").header("X-API-Key", KEY).content(SYSLOG.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isAccepted());

        assertEquals(SYSLOG, ingestedPayload());
    }

    @Test
    void curlsDefaultFormTypeKeepsTheLineAsIs() throws Exception {
        // curl -d sends application/x-www-form-urlencoded. The line must arrive unchanged, not parsed as form fields.
        String line = "action=DENY src=10.0.0.1 msg=a+b%20c";

        mvc.perform(post("/v1/events").header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).content(line))
                .andExpect(status().isAccepted());

        assertEquals(line, ingestedPayload());
    }

    @Test
    void aJsonObjectStillArrivesAsAMap() throws Exception {
        mvc.perform(post("/v1/events").header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"src_ip\":\"10.0.0.1\",\"action\":\"ALLOW\"}"))
                .andExpect(status().isAccepted());

        Map<?, ?> payload = assertInstanceOf(Map.class, ingestedPayload());
        assertEquals("ALLOW", payload.get("action"));
    }

    @Test
    void aJsonStringStillWorks() throws Exception {
        // The workaround that shippers may already use: the raw line wrapped as a JSON string.
        mvc.perform(post("/v1/events").header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("\"" + SYSLOG + "\""))
                .andExpect(status().isAccepted());

        assertEquals(SYSLOG, ingestedPayload());
    }

    @Test
    void anEmptyOrBlankTextBodyIsRejected() throws Exception {
        mvc.perform(post("/v1/events").header("X-API-Key", KEY).contentType(MediaType.TEXT_PLAIN).content("   "))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/events").header("X-API-Key", KEY).contentType(MediaType.TEXT_PLAIN).content(""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anEmptyBodyOfEitherKindGetsA400NotAnAmbiguousMapping() throws Exception {
        // With an optional JSON body, both handlers matched a bodyless request, and Spring answered 409.
        mvc.perform(post("/v1/events").header("X-API-Key", KEY))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/events").header("X-API-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void textWithoutAnApiKeyIsRejectedBeforeAnyWork() throws Exception {
        mvc.perform(post("/v1/events").contentType(MediaType.TEXT_PLAIN).content(SYSLOG))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }
}
