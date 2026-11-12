package com.surprising.aeron.service.core;

import com.surprising.product.api.ProductLine;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** One finite sample per product line; not a throughput or latency measurement. */
class InstrumentPauseOrderCheckBenchmarkTest {
    @ParameterizedTest @EnumSource(ProductLine.class)
    void benchmarkExercisesRealPauseAndSnapshotRecovery(ProductLine line) {
        var fixture=new InstrumentPauseOrderCheckBenchmark.Fixture(); fixture.productLine=line;
        fixture.initialize(); fixture.restore();
        try { new InstrumentPauseOrderCheckBenchmark().pausedOrder(fixture); }
        finally { fixture.verify(); }
    }
}
