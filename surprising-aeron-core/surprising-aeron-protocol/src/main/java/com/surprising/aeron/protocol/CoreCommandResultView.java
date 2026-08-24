package com.surprising.aeron.protocol;

import java.util.List;
import java.util.UUID;

public record CoreCommandResultView(
        long coreSequence,
        UUID commandId,
        long orderId,
        long instrumentVersion,
        long matcherSequence,
        long beforeBookHash,
        long afterBookHash,
        List<CoreOrderStateView> orders,
        List<CoreExecutionView> executions) {

    public CoreCommandResultView {
        if (coreSequence <= 0 || commandId == null || commandId.equals(new UUID(0, 0))
                || orderId <= 0 || instrumentVersion <= 0 || matcherSequence <= 0
                || beforeBookHash < 0 || afterBookHash < 0) {
            throw new IllegalArgumentException("invalid command result identity");
        }
        orders = orders == null ? List.of() : List.copyOf(orders);
        executions = executions == null ? List.of() : List.copyOf(executions);
    }
}
