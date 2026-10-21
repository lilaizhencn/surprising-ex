package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.controller.TelegramLoginController;
import com.surprising.gateway.provider.auth.service.TelegramLoginService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("default-src 'none'")))
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
    void webCallbackDoesNotLaunchTheApp() throws Exception {
        var service = mock(TelegramLoginService.class);
        when(service.callback("state", "code", null)).thenReturn(new TelegramLoginService.Callback(true, null));
        var mvc = MockMvcBuilders.standaloneSetup(new TelegramLoginController(service)).build();
        var response = mvc.perform(get("/api/v1/auth/telegram/callback")
                        .param("state", "state").param("code", "code").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(response).contains("Surprising").doesNotContain("accessToken", "refreshToken", "surprising://", "window.location.replace");
    }
    @Test
    void mobileCallbackOffersAutomaticAndManualReturnWithoutCredentials() throws Exception {
        var service = mock(TelegramLoginService.class);
        String state = "s".repeat(43);
        String link = "surprising://auth/telegram?flowId=" + state;
        when(service.callback(state, "private-code", null))
                .thenReturn(new TelegramLoginService.Callback(true, link));
        var mvc = MockMvcBuilders.standaloneSetup(new TelegramLoginController(service)).build();
        var response = mvc.perform(get("/api/v1/auth/telegram/callback")
                        .param("state", state).param("code", "private-code").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk()).andReturn().getResponse();
        String body = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains(link, "返回 Surprising", "window.location.replace")
                .doesNotContain("private-code", "accessToken", "refreshToken", "verifier");
        String nonce = body.split("nonce=\"")[1].split("\"")[0];
        assertThat(response.getHeader("Content-Security-Policy")).contains("script-src 'nonce-" + nonce + "'");
    }

    @Test
    void startDefaultsToWebAndOnlyExplicitMobileRequestSelectsAppReturn() throws Exception {
        var service = mock(TelegramLoginService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new TelegramLoginController(service)).build();
        mvc.perform(post("/api/v1/auth/telegram/start").header("X-Device-Id", "device")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        verify(service).start("device", false);
        mvc.perform(post("/api/v1/auth/telegram/start").header("X-Device-Id", "device")
                .contentType(MediaType.APPLICATION_JSON).content("{\"returnToApp\":true}"))
                .andExpect(status().isOk());
        verify(service).start("device", true);
    }

}
