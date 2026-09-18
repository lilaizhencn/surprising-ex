package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.service.cluster.ClusterTopology;
import com.surprising.aeron.service.cluster.AeronTradingClusterService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

class SurprisingCoreApplicationTest {

    @Test
    void bindsCoreConfigurationProperties() {
        org.springframework.boot.SpringApplication application = new org.springframework.boot.SpringApplication(
                SurprisingCoreApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setDefaultProperties(java.util.Map.of(
                "surprising.aeron.product-line", "LINEAR_PERPETUAL",
                "surprising.aeron.node-id", "1",
                "surprising.aeron.hostnames", "localhost,localhost,localhost",
                "surprising.aeron.data-dir", "target/test-aeron"));
        try (ConfigurableApplicationContext context = application.run()) {
            assertThat(context.getBean(ClusterTopology.class).productLine().name())
                    .isEqualTo("LINEAR_PERPETUAL");
            assertThat(context.getBean(AeronTradingClusterService.class))
                    .isSameAs(context.getBean(AeronTradingClusterService.class));
        }
    }

    @Test
    void refusesToStartWithoutProductLine() {
        org.springframework.boot.SpringApplication application = new org.springframework.boot.SpringApplication(
                SurprisingCoreApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setDefaultProperties(java.util.Map.of(
                "surprising.aeron.node-id", "1",
                "surprising.aeron.hostnames", "localhost,localhost,localhost",
                "surprising.aeron.data-dir", "target/test-aeron"));

        assertThatThrownBy(application::run)
                .hasStackTraceContaining("surprising.aeron.product-line is required");
    }
}
