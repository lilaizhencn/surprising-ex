package com.surprising.websocket.provider.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class WebSocketApplicationYamlTest {

    @Test
    void defaultConsumerGroupUsesStableNodeNameBeforeRandomFallback() throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("application", new ClassPathResource("application.yml"));

        assertThat(sources)
                .extracting(source -> source.getProperty("surprising.websocket.kafka.group-id"))
                .contains("surprising-websocket-${HOSTNAME:${random.uuid}}");
        assertThat(sources)
                .extracting(source -> source.getProperty("surprising.websocket.kafka.max-poll-records"))
                .contains(1000);
        assertThat(sources)
                .extracting(source -> source.getProperty("surprising.websocket.kafka.product-line"))
                .contains("LINEAR_PERPETUAL");
        assertThat(sources)
                .extracting(source -> source.getProperty("surprising.websocket.kafka.product-topics-enabled"))
                .contains(false);
        assertThat(sources)
                .extracting(source -> source.getProperty("surprising.websocket.kafka.account-risk-events-topic"))
                .contains("surprising.risk.account.events.v1");
        assertThat(sources)
                .extracting(source -> source.getProperty("surprising.websocket.kafka.position-risk-events-topic"))
                .contains("surprising.risk.position.events.v1");
    }
}
