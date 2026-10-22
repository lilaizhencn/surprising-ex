package com.surprising.aeron.bootstrap;

import io.aeron.cluster.ConsensusModule;
import io.aeron.cluster.service.ClusteredServiceContainer;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.YieldingIdleStrategy;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** 检查服务专用空闲策略的默认行为、实例隔离，以及它不会改动共识模块的全局策略。 */
class SurprisingCoreApplicationIdleStrategyTest {
    /** 空配置不覆盖 Aeron 默认值，无法识别的名称则应在启动时被拒绝。 */
    @Test
    void leavesAeronDefaultsIntactWhenUnconfigured() {
        assertThat(AeronCoreLifecycle.serviceIdleStrategySupplier((String) null)).isNull();
        assertThat(AeronCoreLifecycle.serviceIdleStrategySupplier(" ")).isNull();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AeronCoreLifecycle.serviceIdleStrategySupplier("invalid"));
    }

    /** Backoff 含有可变等待状态，因此每个容器都必须拿到不同实例。 */
    @Test
    void givesEachServiceItsOwnMutableBackoffState() {
        // 使用带空格和小写的配置，顺便确认名称解析会做清理和大小写转换。
        var supplier = AeronCoreLifecycle.serviceIdleStrategySupplier(" backoff ");
        // 保存第一次生成的实例，用它与第二次生成的实例比较身份。
        var first = supplier.get();
        assertThat(first).isInstanceOf(BackoffIdleStrategy.class).isNotSameAs(supplier.get());
    }

    /** 给交易服务单独选 YIELDING 后，共识模块和全局属性仍应保持原来的 BACKOFF。 */
    @Test
    void serviceOverrideDoesNotChangeConsensusOrGlobalConfiguration() {
        // 本项目专门控制交易服务空闲策略的属性名。
        String property = "surprising.aeron.service.idle-strategy";
        // Aeron 共识容器使用的全局属性名，不能被服务局部配置覆盖。
        String global = "aeron.cluster.idle.strategy";
        // 保存服务局部配置，测试结束后恢复，避免影响同 JVM 内的其他测试。
        String before = System.getProperty(property);
        // 全局配置也要独立保存和恢复，不能把本测试设置带到后续测试。
        String globalBefore = System.getProperty(global);
        try {
            System.setProperty(global, "org.agrona.concurrent.BackoffIdleStrategy");
            System.setProperty(property, "yielding");
            assertThat(AeronCoreLifecycle.serviceIdleStrategySupplier().get())
                    .isInstanceOf(YieldingIdleStrategy.class);
            assertThat(new ConsensusModule.Context().idleStrategySupplier(
                    ClusteredServiceContainer.Configuration.idleStrategySupplier(null)).idleStrategy())
                    .isInstanceOf(BackoffIdleStrategy.class);
            assertThat(System.getProperty(global)).isEqualTo("org.agrona.concurrent.BackoffIdleStrategy");
        } finally {
            if (before == null) System.clearProperty(property); else System.setProperty(property, before);
            if (globalBefore == null) System.clearProperty(global); else System.setProperty(global, globalBefore);
        }
    }
}
