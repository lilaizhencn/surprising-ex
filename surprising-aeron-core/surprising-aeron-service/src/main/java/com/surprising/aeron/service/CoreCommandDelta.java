package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CoreExecutionView;
import com.surprising.aeron.protocol.CoreFundingPaymentView;
import com.surprising.aeron.protocol.CoreFundingProgressView;
import com.surprising.aeron.protocol.CoreSettlementProgressView;
import java.util.List;

public record CoreCommandDelta(
        List<Long> userIds,
        List<Long> orderIds,
        List<Long> liquidationIds,
        List<Long> triggerOrderIds,
        List<CoreExecutionView> executions,
        List<CoreFundingPaymentView> fundingPayments,
        CoreFundingProgressView fundingProgress,
        CoreSettlementProgressView settlementProgress) {

    public CoreCommandDelta {
        userIds = copyNullable(userIds);
        orderIds = copyNullable(orderIds);
        liquidationIds = copyNullable(liquidationIds);
        triggerOrderIds = copyNullable(triggerOrderIds);
        executions = List.copyOf(executions == null ? List.of() : executions);
        fundingPayments = List.copyOf(fundingPayments == null ? List.of() : fundingPayments);
    }

    public static CoreCommandDelta empty() {
        return new CoreCommandDelta(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null);
    }

    private static <T> List<T> copyNullable(List<T> values) {
        return values == null ? null : List.copyOf(values);
    }
}
