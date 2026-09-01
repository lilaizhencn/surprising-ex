package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CoreExecutionView;
import com.surprising.aeron.protocol.CoreFundingPaymentView;
import com.surprising.aeron.protocol.CoreFundingProgressView;
import com.surprising.aeron.protocol.CoreLiquidationView;
import com.surprising.aeron.protocol.CoreOrderStateView;
import com.surprising.aeron.protocol.CoreSettlementProgressView;
import com.surprising.aeron.protocol.CoreTriggerOrderStateView;
import com.surprising.aeron.protocol.CoreTreasuryAssetView;
import com.surprising.aeron.protocol.CoreUserStateView;
import java.util.List;

public record CoreCommandDelta(
        List<Long> userIds,
        List<Long> orderIds,
        List<Long> liquidationIds,
        List<Long> triggerOrderIds,
        List<CoreExecutionView> executions,
        List<CoreFundingPaymentView> fundingPayments,
        CoreFundingProgressView fundingProgress,
        CoreSettlementProgressView settlementProgress,
        List<CoreUserStateView> changedUsers,
        List<CoreOrderStateView> changedOrders,
        List<CoreLiquidationView> changedLiquidations,
        List<CoreTreasuryAssetView> changedTreasuryAssets,
        List<CoreTriggerOrderStateView> changedTriggerOrders) {

    public CoreCommandDelta {
        userIds = copyNullableLongs(userIds);
        orderIds = copyNullableLongs(orderIds);
        liquidationIds = copyNullableLongs(liquidationIds);
        triggerOrderIds = copyNullableLongs(triggerOrderIds);
        executions = List.copyOf(executions == null ? List.of() : executions);
        fundingPayments = List.copyOf(fundingPayments == null ? List.of() : fundingPayments);
        changedUsers = List.copyOf(changedUsers == null ? List.of() : changedUsers);
        changedOrders = List.copyOf(changedOrders == null ? List.of() : changedOrders);
        changedLiquidations = List.copyOf(changedLiquidations == null ? List.of() : changedLiquidations);
        changedTreasuryAssets = List.copyOf(changedTreasuryAssets == null ? List.of() : changedTreasuryAssets);
        changedTriggerOrders = List.copyOf(changedTriggerOrders == null ? List.of() : changedTriggerOrders);
    }

    public static CoreCommandDelta empty() {
        return new CoreCommandDelta(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null,
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static <T> List<T> copyNullable(List<T> values) {
        return values == null ? null : List.copyOf(values);
    }

    private static List<Long> copyNullableLongs(List<Long> values) {
        return values == null ? null : ImmutableLongArrayList.preservePrimitive(values);
    }
}
