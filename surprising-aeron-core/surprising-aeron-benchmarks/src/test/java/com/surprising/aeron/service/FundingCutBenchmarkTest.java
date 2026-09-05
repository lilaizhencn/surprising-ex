package com.surprising.aeron.service;

import com.surprising.product.api.ProductLine;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.assertThat;

class FundingCutBenchmarkTest {
    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = {"LINEAR_PERPETUAL", "INVERSE_PERPETUAL"})
    void fixesFundingMarkAcrossPagesAndSnapshotRecovery(ProductLine line) {
        var benchmark = new FundingCutBenchmark();
        benchmark.productLine = line;
        benchmark.maxInFlight = 256;
        benchmark.prepare();
        benchmark.restore();
        try { assertThat(benchmark.fundingWithRecovery()).isEqualTo(18); }
        catch (Throwable failure) {
            try { benchmark.verify(); } catch (Throwable verify) { if (failure != verify) failure.addSuppressed(verify); }
            throw failure;
        }
        benchmark.verify();
    }
}
