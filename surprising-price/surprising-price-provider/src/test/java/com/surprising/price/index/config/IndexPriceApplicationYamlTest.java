package com.surprising.price.index.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class IndexPriceApplicationYamlTest {

    @Test
    void integrationConfigurationDisablesRestFallbackForPublicWebSocketSources() throws IOException {
        IndexPriceProperties properties = bind(Map.of("PRODUCT_LINE", "LINEAR_PERPETUAL"));

        assertThat(properties.getWebSocket().isEnabled()).isTrue();
        assertThat(properties.getWebSocket().isRestFallbackEnabled()).isFalse();
    }

    @Test
    void matrixSourceQuorumFreshnessAndReconnectOverridesBindToEffectiveValues() throws IOException {
        IndexPriceProperties properties = bind(Map.of(
                "PRODUCT_LINE", "LINEAR_PERPETUAL",
                "PRICE_INDEX_POLL_DELAY_MS", "250",
                "PRICE_INDEX_MAX_SOURCE_AGE", "3s",
                "PRICE_INDEX_MIN_VALID_SOURCES", "4",
                "PRICE_INDEX_WS_IDLE_TIMEOUT", "9s",
                "PRICE_INDEX_WS_RECONNECT_INITIAL_DELAY", "500ms",
                "PRICE_INDEX_WS_RECONNECT_MAX_DELAY", "6s",
                "PRICE_INDEX_WS_HEALTH_CHECK_INTERVAL", "2s"));

        assertThat(properties.getCalculation().getPollDelayMs()).isEqualTo(250L);
        assertThat(properties.getCalculation().getMaxSourceAge()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.getCalculation().getMinValidSources()).isEqualTo(4);
        assertThat(properties.getWebSocket().getIdleTimeout()).isEqualTo(Duration.ofSeconds(9));
        assertThat(properties.getWebSocket().getReconnectInitialDelay()).isEqualTo(Duration.ofMillis(500));
        assertThat(properties.getWebSocket().getReconnectMaxDelay()).isEqualTo(Duration.ofSeconds(6));
        assertThat(properties.getWebSocket().getHealthCheckInterval()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void malformedSourceQuorumOverrideFailsBindingInsteadOfQuietlyUsingTheDefault() {
        assertThatThrownBy(() -> bind(Map.of(
                "PRODUCT_LINE", "LINEAR_PERPETUAL",
                "PRICE_INDEX_MIN_VALID_SOURCES", "not-a-number")))
                .isInstanceOf(BindException.class);
    }

    private IndexPriceProperties bind(Map<String, Object> overrides) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("integration",
                overrides));
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        sources.forEach(environment.getPropertySources()::addLast);

        return Binder.get(environment)
                .bind("surprising.price.index", Bindable.of(IndexPriceProperties.class))
                .orElseThrow(() -> new IllegalStateException("index price properties are required"));
    }
}
