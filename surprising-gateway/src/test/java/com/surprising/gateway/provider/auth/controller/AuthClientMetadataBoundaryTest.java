package com.surprising.gateway.provider.auth.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.surprising.gateway.provider.auth.model.AuthClientMetadata;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.auth.service.PasswordResetService;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuthClientMetadataBoundaryTest {
    @Test
    void actualLoginBindsTrustedAddressCanonicalDeviceAndBoundedAgent() throws Exception {
        var properties=new GatewayProperties();properties.getSecurity().setTrustedProxyIpAllowlist(List.of("192.0.2.0/24"));
        var auth=mock(AuthService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new AuthController(auth,mock(PasswordResetService.class),new AuthClientMetadataBinding(properties, org.mockito.Mockito.mock(com.surprising.gateway.provider.service.GatewayRequestLimits.class), org.mockito.Mockito.mock(com.surprising.gateway.provider.controller.GatewayLimitRequestBinding.class)))).build();
        mvc.perform(post("/api/v1/auth/login").with(request->{request.setRemoteAddr("192.0.2.10");return request;})
                .header("X-Forwarded-For","203.0.113.7").header("User-Agent","a".repeat(400))
                .header("X-Device-Id"," A1111111-1111-4111-8111-111111111111 ")
                .contentType(MediaType.APPLICATION_JSON).content("{\"identifier\":\"user\",\"password\":\"password\"}"))
                .andExpect(status().isOk());
        var metadata=ArgumentCaptor.forClass(AuthClientMetadata.class);verify(auth).login(any(),metadata.capture());
        assertThat(metadata.getValue()).isEqualTo(new AuthClientMetadata("a".repeat(300),"203.0.113.7","a1111111-1111-4111-8111-111111111111"));
    }

    @ParameterizedTest
    @ValueSource(strings={"device", "1-1-1-1-1", "", "../device"})
    void malformedDeviceNeverReachesRegistrationBusiness(String device) throws Exception {
        var auth=mock(AuthService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new AuthController(auth,mock(PasswordResetService.class),new AuthClientMetadataBinding(new GatewayProperties(), org.mockito.Mockito.mock(com.surprising.gateway.provider.service.GatewayRequestLimits.class), org.mockito.Mockito.mock(com.surprising.gateway.provider.controller.GatewayLimitRequestBinding.class)))).build();
        mvc.perform(post("/api/v1/auth/register").header("X-Device-Id",device)
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"user@example.com\",\"password\":\"StrongPassword1!\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(auth);
    }

    @Test
    void untrustedPeerCannotReplaceAddressAndCurrentLookupDoesNotParseDevice() {
        var binder=new AuthClientMetadataBinding(new GatewayProperties(), org.mockito.Mockito.mock(com.surprising.gateway.provider.service.GatewayRequestLimits.class), org.mockito.Mockito.mock(com.surprising.gateway.provider.controller.GatewayLimitRequestBinding.class));var request=new org.springframework.mock.web.MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.1");request.addHeader("X-Forwarded-For","10.0.0.1");
        request.addHeader("X-Device-Id","not-a-uuid");
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request));
        try {assertThat(binder.current().ipAddress()).isEqualTo("203.0.113.1");}
        finally {org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();}
        assertThat(binder.current()).isNull();
    }
}
