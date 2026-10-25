package com.ulpf.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulpf.common.GlobalExceptionHandler;
import com.ulpf.common.UlpfPrincipal;
import com.ulpf.controlplane.service.VendorWebhookService;
import com.ulpf.controlplane.service.VendorWebhookService.DeliveryLogEntry;
import com.ulpf.controlplane.service.VendorWebhookService.VendorWebhookResponse;
import com.ulpf.controlplane.service.VendorWebhookService.WebhookConfigRequest;
import com.ulpf.dataplane.service.AlertDispatcher.DeliveryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class VendorWebhookControllerTest {

    @Mock
    private VendorWebhookService vendorWebhookService;

    private MockMvc mockMvc;
    private UlpfPrincipal currentPrincipal;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        currentPrincipal = new UlpfPrincipal("user-vendor-1", "vendoruser", "VENDOR");

        HandlerMethodArgumentResolver principalResolver = new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.getParameterType().equals(UlpfPrincipal.class);
            }

            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                          NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
                return currentPrincipal;
            }
        };

        VendorWebhookController controller = new VendorWebhookController(vendorWebhookService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(principalResolver)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void testGetWebhook_Success() throws Exception {
        VendorWebhookResponse response = new VendorWebhookResponse(
                true,
                "https://hooks.slack.com/services/test",
                "whsec_••••1234",
                "HEALTHY",
                200,
                LocalDateTime.now(),
                0,
                List.of(new DeliveryLogEntry("SCHEMA_DRIFT", 200, true, 120, LocalDateTime.now()))
        );
        when(vendorWebhookService.getWebhook("user-vendor-1")).thenReturn(response);

        mockMvc.perform(get("/v1/vendor/webhook"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.webhookUrl").value("https://hooks.slack.com/services/test"))
                .andExpect(jsonPath("$.maskedSecret").value("whsec_••••1234"))
                .andExpect(jsonPath("$.status").value("HEALTHY"))
                .andExpect(jsonPath("$.recentLogs[0].alertType").value("SCHEMA_DRIFT"));
    }

    @Test
    void testGetWebhook_NotFound() throws Exception {
        when(vendorWebhookService.getWebhook("user-vendor-1"))
                .thenThrow(new IllegalArgumentException("No vendor organization found for user: user-vendor-1"));

        mockMvc.perform(get("/v1/vendor/webhook"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void testSaveOrUpdateWebhook_Success() throws Exception {
        WebhookConfigRequest request = new WebhookConfigRequest("https://example.com/webhook", "mysecret");
        VendorWebhookResponse response = new VendorWebhookResponse(
                true,
                "https://example.com/webhook",
                "myse••••cret",
                "UNTESTED",
                null,
                null,
                0,
                List.of()
        );
        when(vendorWebhookService.saveOrUpdateWebhook(eq("user-vendor-1"), any())).thenReturn(response);

        mockMvc.perform(put("/v1/vendor/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.status").value("UNTESTED"));
    }

    @Test
    void testSaveOrUpdateWebhook_InvalidUrl() throws Exception {
        WebhookConfigRequest request = new WebhookConfigRequest("not-a-valid-url", "mysecret");
        when(vendorWebhookService.saveOrUpdateWebhook(eq("user-vendor-1"), any()))
                .thenThrow(new IllegalArgumentException("Webhook URL must be a valid HTTP or HTTPS URL with a hostname"));

        mockMvc.perform(put("/v1/vendor/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Webhook URL must be a valid HTTP or HTTPS URL with a hostname"));
    }

    @Test
    void testDeleteWebhook_Success() throws Exception {
        doNothing().when(vendorWebhookService).deleteWebhook("user-vendor-1");

        mockMvc.perform(delete("/v1/vendor/webhook"))
                .andExpect(status().isNoContent());

        verify(vendorWebhookService).deleteWebhook("user-vendor-1");
    }

    @Test
    void testSendTestPing_Success() throws Exception {
        DeliveryResult result = new DeliveryResult(true, 200, null, 85);
        when(vendorWebhookService.sendTestPing("user-vendor-1")).thenReturn(result);

        mockMvc.perform(post("/v1/vendor/webhook/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.latencyMs").value(85));
    }

    @Test
    void testSendTestPing_NotConfigured() throws Exception {
        when(vendorWebhookService.sendTestPing("user-vendor-1"))
                .thenThrow(new IllegalStateException("No webhook configured. Please save a webhook URL first."));

        mockMvc.perform(post("/v1/vendor/webhook/test"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("No webhook configured. Please save a webhook URL first."));
    }
}
