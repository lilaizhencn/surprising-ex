package com.surprising.aeron.protocol;

import java.util.List;

public record CoreCommandResultView(
        List<CoreOrderStateView> orders,
        List<CoreExecutionView> executions) {

    public CoreCommandResultView {
        orders = orders == null ? List.of() : List.copyOf(orders);
        executions = executions == null ? List.of() : List.copyOf(executions);
    }
}
