package com.surprising.websocket.provider.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class WebSocketServerConfigurationTest {

    @Test
    void usesConfiguredAllowedOriginPatterns() {
        WebSocketProperties properties = new WebSocketProperties();
        properties.getSecurity().setAllowedOrigins(List.of("https://app.example.com", " https://m.example.com "));

        WebSocketServerConfiguration configuration = new WebSocketServerConfiguration(null, properties);

        assertThat(configuration.allowedOriginPatterns())
                .containsExactly("https://app.example.com", "https://m.example.com");
    }

    @Test
    void fallsBackToWildcardForLocalDevelopmentWhenOriginsAreBlank() {
        WebSocketProperties properties = new WebSocketProperties();
        properties.getSecurity().setAllowedOrigins(List.of(" ", ""));

        WebSocketServerConfiguration configuration = new WebSocketServerConfiguration(null, properties);

        assertThat(configuration.allowedOriginPatterns()).containsExactly("*");
    }
}
