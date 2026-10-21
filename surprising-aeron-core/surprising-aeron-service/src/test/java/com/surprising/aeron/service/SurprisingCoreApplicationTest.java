package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.service.cluster.ClusterTopology;
import com.surprising.aeron.service.cluster.AeronTradingClusterService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

/** 验证非 Web 入口能正确绑定节点配置，并在缺少产品线时拒绝装配交易集群。 */
class SurprisingCoreApplicationTest {

    /** 启动最小 Spring 上下文，检查产品线绑定和交易服务的单例装配。 */
    @Test
    void bindsCoreConfigurationProperties() {
        // 使用真实入口装配组件，但不调用启动网络资源并等待退出的 main 方法。
        org.springframework.boot.SpringApplication application = new org.springframework.boot.SpringApplication(
                SurprisingCoreApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setDefaultProperties(java.util.Map.of(
                "surprising.aeron.product-line", "LINEAR_PERPETUAL",
                "surprising.aeron.node-id", "1",
                "surprising.aeron.hostnames", "localhost,localhost,localhost",
                "surprising.aeron.data-dir", "target/test-aeron"));
        try (/* 测试使用的 Spring 上下文，作用域结束时自动关闭组件。 */
                ConfigurableApplicationContext context = application.run()) {
            assertThat(context.getBean(ClusterTopology.class).productLine().name())
                    .isEqualTo("LINEAR_PERPETUAL");
            assertThat(context.getBean(AeronTradingClusterService.class))
                    .isSameAs(context.getBean(AeronTradingClusterService.class));
        }
    }

    /** 没有指定产品线时必须明确报错，防止把节点意外启动到默认交易产品。 */
    @Test
    void refusesToStartWithoutProductLine() {
        // 故意不提供产品线配置，只提供其余节点参数来定位必填项检查。
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
