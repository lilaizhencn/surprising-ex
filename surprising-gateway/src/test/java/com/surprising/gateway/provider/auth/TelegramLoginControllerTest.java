package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TelegramLoginControllerTest {
    @Test
    void browserCallbackErrorsRemainNoStoreHtmlWithoutReflectingProviderSecrets() throws Exception {
        var service = mock(TelegramLoginService.class);
        when(service.callback("expired", "private-code", null))
                .thenThrow(new IllegalArgumentException("private-code / database detail"));
        var mvc = MockMvcBuilders.standaloneSetup(new TelegramLoginController(service)).build();
        var response = mvc.perform(get("/api/v1/auth/telegram/callback")
                        .param("state", "expired").param("code", "private-code")
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).contains("Surprising").doesNotContain("private-code", "database detail");
    }

    @Test
    void unavailableCallbackDoesNotRenderFrameworkErrorOrSensitiveException() throws Exception {
        var service = mock(TelegramLoginService.class);
        when(service.callback(null, null, null)).thenThrow(new IllegalStateException("private detail"));
        var mvc = MockMvcBuilders.standaloneSetup(new TelegramLoginController(service)).build();
        var response = mvc.perform(get("/api/v1/auth/telegram/callback").accept(MediaType.TEXT_HTML))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).contains("Surprising").doesNotContain("private detail");
    }

    @Test
    void successfulCallbackReturnsOnlyAnInstructionToReturnToTheApp() throws Exception {
        var service = mock(TelegramLoginService.class);
        when(service.callback("state", "code", null)).thenReturn(true);
        var mvc = MockMvcBuilders.standaloneSetup(new TelegramLoginController(service)).build();
        var response = mvc.perform(get("/api/v1/auth/telegram/callback")
                        .param("state", "state").param("code", "code").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(response).contains("Surprising").doesNotContain("accessToken", "refreshToken");
    }
}
