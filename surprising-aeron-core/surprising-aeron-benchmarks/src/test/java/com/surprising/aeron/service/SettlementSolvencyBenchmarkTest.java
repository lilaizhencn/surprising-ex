package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.assertThat;

class SettlementSolvencyBenchmarkTest {
    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = {"LINEAR_DELIVERY", "INVERSE_DELIVERY", "OPTION"})
    void preservesDebtAcrossRestartAndPaysExactlyOnceAfterRefill(ProductLine line) {
        for (var mode : CoreMarginMode.values()) {
            var benchmark = new SettlementSolvencyBenchmark();
            benchmark.productLine = line;
            benchmark.marginMode = mode;
            benchmark.maxInFlight = 256;
            benchmark.prepare();
            benchmark.restore();
            try { assertThat(benchmark.run(true)).isEqualTo(19); }
            catch (Throwable failure) {
                try { benchmark.verify(); } catch (Throwable verify) { if (failure != verify) failure.addSuppressed(verify); }
                throw failure;
            }
            benchmark.verify();
        }
    }
}
