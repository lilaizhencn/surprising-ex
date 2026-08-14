package com.surprising.risk.provider.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class RiskApplicationYamlTest {

    @Test
    void configuresOnlyAeronAndPostgresForRiskQueries() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        assertThat(sources).extracting(source -> source.getProperty("surprising.risk.product-line"))
                .contains("${PRODUCT_LINE:LINEAR_PERPETUAL}");
        assertThat(sources).extracting(source -> source.getProperty("surprising.risk.aeron.hostnames"))
                .contains("${AERON_HOSTNAMES:localhost,localhost,localhost}");
        assertThat(sources).extracting(source -> source.getProperty("surprising.risk.kafka.bootstrap-servers"))
                .containsOnlyNulls();
        assertThat(sources).extracting(source -> source.getProperty("surprising.risk.redis-state.key-prefix"))
                .containsOnlyNulls();
    }
}
