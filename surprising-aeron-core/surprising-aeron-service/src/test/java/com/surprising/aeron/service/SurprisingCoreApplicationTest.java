package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.service.bootstrap.SurprisingCoreNode;
import com.surprising.aeron.service.cluster.ClusterTopology;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

class SurprisingCoreApplicationTest {

    @Test
    void createsSingletonInfrastructureBeansFromBootProperties() {
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
            assertThat(context.getBean(SurprisingCoreNode.class))
                    .isSameAs(context.getBean(SurprisingCoreNode.class));
        }
    }
}
