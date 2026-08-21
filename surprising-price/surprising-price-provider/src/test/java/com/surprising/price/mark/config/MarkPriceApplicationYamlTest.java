package com.surprising.price.mark.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import jakarta.validation.Validation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class MarkPriceApplicationYamlTest {

    @ParameterizedTest
    @ValueSource(longs = {1000L, 500L, 250L, 100L})
    void markMatrixOverrideBindsToEffectivePublishInterval(long publishIntervalMs) throws IOException {
        MarkPriceProperties properties = bind(publishIntervalMs);

        assertThat(properties.getCalculation().getPublishIntervalMs()).isEqualTo(publishIntervalMs);
        assertThat(Validation.buildDefaultValidatorFactory().getValidator().validate(properties)).isEmpty();
    }

    private MarkPriceProperties bind(long publishIntervalMs) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("matrix",
                Map.of("PRODUCT_LINE", "LINEAR_PERPETUAL",
                        "MARK_PUBLISH_INTERVAL_MS", Long.toString(publishIntervalMs))));
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        sources.forEach(environment.getPropertySources()::addLast);
        return Binder.get(environment).bind("surprising.price.mark", Bindable.of(MarkPriceProperties.class))
                .orElseThrow(() -> new IllegalStateException("mark price properties are required"));
    }
}
