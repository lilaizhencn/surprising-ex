package com.surprising.marketmaker.provider.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MakerReconciliationAllocationBenchmarkTest {
    @Test void quoteSlotsAndCachedOrdersSurviveConfirmedBatchReuse() throws Throwable {
        var benchmark = new MakerReconciliationAllocationBenchmark();
        benchmark.levels = 120;
        benchmark.setup();
        assertThat(benchmark.liquidityReplacements()).isEqualTo(240);
        for (int i = 0; i < 256; i++) benchmark.confirmedBatchSnapshot();
        Object cached = benchmark.cache.get("LINEAR_PERPETUAL:7:604");
        var accessor = cached.getClass().getDeclaredMethod("orders"); accessor.setAccessible(true);
        assertThat((java.util.List<?>) accessor.invoke(cached)).hasSize(240).doesNotHaveDuplicates();
    }
}
