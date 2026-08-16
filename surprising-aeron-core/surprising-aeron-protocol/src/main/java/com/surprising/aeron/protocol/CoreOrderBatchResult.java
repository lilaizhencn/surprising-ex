package com.surprising.aeron.protocol;

import java.util.List;

public record CoreOrderBatchResult(List<CoreOrderBatchResult.Item> items) {

    public static final int MAX_ITEMS = 50;

    public CoreOrderBatchResult {
        if (items == null || items.isEmpty() || items.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("invalid order batch result");
        }
        items = List.copyOf(items);
        for (int index = 0; index < items.size(); index++) {
            Item item = items.get(index);
            if (item == null || item.index() != index) {
                throw new IllegalArgumentException("order batch result indexes must be contiguous");
            }
        }
    }

    public List<Item> results() {
        return items;
    }

    public List<Item> outcomes() {
        return items;
    }

    public record Item(
            int index,
            long orderId,
            long originalOrderId,
            long replacementOrderId,
            ResponseStatus status,
            CoreResultCode resultCode,
            CoreOrderStateView order,
            List<CoreExecutionView> executions) {

        public Item {
            if (index < 0 || orderId <= 0 || originalOrderId < 0 || replacementOrderId < 0
                    || status == null || resultCode == null) {
                throw new IllegalArgumentException("invalid order batch result item");
            }
            executions = executions == null ? List.of() : List.copyOf(executions);
        }

        public int itemIndex() {
            return index;
        }

        public long itemId() {
            return orderId;
        }
    }
}
