package com.surprising.aeron.service;

import com.surprising.product.api.ProductLine;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.assertThat;

class LifecyclePaginationBenchmarkTest {
    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = {"LINEAR_DELIVERY", "INVERSE_DELIVERY", "OPTION"})
    void resumesEveryOrderAndUserPageFromSnapshot(ProductLine line) {
        var benchmark = new LifecyclePaginationBenchmark();
        benchmark.productLine = line;
        benchmark.maxInFlight = 256;
        benchmark.prepare();
        benchmark.restore();
        try { assertThat(benchmark.settlePages(true)).isEqualTo(32); }
        catch (Throwable failure) {
            try { benchmark.verify(); } catch (Throwable verify) { failure.addSuppressed(verify); }
            throw failure;
        }
        benchmark.verify();
    }
}
