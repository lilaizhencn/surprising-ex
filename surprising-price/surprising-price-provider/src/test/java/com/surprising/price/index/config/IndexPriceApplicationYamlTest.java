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
    void businessEnvironmentOverridesAreNotConfigurationSources() throws IOException {
        var properties = bind(Map.of("PRODUCT_LINE", "LINEAR_PERPETUAL",
                "PRICE_INDEX_POLL_DELAY_MS", "250", "PRICE_INDEX_MIN_VALID_SOURCES", "not-a-number",
                "surprising.price.index.calculation.poll-delay-ms", "50",
                "surprising.price.index.web-socket.enabled", "false"));
        assertThat(properties.getCalculation().getPollDelayMs()).isEqualTo(1000);
        assertThat(properties.getCalculation().getMinValidSources()).isEqualTo(3);
        assertThat(properties.getWebSocket().isEnabled()).isTrue();
    }

    private IndexPriceProperties bind(Map<String, Object> overrides) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("integration",
                overrides));
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        sources.forEach(environment.getPropertySources()::addLast);

        return new com.surprising.price.settings.PriceInfrastructureConfiguration().indexPriceProperties(environment);
    }
}
