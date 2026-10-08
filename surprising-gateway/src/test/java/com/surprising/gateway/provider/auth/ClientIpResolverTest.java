package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.gateway.provider.config.GatewayProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpResolverTest {

    @Test
    void resolvesFirstUntrustedHopFromTrustedForwardedChain() {
        GatewayProperties properties = new GatewayProperties();
        properties.getSecurity().setTrustedProxyIpAllowlist(List.of("192.0.2.0/24"));
        ClientIpResolver resolver = new ClientIpResolver(properties);
        MockHttpServletRequest request = request("192.0.2.10", "198.51.100.24, 10.8.2.3");

        assertThat(resolver.resolve(request)).isEqualTo("10.8.2.3");
    }

    @Test
    void ignoresForwardedHeaderFromUntrustedPeer() {
        GatewayProperties properties = new GatewayProperties();
        properties.getSecurity().setTrustedProxyIpAllowlist(List.of("192.0.2.0/24"));
        ClientIpResolver resolver = new ClientIpResolver(properties);
        MockHttpServletRequest request = request("203.0.113.10", "10.8.2.3");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.10");
    }

    @Test
    void resolvesCloudflareClientHeaderOnlyFromTrustedProxy() {
        GatewayProperties properties = new GatewayProperties();
        properties.getSecurity().setTrustedProxyIpAllowlist(List.of("192.0.2.0/24"));
        ClientIpResolver resolver = new ClientIpResolver(properties);
        MockHttpServletRequest trusted = request("192.0.2.10", null);
        trusted.addHeader("CF-Connecting-IP", "198.51.100.42");

        MockHttpServletRequest untrusted = request("203.0.113.10", null);
        untrusted.addHeader("CF-Connecting-IP", "198.51.100.42");

        assertThat(resolver.resolve(trusted)).isEqualTo("198.51.100.42");
        assertThat(resolver.resolve(untrusted)).isEqualTo("203.0.113.10");
    }

    @Test
    void supportsCloudVendorHeadersAndFallsBackToForwardedChain() {
        GatewayProperties properties = new GatewayProperties();
        properties.getSecurity().setTrustedProxyIpAllowlist(List.of("192.0.2.0/24"));
        ClientIpResolver resolver = new ClientIpResolver(properties);

        MockHttpServletRequest azure = request("192.0.2.10", null);
        azure.addHeader("X-Azure-ClientIP", "198.51.100.43");
        MockHttpServletRequest cloudFront = request("192.0.2.10", null);
        cloudFront.addHeader("CloudFront-Viewer-Address", "[2001:db8::5]:443");
        MockHttpServletRequest edge = request("192.0.2.10", null);
        edge.addHeader("True-Client-IP", "198.51.100.45");
        MockHttpServletRequest forwarded = request("192.0.2.10", "198.51.100.44, 192.0.2.11");

        assertThat(resolver.resolve(azure)).isEqualTo("198.51.100.43");
        assertThat(resolver.resolve(cloudFront)).isEqualTo("2001:db8::5");
        assertThat(resolver.resolve(edge)).isEqualTo("198.51.100.45");
        assertThat(resolver.resolve(forwarded)).isEqualTo("198.51.100.44");
    }

    @Test
    void ignoresMalformedClientIpHeadersAndDoesNotReturnInvalidForwardedValues() {
        GatewayProperties properties = new GatewayProperties();
        properties.getSecurity().setTrustedProxyIpAllowlist(List.of("192.0.2.0/24"));
        ClientIpResolver resolver = new ClientIpResolver(properties);
        MockHttpServletRequest request = request("192.0.2.10", "not-an-ip");
        request.addHeader("CF-Connecting-IP", "forged-value");

        assertThat(resolver.resolve(request)).isEqualTo("192.0.2.10");
    }

    @Test
    void neverResolvesHostnameInAllowlistOrForwardedHeader() {
        GatewayProperties properties = new GatewayProperties();
        ClientIpResolver resolver = new ClientIpResolver(properties);

        assertThat(resolver.isAllowed("203.0.113.10", List.of("api.example.com/32"))).isFalse();
        assertThat(resolver.isAllowed("203.0.113.10", List.of("203.0.113.0/24"))).isTrue();
        assertThat(resolver.isAllowed("api.example.com", List.of("203.0.113.0/24"))).isFalse();
    }

    private MockHttpServletRequest request(String remoteAddress, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v3/account");
        request.setRemoteAddr(remoteAddress);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }
}
