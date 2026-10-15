package com.surprising.aeron.client;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ClientSourceOrderingBenchmarkTest {
    @Test
    void fourProducersCompleteThroughOwnedEncodedSessionsWithWindow256() throws Exception {
        var shared = new ClientSourceOrderingBenchmark.Shared();
        shared.setup();
        try (var threads = Executors.newFixedThreadPool(4)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int producer = 0; producer < 4; producer++) {
                tasks.add(threads.submit(() -> {
                    var benchmark = new ClientSourceOrderingBenchmark();
                    var worker = new ClientSourceOrderingBenchmark.Worker();
                    for (int round = 0; round < 300; round++) benchmark.dispatch(shared, worker);
                }));
            }
            for (var task : tasks) task.get(5, TimeUnit.SECONDS);
            assertThat(shared.offered).hasValue(256 * 300);
            assertThat(shared.terminal).hasValue(256 * 300);
        } finally {
            shared.close();
        }
    }
}
