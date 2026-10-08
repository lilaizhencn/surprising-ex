package com.surprising.marketmaker.provider.config;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

class MarketMakerApplicationYamlTest {
    @Test
    void priceConsumerDefaultsToOneAndRetainsExplicitConcurrencyOverride() throws Exception {
        var env = new org.springframework.core.env.StandardEnvironment();
        env.getPropertySources().remove(org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        var overrides = new java.util.HashMap<String, Object>();
        overrides.put("PRODUCT_LINE", "LINEAR_PERPETUAL");
        env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("test", overrides));
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(env.getPropertySources()::addLast);
        var binder = org.springframework.boot.context.properties.bind.Binder.get(env);
        var type = org.springframework.boot.context.properties.bind.Bindable.of(com.surprising.price.consumer.MarkPriceConsumerProperties.class);
        assertThat(binder.bind("surprising.price.consumer", type).get().getConcurrency()).isEqualTo(1);
        overrides.put("PRICE_CONSUMER_CONCURRENCY", "4");
        assertThat(binder.bind("surprising.price.consumer", type).get().getConcurrency()).isEqualTo(4);
    }

    @Test
    void yamlContainsInfrastructureOnlyAndCannotEnableBusinessStrategies() throws Exception {
        var sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        for (var source : sources) {
            assertThat(source.getProperty("surprising.market-maker.strategies[0].enabled")).isNull();
            assertThat(source.getProperty("surprising.market-maker.quoting.order-levels")).isNull();
            assertThat(source.getProperty("surprising.market-maker.reference-market.sources[0].url")).isNull();
            assertThat(source.getProperty("surprising.market-maker.engine.enabled")).isNull();
        }
        assertThat(sources).extracting(source -> source.getProperty("surprising.market-maker.infrastructure.product-line"))
                .contains("${PRODUCT_LINE}");
        assertThat(MarketMakerBusinessSettings.initialDisabled().engine().isEnabled()).isFalse();
        assertThat(MarketMakerBusinessSettings.initialDisabled().referenceMarket().getSources()).isEmpty();
    }
}
