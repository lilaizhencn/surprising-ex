package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.config.GatewayProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class GatewayApiKeyAuthenticationIpTest {

    @Test
    void authenticatesUsingForwardedClientIpWhenPeerIsTrusted() {
        GatewayProperties properties = new GatewayProperties();
        properties.getSecurity().setTrustedProxyIpAllowlist(List.of("192.0.2.0/24"));
        TotpService totpService = new TotpService(properties);
        GatewayApiKeyRepository repository = mock(GatewayApiKeyRepository.class);
        GatewayApiKeyService service = new GatewayApiKeyService(repository, null, totpService, null, properties);
        String apiKey = "sx_" + "a".repeat(24);
        GatewayApiKeyRepository.ApiKeyRecord record = record(totpService, apiKey, "10.8.0.0/16");
        when(repository.active(apiKey)).thenReturn(Optional.of(record));
        MockHttpServletRequest request = signedRequest(service, apiKey, "192.0.2.10", "10.8.2.3");

        service.authenticate(request, "READ");
    }

    @Test
    void rejectsForwardedClientIpWhenPeerIsNotTrusted() {
        GatewayProperties properties = new GatewayProperties();
        properties.getSecurity().setTrustedProxyIpAllowlist(List.of("192.0.2.0/24"));
        TotpService totpService = new TotpService(properties);
        GatewayApiKeyRepository repository = mock(GatewayApiKeyRepository.class);
        GatewayApiKeyService service = new GatewayApiKeyService(repository, null, totpService, null, properties);
        String apiKey = "sx_" + "b".repeat(24);
        GatewayApiKeyRepository.ApiKeyRecord record = record(totpService, apiKey, "10.8.0.0/16");
        when(repository.active(apiKey)).thenReturn(Optional.of(record));
        MockHttpServletRequest request = signedRequest(service, apiKey, "203.0.113.10", "10.8.2.3");

        assertThatThrownBy(() -> service.authenticate(request, "READ"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("api key IP address is not allowed");
    }

    private GatewayApiKeyRepository.ApiKeyRecord record(TotpService totpService, String apiKey, String allowlist) {
        return new GatewayApiKeyRepository.ApiKeyRecord(
                UUID.randomUUID(), 42L, apiKey, totpService.encryptSecret("secret"), "test", "READ",
                allowlist, "ACTIVE", Instant.now(), null, null);
    }

    private MockHttpServletRequest signedRequest(GatewayApiKeyService service, String apiKey,
                                                 String remoteAddress, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v3/account");
        request.setRemoteAddr(remoteAddress);
        request.addHeader("X-MBX-APIKEY", apiKey);
        request.addHeader("X-Forwarded-For", forwardedFor);
        request.addParameter("timestamp", Long.toString(System.currentTimeMillis()));
        request.addParameter("signature", service.sign("secret", service.canonicalRequest(request)));
        return request;
    }
}
