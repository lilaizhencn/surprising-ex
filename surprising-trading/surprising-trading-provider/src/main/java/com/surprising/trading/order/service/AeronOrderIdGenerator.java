package com.surprising.trading.order.service;

import com.surprising.trading.order.config.TradingOrderProperties;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public final class AeronOrderIdGenerator {

    private final int nodeId;
    private final AtomicReference<Sequence> sequence = new AtomicReference<>(new Sequence(0, -1));

    public AeronOrderIdGenerator(TradingOrderProperties properties) {
        this.nodeId = properties.getAeron().getNodeId();
        if (nodeId < 0) {
            throw new IllegalStateException("ORDER_WAL_NODE_ID is required as the order gateway node id");
        }
    }

    public long next() {
        while (true) {
            Sequence previous = sequence.get();
            long timestamp = Math.max(System.currentTimeMillis(), previous.timestamp());
            int nextSequence = timestamp == previous.timestamp() ? previous.value() + 1 : 0;
            if (nextSequence > 1_023) {
                timestamp = Math.incrementExact(timestamp);
                nextSequence = 0;
            }
            long orderId = Math.addExact(Math.multiplyExact(timestamp, 1L << 22),
                    Math.addExact(((long) nodeId) << 12, ((long) nextSequence) << 2));
            if (orderId <= 0) {
                throw new IllegalStateException("order id overflow");
            }
            if (sequence.compareAndSet(previous, new Sequence(timestamp, nextSequence))) {
                return orderId;
            }
        }
    }

    private record Sequence(long timestamp, int value) {
    }
}
