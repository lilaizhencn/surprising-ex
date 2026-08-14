package com.surprising.trading.trigger.service;

import com.surprising.trading.trigger.config.TriggerProperties;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public final class AeronTriggerOrderIdGenerator {
    private final int nodeId;
    private final AtomicReference<Sequence> sequence = new AtomicReference<>(new Sequence(0, -1));
    public AeronTriggerOrderIdGenerator(TriggerProperties properties) {
        this.nodeId = properties.getAeron().getNodeId();
        if (nodeId < 0 || nodeId > 1023) throw new IllegalStateException("invalid trigger Aeron node id");
    }
    public long next() {
        while (true) {
            Sequence previous = sequence.get(); long timestamp = Math.max(System.currentTimeMillis(), previous.timestamp());
            int value = timestamp == previous.timestamp() ? previous.value() + 1 : 0;
            if (value > 1023) { timestamp = Math.incrementExact(timestamp); value = 0; }
            long id = Math.addExact(Math.multiplyExact(timestamp, 1L << 22),
                    Math.addExact(((long) nodeId) << 12, ((long) value) << 2 | 1));
            if (sequence.compareAndSet(previous, new Sequence(timestamp, value))) return id;
        }
    }
    private record Sequence(long timestamp, int value) { }
}
