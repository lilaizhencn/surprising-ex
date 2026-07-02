package com.surprising.marketmaker.provider.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class MarketMakerApplicationYamlTest {

    @Test
    void defaultStrategyIsManualOnlyAndUsesKnownInternalAccounts() throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("application", new ClassPathResource("application.yml"));

        assertThat(sources)
                .extracting(source -> source.getProperty("surprising.market-maker.engine.enabled"))
                .contains(false);
        assertThat(sources)
                .extracting(source -> source.getProperty("surprising.market-maker.strategies[0].enabled"))
                .contains(true);
        assertThat(sources)
                .extracting(source -> source.getProperty("surprising.market-maker.strategies[0].account-ids[0]"))
                .contains(900001);
        assertThat(sources)
                .extracting(source -> source.getProperty("surprising.market-maker.strategies[0].account-ids[1]"))
                .contains(900002);
    }
}
