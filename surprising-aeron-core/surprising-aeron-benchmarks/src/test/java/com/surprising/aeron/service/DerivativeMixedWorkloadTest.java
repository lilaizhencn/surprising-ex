package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;

class DerivativeMixedWorkloadTest {

    @Test
    void everyDerivativeLineCompletesTheSameMixedOrderLifecycleAndRecoversExactly() {
        for (ProductLine productLine : ProductLine.values()) {
            if (!productLine.isDerivative()) continue;
            var template = DerivativeMixedWorkload.template(productLine, 4, 32, 2);
            assertThatCode(() -> {
                try (var scenario = DerivativeMixedWorkload.scenario(template, 2, 4)) {
                    assertThat(scenario.run()).isNotZero();
                    assertThat(scenario.acceptedOperations()).isEqualTo(scenario.terminalOperations());
                    assertThat(scenario.acceptedCoreMessages()).isEqualTo(scenario.terminalCoreMessages());
                    assertThat(scenario.maxBacklog()).isGreaterThan(1);
                    scenario.verify();
                }
            }).as(productLine.name()).doesNotThrowAnyException();
        }
    }
}
