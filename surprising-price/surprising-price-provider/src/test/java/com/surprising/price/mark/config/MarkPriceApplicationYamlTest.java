package com.surprising.price.mark.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import jakarta.validation.Validation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class MarkPriceApplicationYamlTest {

    @Test
    void singleNodeClusterAddressBindsToMarkPriceCorePublisher() throws IOException {
        StandardEnvironment environment = environment(Map.of(
                "PRODUCT_LINE", "LINEAR_PERPETUAL",
                "AERON_CLUSTER_HOSTNAMES", "127.0.0.1",
                "AERON_EGRESS_HOSTNAME", "127.0.0.1"));
        MarkPriceProperties properties = bind(environment);

        assertThat(properties.getAeron().getHostnames()).containsExactly("127.0.0.1");
        assertThat(properties.getAeron().getEgressHostname()).isEqualTo("127.0.0.1");
    }

    @ParameterizedTest
    @ValueSource(longs = {1000L, 500L, 250L, 100L})
    void environmentCannotOverrideBusinessPublishInterval(long publishIntervalMs) throws IOException {
        MarkPriceProperties properties = bind(publishIntervalMs);

        assertThat(properties.getCalculation().getPublishIntervalMs()).isEqualTo(1000L);
        assertThat(Validation.buildDefaultValidatorFactory().getValidator().validate(properties)).isEmpty();
    }

    private MarkPriceProperties bind(long publishIntervalMs) throws IOException {
        return bind(environment(Map.of("PRODUCT_LINE", "LINEAR_PERPETUAL",
                "MARK_PUBLISH_INTERVAL_MS", Long.toString(publishIntervalMs))));
    }

    private StandardEnvironment environment(Map<String, Object> overrides) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("matrix", overrides));
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        sources.forEach(environment.getPropertySources()::addLast);
        return environment;
    }

    private MarkPriceProperties bind(StandardEnvironment environment) {
        return new com.surprising.price.settings.PriceInfrastructureConfiguration().markPriceProperties(environment);
    }
}
