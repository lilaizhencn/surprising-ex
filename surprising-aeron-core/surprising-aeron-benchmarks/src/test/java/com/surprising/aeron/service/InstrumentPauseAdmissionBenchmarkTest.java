package com.surprising.aeron.service;

import com.surprising.product.api.ProductLine;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** One finite sample per product line; not a throughput or latency measurement. */
class InstrumentPauseAdmissionBenchmarkTest {
    @ParameterizedTest @EnumSource(ProductLine.class)
    void benchmarkExercisesRealPauseAndSnapshotRecovery(ProductLine line) {
        var fixture=new InstrumentPauseAdmissionBenchmark.Fixture(); fixture.productLine=line;
        fixture.initialize(); fixture.restore();
        try { new InstrumentPauseAdmissionBenchmark().pausedOrder(fixture); }
        finally { fixture.verify(); }
    }
}
