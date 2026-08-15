package com.surprising.aeron.service.state;

import java.util.List;
import java.util.Map;

public record CoreBookState(long nextPrioritySequence, Map<Long, Long> openOrders) {

    public CoreBookState {
        if (nextPrioritySequence <= 0 || openOrders == null) {
            throw new IllegalArgumentException("invalid book state");
        }
        Map<Long, Long> sorted = StateMapSupport.freezeSorted(openOrders);
        if (!StateMapSupport.isDelta(openOrders)) {
            sorted.forEach((orderId, prioritySequence) ->
                    validateOrder(nextPrioritySequence, orderId, prioritySequence));
        } else {
            for (Object key : StateMapSupport.changedKeys(openOrders)) {
                Long prioritySequence = sorted.get(key);
                if (prioritySequence != null) {
                    validateOrder(nextPrioritySequence, (Long) key, prioritySequence);
                }
            }
        }
        openOrders = sorted;
    }

    public static CoreBookState empty() {
        return new CoreBookState(1, Map.of());
    }

    private static void validateOrder(long nextPrioritySequence, long orderId, long prioritySequence) {
        if (orderId <= 0 || prioritySequence <= 0 || prioritySequence >= nextPrioritySequence) {
            throw new IllegalArgumentException("invalid book order index");
        }
    }

    public long prioritySequence(long orderId) {
        Long prioritySequence = openOrders.get(orderId);
        return prioritySequence == null ? 0 : prioritySequence;
    }

    public List<Long> priorityOrder() {
        return openOrders.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .toList();
    }

    public long stateHash() {
        long hash = CoreStateHash.start();
        hash = CoreStateHash.mix(hash, nextPrioritySequence);
        for (Map.Entry<Long, Long> entry : openOrders.entrySet()) {
            hash = CoreStateHash.mix(hash, entry.getKey());
            hash = CoreStateHash.mix(hash, entry.getValue());
        }
        return hash;
    }
}
