package com.surprising.gateway.c2c;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.surprising.gateway.c2c.controller.C2cAdminRequestBinding;
import com.surprising.gateway.c2c.controller.advice.C2cHttpErrors;
import com.surprising.gateway.c2c.exception.C2cProblem;
import com.surprising.gateway.c2c.service.C2cAdminAccess;
import com.surprising.gateway.c2c.utils.C2cDigests;
import com.surprising.gateway.provider.auth.service.AdminApprovalService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

class C2cHttpBoundaryTest {
    @RestController static class Probe {
        @GetMapping("/__mvc/c2c/failure") public void fail(@RequestParam String reason) {
            if (reason.equals("state")) throw new IllegalStateException("ciphertext secret 123456");
            if (reason.equals("runtime")) throw new RuntimeException("database password 123456");
            throw new C2cProblem(C2cProblem.Reason.valueOf(reason), "C2C_QA_FAILURE", "business failure");
        }
    }

    @ParameterizedTest @EnumSource(C2cProblem.Reason.class)
    void explicitBusinessReasonsKeepTheirHttpContract(C2cProblem.Reason reason) throws Exception {
        int expected = switch(reason) {
            case AUTHENTICATION_REQUIRED -> 401; case ACCESS_DENIED -> 403; case NOT_FOUND -> 404;
            case RATE_LIMITED -> 429; case CONFLICT -> 409; case UNAVAILABLE -> 503;
        };
        var mvc = MockMvcBuilders.standaloneSetup(new Probe()).setControllerAdvice(new C2cHttpErrors()).build();
        mvc.perform(get("/__mvc/c2c/failure").param("reason", reason.name())).andExpect(status().is(expected))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("C2C_QA_FAILURE"));
        assertThat(new C2cProblem(reason,"test","test").retryable()).isEqualTo(reason==C2cProblem.Reason.UNAVAILABLE);
    }

    @Test void internalStateAndUnexpectedFailuresStaySanitizedAndAreNotBusinessConflicts() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new Probe()).setControllerAdvice(new C2cHttpErrors()).build();
        for (String reason : new String[]{"state", "runtime"}) {
            mvc.perform(get("/__mvc/c2c/failure").param("reason", reason)).andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("C2C_INTERNAL_ERROR"))
                    .andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain("ciphertext", "password", "123456"));
        }
        assertThat(C2cHttpErrors.status(new IllegalStateException("internal")).value()).isEqualTo(500);
        assertThat(C2cHttpErrors.status(C2cProblem.conflict("changed")).value()).isEqualTo(409);
    }

    @Test void controllerBindsConfiguredApprovalHeaderAndPreservesOriginalMetadataAndBodyDigest() {
        var approvals = mock(AdminApprovalService.class);when(approvals.approvalHeaderName()).thenReturn("X-QA-Approval");
        var http = new MockHttpServletRequest("POST", "/api/v1/admin/c2c/appeals/resolve");
        http.setQueryString("market=a%2Bb&reason=%E8%AF%B4%E6%98%8E");http.setRemoteAddr("127.0.0.9");
        http.addHeader("X-QA-Approval", "9007199254740993");http.addHeader("User-Agent", "original-agent");
        var bound = new C2cAdminRequestBinding(approvals).bind(http);
        assertThat(bound.approvalId()).isEqualTo("9007199254740993");assertThat(bound.method()).isEqualTo("POST");
        assertThat(bound.path()).isEqualTo(http.getRequestURI());assertThat(bound.query()).isEqualTo(http.getQueryString());
        assertThat(bound.userAgent()).isEqualTo("original-agent");assertThat(bound.ip()).isEqualTo("127.0.0.9");
        byte[] body = " { \"reason\": \"原始正文\" }\r\n".getBytes(StandardCharsets.UTF_8);
        assertThat(C2cAdminAccess.hash(body)).isEqualTo(C2cDigests.sha256(body));
        assertThat(C2cAdminAccess.hash(body)).isNotEqualTo(C2cDigests.sha256(new String(body, StandardCharsets.UTF_8).trim().getBytes(StandardCharsets.UTF_8)));
    }
}
