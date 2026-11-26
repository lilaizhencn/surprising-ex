package com.surprising.aeron.protocol.export;

import com.surprising.aeron.protocol.CoreAlgoOrderView;
import com.surprising.aeron.protocol.CoreTradeFeeObservation;
import com.surprising.aeron.protocol.RealtimeFrame;
import com.surprising.product.api.ProductLine;
import java.util.List;

/** Core 异步导出到 Kafka 的已提交投影增量批次（无状态 Gateway 消费后直接落库）。 */
public record CoreProjectionBatchEvent(
        ProductLine product,
        long exportSequence,
        long clusterPosition,
        long occurredAt,
        List<RealtimeFrame> orderChanges,
        List<CommittedCommandResult> commandResults,
        List<CoreAlgoOrderView> algoChanges,
        List<CommittedBalanceFlow> balanceFlows,
        List<LiquidationChange> liquidationChanges,
        List<CoreTradeFeeObservation> feeObservations,
        List<CommittedFundingPage> fundingPages,
        List<CommittedDeliveryPage> deliveryPages,
        List<PortfolioCutoffEvent> portfolioCutoffs,
        StatisticsEvent statistics
) {
    public CoreProjectionBatchEvent {
        orderChanges = orderChanges == null ? List.of() : List.copyOf(orderChanges);
        commandResults = commandResults == null ? List.of() : List.copyOf(commandResults);
        algoChanges = algoChanges == null ? List.of() : List.copyOf(algoChanges);
        balanceFlows = balanceFlows == null ? List.of() : List.copyOf(balanceFlows);
        liquidationChanges = liquidationChanges == null ? List.of() : List.copyOf(liquidationChanges);
        feeObservations = feeObservations == null ? List.of() : List.copyOf(feeObservations);
        fundingPages = fundingPages == null ? List.of() : List.copyOf(fundingPages);
        deliveryPages = deliveryPages == null ? List.of() : List.copyOf(deliveryPages);
        portfolioCutoffs = portfolioCutoffs == null ? List.of() : List.copyOf(portfolioCutoffs);
    }

    public static String projectionTopic(ProductLine product) {
        return "surprising-core-projection-" + product.name();
    }

    public record PortfolioCutoffEvent(
            CommittedPortfolioSnapshot snapshot,
            long clusterPosition,
            long exportSequence,
            long coveredFrom,
            boolean opening
    ) {}

    public record StatisticsEvent(
            CommittedTradingActivity activity,
            long clusterPosition,
            long exportSequence,
            long occurredAt
    ) {}
}
