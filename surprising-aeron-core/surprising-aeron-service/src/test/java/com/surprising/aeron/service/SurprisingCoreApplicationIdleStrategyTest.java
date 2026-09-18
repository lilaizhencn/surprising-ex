package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.aeron.cluster.ConsensusModule;
import io.aeron.cluster.service.ClusteredServiceContainer;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.YieldingIdleStrategy;
import org.junit.jupiter.api.Test;

class SurprisingCoreApplicationIdleStrategyTest {
    @Test
    void leavesAeronDefaultsIntactWhenUnconfigured() {
        assertThat(SurprisingCoreApplication.serviceIdleStrategySupplier((String) null)).isNull();
        assertThat(SurprisingCoreApplication.serviceIdleStrategySupplier(" ")).isNull();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SurprisingCoreApplication.serviceIdleStrategySupplier("invalid"));
    }

    @Test
    void givesEachServiceItsOwnMutableBackoffState() {
        var supplier = SurprisingCoreApplication.serviceIdleStrategySupplier(" backoff ");
        var first = supplier.get();
        assertThat(first).isInstanceOf(BackoffIdleStrategy.class).isNotSameAs(supplier.get());
    }

    @Test
    void serviceOverrideDoesNotChangeConsensusOrGlobalConfiguration() {
        String property = "surprising.aeron.service.idle-strategy";
        String global = "aeron.cluster.idle.strategy";
        String before = System.getProperty(property);
        String globalBefore = System.getProperty(global);
        try {
            System.setProperty(global, "org.agrona.concurrent.BackoffIdleStrategy");
            System.setProperty(property, "yielding");
            assertThat(SurprisingCoreApplication.serviceIdleStrategySupplier().get())
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
