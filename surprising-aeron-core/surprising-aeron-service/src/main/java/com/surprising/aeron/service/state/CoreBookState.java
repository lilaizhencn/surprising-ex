package com.surprising.aeron.service.state;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record CoreBookState(long nextPrioritySequence, Map<Long, CoreBookOrder> openOrders) {

    public CoreBookState {
        if (nextPrioritySequence <= 0 || openOrders == null) {
            throw new IllegalArgumentException("invalid book state");
        }
        Map<Long, CoreBookOrder> sorted = new TreeMap<>(openOrders);
        sorted.forEach((orderId, order) -> {
            if (orderId != order.orderId() || order.prioritySequence() >= nextPrioritySequence) {
                throw new IllegalArgumentException("invalid book order index");
            }
        });
        openOrders = Collections.unmodifiableMap(sorted);
    }

    public static CoreBookState empty() {
        return new CoreBookState(1, Map.of());
    }

    public List<CoreBookOrder> recoveryOrder() {
        return openOrders.values().stream()
                .sorted(Comparator.comparingLong(CoreBookOrder::prioritySequence))
                .toList();
    }

    public long stateHash(String symbol) {
        String normalized = symbol == null ? null : OrderReservation.normalizeSymbol(symbol);
        long hash = CoreStateHash.start();
        hash = CoreStateHash.mix(hash, nextPrioritySequence);
        for (CoreBookOrder order : recoveryOrder()) {
            if (normalized != null && !normalized.equals(order.symbol())) {
                continue;
            }
            hash = CoreStateHash.mix(hash, order.orderId());
            hash = CoreStateHash.mix(hash, order.userId());
            hash = CoreStateHash.mix(hash, order.symbol());
            hash = CoreStateHash.mix(hash, order.side().wireCode());
            hash = CoreStateHash.mix(hash, order.priceTicks());
            hash = CoreStateHash.mix(hash, order.remainingQuantitySteps());
            hash = CoreStateHash.mix(hash, order.prioritySequence());
        }
        return hash;
    }
}
