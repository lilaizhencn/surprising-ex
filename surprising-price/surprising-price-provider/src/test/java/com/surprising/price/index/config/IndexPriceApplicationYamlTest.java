package com.surprising.price.index.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class IndexPriceApplicationYamlTest {

    @Test
    void integrationConfigurationDisablesRestFallbackForPublicWebSocketSources() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("integration",
                Map.of("PRODUCT_LINE", "LINEAR_PERPETUAL")));
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        sources.forEach(environment.getPropertySources()::addLast);

        IndexPriceProperties properties = Binder.get(environment)
                .bind("surprising.price.index", Bindable.of(IndexPriceProperties.class))
                .orElseThrow(() -> new IllegalStateException("index price properties are required"));

        assertThat(properties.getWebSocket().isEnabled()).isTrue();
        assertThat(properties.getWebSocket().isRestFallbackEnabled()).isFalse();
    }
}
