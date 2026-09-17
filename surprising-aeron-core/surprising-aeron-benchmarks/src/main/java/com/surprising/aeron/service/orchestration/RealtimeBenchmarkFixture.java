package com.surprising.aeron.service.orchestration;
import com.surprising.aeron.service.orchestration.SurprisingClusteredService;
import com.surprising.aeron.client.RealtimeOutbox;

/** Benchmark setup only; never adds test attachment APIs to the deployed service. */
final class RealtimeBenchmarkFixture {
    static void attach(SurprisingClusteredService service, RealtimeOutbox outbox) {
        service.attachRealtime(outbox);
    }
}
