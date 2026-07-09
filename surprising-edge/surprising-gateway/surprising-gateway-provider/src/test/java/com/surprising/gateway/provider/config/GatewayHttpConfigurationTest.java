package com.surprising.gateway.provider.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;

class GatewayHttpConfigurationTest {

    @Test
    void restTemplateUsesConfiguredTimeouts() {
        GatewayProperties properties = new GatewayProperties();
        properties.getHttpClient().setConnectTimeout(Duration.ofMillis(750));
        properties.getHttpClient().setReadTimeout(Duration.ofSeconds(2));

        var restTemplate = new GatewayHttpConfiguration().gatewayRestTemplate(properties);

        assertThat(restTemplate.getRequestFactory()).isInstanceOf(SimpleClientHttpRequestFactory.class);
        SimpleClientHttpRequestFactory requestFactory =
                (SimpleClientHttpRequestFactory) restTemplate.getRequestFactory();
        assertThat(ReflectionTestUtils.getField(requestFactory, "connectTimeout")).isEqualTo(750);
        assertThat(ReflectionTestUtils.getField(requestFactory, "readTimeout")).isEqualTo(2000);
    }
}
