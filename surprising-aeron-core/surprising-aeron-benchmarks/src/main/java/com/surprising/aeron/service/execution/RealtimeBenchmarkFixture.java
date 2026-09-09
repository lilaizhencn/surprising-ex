package com.surprising.aeron.service.execution;

import com.surprising.aeron.client.RealtimeOutbox;
import java.lang.reflect.Field;

/** Benchmark setup only; never adds test attachment APIs to the deployed service. */
final class RealtimeBenchmarkFixture {
    static void attach(SurprisingClusteredService service, RealtimeOutbox outbox) {
        try {
            Field stateField = SurprisingClusteredService.class.getDeclaredField("state");
            stateField.setAccessible(true);
            TradingCoreRuntime state = (TradingCoreRuntime) stateField.get(service);
            Field outboxField = SurprisingClusteredService.class.getDeclaredField("realtimeOutbox");
            outboxField.setAccessible(true);
            outboxField.set(service, outbox);
            Field captureField = SurprisingClusteredService.class.getDeclaredField("realtimeCapture");
            captureField.setAccessible(true);
            captureField.set(service, state.attachRealtime(outbox));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("benchmark realtime setup failed", failure);
        }
    }
}
