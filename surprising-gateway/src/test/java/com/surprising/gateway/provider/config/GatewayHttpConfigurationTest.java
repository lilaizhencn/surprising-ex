package com.surprising.gateway.provider.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;

class GatewayHttpConfigurationTest {

    @Test
    void restClientUsesConfiguredTimeouts() {
        GatewayProperties properties = new GatewayProperties();
        properties.getHttpClient().setConnectTimeout(Duration.ofMillis(750));
        properties.getHttpClient().setReadTimeout(Duration.ofSeconds(2));

        var config = new GatewayHttpConfiguration();
        var httpClient = config.gatewayHttpClient(properties);
        var requestFactory = config.requestFactory(httpClient, properties.getHttpClient().getReadTimeout());

        assertThat(requestFactory).isInstanceOf(JdkClientHttpRequestFactory.class);
        assertThat(httpClient.connectTimeout()).contains(Duration.ofMillis(750));
        assertThat(ReflectionTestUtils.getField(requestFactory, "readTimeout")).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void custodyRestClientUsesCustodyWalletTimeout() {
        GatewayProperties properties = new GatewayProperties();
        properties.getCustodyWallet().setRequestTimeout(Duration.ofMillis(1500));

        var config = new GatewayHttpConfiguration();
        var httpClient = config.gatewayHttpClient(properties);
        var requestFactory = config.requestFactory(httpClient, properties.getCustodyWallet().getRequestTimeout());

        assertThat(requestFactory).isInstanceOf(JdkClientHttpRequestFactory.class);
        assertThat(ReflectionTestUtils.getField(requestFactory, "readTimeout")).isEqualTo(Duration.ofMillis(1500));
    }
}
