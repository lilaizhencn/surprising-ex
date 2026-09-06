package com.surprising.realtime.provider;

import static org.assertj.core.api.Assertions.*;

import com.surprising.product.api.ProductLine;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class RealtimeRouterPropertiesTest {
    @Test
    void bindsManualMdcDestinationsWithDefaultStreams() {
        new ApplicationContextRunner()
                .withUserConfiguration(Config.class)
                .withPropertyValues(
                        "surprising.realtime.router.channel=aeron:ipc",
                        "surprising.realtime.router.control-channels.SPOT=aeron:udp?control-mode=manual",
                        "surprising.realtime.router.control-destinations.SPOT[0]=aeron:udp?endpoint=127.0.0.1:25001",
                        "surprising.realtime.router.control-destinations.SPOT[1]=aeron:udp?endpoint=127.0.0.1:25002")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            var properties = context.getBean(RealtimeRouterProperties.class);
                            assertThat(properties.stream()).isEqualTo(2101);
                            assertThat(properties.nodeStream()).isEqualTo(2103);
                            assertThat(properties.controlDestinations().get(ProductLine.SPOT))
                                    .hasSize(2);
                        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RealtimeRouterProperties.class)
    static class Config {}
}
