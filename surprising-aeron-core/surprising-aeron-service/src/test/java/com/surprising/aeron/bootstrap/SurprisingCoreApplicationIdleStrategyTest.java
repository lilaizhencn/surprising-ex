package com.surprising.aeron.bootstrap;

import com.surprising.aeron.config.CoreTransportConfiguration.ServiceIdleStrategy;
import io.aeron.cluster.ConsensusModule;
import io.aeron.cluster.service.ClusteredServiceContainer;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.YieldingIdleStrategy;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 检查服务专用空闲策略的默认行为、实例隔离，以及它不会改动共识模块的全局策略。 */
class SurprisingCoreApplicationIdleStrategyTest {
    /** 空配置不覆盖 Aeron 默认值；名称校验在配置绑定时完成。 */
    @Test
    void leavesAeronDefaultsIntactWhenUnconfigured() {
        assertThat(AeronCoreLifecycle.serviceIdleStrategySupplier(null)).isNull();
    }

    /** Backoff 含有可变等待状态，因此每个容器都必须拿到不同实例。 */
    @Test
    void givesEachServiceItsOwnMutableBackoffState() {
        var supplier = AeronCoreLifecycle.serviceIdleStrategySupplier(ServiceIdleStrategy.BACKOFF);
        // 保存第一次生成的实例，用它与第二次生成的实例比较身份。
        var first = supplier.get();
        assertThat(first).isInstanceOf(BackoffIdleStrategy.class).isNotSameAs(supplier.get());
    }

    /** 给交易服务单独选 YIELDING 后，共识模块和全局属性仍应保持原来的 BACKOFF。 */
    @Test
    void serviceOverrideDoesNotChangeConsensusOrGlobalConfiguration() {
        // Aeron 共识容器使用的全局属性名，不能被服务局部配置覆盖。
        String global = "aeron.cluster.idle.strategy";
        // 全局配置也要独立保存和恢复，不能把本测试设置带到后续测试。
        String globalBefore = System.getProperty(global);
        try {
            System.setProperty(global, "org.agrona.concurrent.BackoffIdleStrategy");
            assertThat(AeronCoreLifecycle.serviceIdleStrategySupplier(ServiceIdleStrategy.YIELDING).get())
                    .isInstanceOf(YieldingIdleStrategy.class);
            assertThat(new ConsensusModule.Context().idleStrategySupplier(
                    ClusteredServiceContainer.Configuration.idleStrategySupplier(null)).idleStrategy())
                    .isInstanceOf(BackoffIdleStrategy.class);
            assertThat(System.getProperty(global)).isEqualTo("org.agrona.concurrent.BackoffIdleStrategy");
        } finally {
            if (globalBefore == null) System.clearProperty(global); else System.setProperty(global, globalBefore);
        }
    }
}
