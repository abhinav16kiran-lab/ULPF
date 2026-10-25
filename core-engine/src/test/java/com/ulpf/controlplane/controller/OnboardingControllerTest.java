package com.ulpf.controlplane.controller;

import com.ulpf.common.GlobalExceptionHandler;
import com.ulpf.common.UlpfPrincipal;
import com.ulpf.controlplane.service.OnboardingService;
import com.ulpf.controlplane.service.OnboardingService.OnboardingSubmissionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class OnboardingControllerTest {

        @Mock
        private OnboardingService onboardingService;

        private MockMvc mockMvc;
        private UlpfPrincipal currentPrincipal;

        @BeforeEach
        void setUp() {
                currentPrincipal = new UlpfPrincipal("user-1", "alice", "VENDOR");

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

                OnboardingController controller = new OnboardingController(onboardingService);
                mockMvc = MockMvcBuilders.standaloneSetup(controller)
                                .setCustomArgumentResolvers(principalResolver)
                                .setControllerAdvice(new GlobalExceptionHandler())
                                .build();
        }

        @Test
        void testUpdateSourceSuccessReturns201() throws Exception {
                when(onboardingService.submitUpdateRequest(
                                eq("alice"), eq("source-1"), eq("FIREWALL"), eq("REG_LOG"), any(), any(), any(), any(),
                                any()))
                                .thenReturn(new OnboardingSubmissionResult(
                                                "req-123", "source-1", "vendor-1", null, "SUBMITTED",
                                                "Update submitted successfully."));

                MockMultipartFile sampleFile = new MockMultipartFile(
                                "sampleLogFile", "sample.log", "text/plain", "log data".getBytes());

                mockMvc.perform(multipart("/v1/onboard/update/alice")
                                .file(sampleFile)
                                .param("sourceId", "source-1")
                                .param("sourceType", "FIREWALL")
                                .param("logType", "REG_LOG"))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.requestId").value("req-123"))
                                .andExpect(jsonPath("$.sourceId").value("source-1"))
                                .andExpect(jsonPath("$.status").value("SUBMITTED"));
        }

        @Test
        void testUpdateSourceConflictReturns409WhenPendingRequestExists() throws Exception {
                when(onboardingService.submitUpdateRequest(
                                eq("alice"), eq("source-1"), eq("FIREWALL"), any(), any(), any(), any(), any(), any()))
                                .thenThrow(new IllegalStateException(
                                                "A schema update request for this source is already awaiting review (Request ID: req-999). Please wait for admin approval."));

                MockMultipartFile sampleFile = new MockMultipartFile(
                                "sampleLogFile", "sample.log", "text/plain", "log data".getBytes());

                mockMvc.perform(multipart("/v1/onboard/update/alice")
                                .file(sampleFile)
                                .param("sourceId", "source-1")
                                .param("sourceType", "FIREWALL"))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.error").value(
                                                org.hamcrest.Matchers.containsString("already awaiting review")));
        }

        @Test
        void testUpdateSourceNotFoundReturns400() throws Exception {
                when(onboardingService.submitUpdateRequest(
                                eq("alice"), eq("non-existent"), eq("FIREWALL"), any(), any(), any(), any(), any(),
                                any()))
                                .thenThrow(new IllegalArgumentException("Log source not found: non-existent"));

                MockMultipartFile sampleFile = new MockMultipartFile(
                                "sampleLogFile", "sample.log", "text/plain", "log data".getBytes());

                mockMvc.perform(multipart("/v1/onboard/update/alice")
                                .file(sampleFile)
                                .param("sourceId", "non-existent")
                                .param("sourceType", "FIREWALL"))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.error").value("Log source not found: non-existent"));
        }

        @Test
        void testUpdateSourceInvalidFileExtensionReturns400() throws Exception {
                MockMultipartFile badFile = new MockMultipartFile(
                                "schemaFile", "malicious.exe", "application/octet-stream", "bad binary".getBytes());

                mockMvc.perform(multipart("/v1/onboard/update/alice")
                                .file(badFile)
                                .param("sourceId", "source-1")
                                .param("sourceType", "FIREWALL"))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.error").value("schema file must be .log, .csv, .json, or .txt"));
        }

        @Test
        void testUpdateSourceForbiddenWhenPrincipalMismatchesUsername() throws Exception {
                currentPrincipal = new UlpfPrincipal("user-2", "bob", "VENDOR");

                MockMultipartFile sampleFile = new MockMultipartFile(
                                "sampleLogFile", "sample.log", "text/plain", "log data".getBytes());

                mockMvc.perform(multipart("/v1/onboard/update/alice")
                                .file(sampleFile)
                                .param("sourceId", "source-1")
                                .param("sourceType", "FIREWALL"))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.error")
                                                .value("you can only submit onboarding updates for your own account"));
        }

        @Test
        void testWithdrawRequestSuccessReturns200() throws Exception {
                com.ulpf.common.db.OnboardingRepository.OnboardingRequestRecord record = new com.ulpf.common.db.OnboardingRepository.OnboardingRequestRecord(
                                "req-123", "user-1", "source-1", "SCHEMA_UPDATE", "{}", "REJECTED",
                                java.time.LocalDateTime.now());

                when(onboardingService.withdrawRequest("alice", "req-123")).thenReturn(record);

                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/v1/onboard/withdraw/req-123"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.requestId").value("req-123"))
                                .andExpect(jsonPath("$.status").value("REJECTED"))
                                .andExpect(jsonPath("$.message").value("Request withdrawn successfully"));
        }

        @Test
        void testWithdrawRequestConflictWhenAlreadyTerminalReturns409() throws Exception {
                when(onboardingService.withdrawRequest("alice", "req-123"))
                                .thenThrow(new IllegalStateException(
                                                "Cannot withdraw request in terminal state: REJECTED"));

                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/v1/onboard/withdraw/req-123"))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.error")
                                                .value(org.hamcrest.Matchers.containsString("terminal state")));
        }

        @Test
        void testWithdrawRequestFailsWhenNotOwnerReturns400() throws Exception {
                when(onboardingService.withdrawRequest("alice", "req-999"))
                                .thenThrow(new IllegalArgumentException(
                                                "You can only withdraw your own onboarding requests"));

                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/v1/onboard/withdraw/req-999"))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.error")
                                                .value(org.hamcrest.Matchers.containsString("only withdraw your own")));
        }
}
