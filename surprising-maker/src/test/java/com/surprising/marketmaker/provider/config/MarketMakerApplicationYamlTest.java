package com.surprising.marketmaker.provider.config;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

class MarketMakerApplicationYamlTest {
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
