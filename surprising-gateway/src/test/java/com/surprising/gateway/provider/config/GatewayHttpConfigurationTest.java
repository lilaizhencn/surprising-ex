package com.surprising.gateway.provider.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;

class GatewayHttpConfigurationTest {

    @Test
    void restTemplateUsesConfiguredTimeouts() {
        GatewayProperties properties = new GatewayProperties();
        properties.getHttpClient().setConnectTimeout(Duration.ofMillis(750));
        properties.getHttpClient().setReadTimeout(Duration.ofSeconds(2));

        var config = new GatewayHttpConfiguration();
        var httpClient = config.gatewayHttpClient(properties);
        var restTemplate = config.gatewayRestTemplate(properties, httpClient);

        assertThat(restTemplate.getRequestFactory()).isInstanceOf(JdkClientHttpRequestFactory.class);
        JdkClientHttpRequestFactory requestFactory =
                (JdkClientHttpRequestFactory) restTemplate.getRequestFactory();
        assertThat(httpClient.connectTimeout()).contains(Duration.ofMillis(750));
        assertThat(ReflectionTestUtils.getField(requestFactory, "readTimeout")).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void custodyRestTemplateUsesCustodyWalletTimeout() {
        GatewayProperties properties = new GatewayProperties();
        properties.getCustodyWallet().setRequestTimeout(Duration.ofMillis(1500));

        var config = new GatewayHttpConfiguration();
        var httpClient = config.gatewayHttpClient(properties);
        var restTemplate = config.custodyWalletRestTemplate(properties, httpClient);

        assertThat(restTemplate.getRequestFactory()).isInstanceOf(JdkClientHttpRequestFactory.class);
        JdkClientHttpRequestFactory requestFactory =
                (JdkClientHttpRequestFactory) restTemplate.getRequestFactory();
        assertThat(ReflectionTestUtils.getField(requestFactory, "readTimeout")).isEqualTo(Duration.ofMillis(1500));
    }
}
