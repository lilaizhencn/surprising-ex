package com.surprising.aeron.bootstrap;

import com.surprising.aeron.config.ClusterTopology;
import com.surprising.aeron.config.CoreConfiguration;
import com.surprising.aeron.config.LaneTopology;
import com.surprising.aeron.controller.AeronTradingClusterService;
import com.surprising.aeron.support.egress.ClusterServiceEgress;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

import static org.assertj.core.api.Assertions.assertThat;

/** 检查发布入口能够扫描迁移后的所有装配层；上下文创建期间不启动网络和交易线程。 */
class SurprisingCoreApplicationTest {
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void assemblesEachProductWithTheActualApplicationEntry(ProductLine product) {
        var application = new SpringApplication(SurprisingCoreApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setLogStartupInfo(false);
        try (var context = application.run(
                "--surprising.aeron.product-line=" + product.name(),
                "--surprising.aeron.hostnames=localhost",
                "--surprising.aeron.account-lanes=4",
                "--surprising.aeron.cpu-affinity.enabled=false")) {
            assertThat(context.getBeansOfType(CoreConfiguration.class)).hasSize(1);
            assertThat(context.getBeansOfType(AeronTradingClusterService.class)).hasSize(1);
            assertThat(context.getBeansOfType(AeronCoreLifecycle.class)).hasSize(1);
            assertThat(context.getBeansOfType(TradingOwnerLoop.class)).hasSize(1);
            assertThat(context.getBeansOfType(ClusterServiceEgress.class)).hasSize(1);
            assertThat(context.getBean(ClusterTopology.class).productLine()).isEqualTo(product);
            assertThat(context.getBean(LaneTopology.class).accountLaneCount()).isEqualTo(4);
        }
    }
}
