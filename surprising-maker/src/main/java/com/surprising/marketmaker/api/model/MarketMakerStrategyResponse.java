package com.surprising.marketmaker.api.model;

import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.List;

public record MarketMakerStrategyResponse(
        String strategyId,
        ProductLine productLine,
        List<String> symbols,
        List<Long> accountIds,
        MarketMakerStrategyStatus status,
        boolean configuredEnabled,
        boolean runtimePaused,
        long cycleSequence,
        long submittedOrders,
        long canceledOrders,
        long rejectedOrders,
        long skippedCycles,
        String lastTraceId,
        String lastError,
        Instant lastCycleTime) {

    public MarketMakerStrategyResponse(String strategyId,
                                       List<String> symbols,
                                       List<Long> accountIds,
                                       MarketMakerStrategyStatus status,
                                       boolean configuredEnabled,
                                       boolean runtimePaused,
                                       long cycleSequence,
                                       long submittedOrders,
                                       long canceledOrders,
                                       long rejectedOrders,
                                       long skippedCycles,
                                       String lastTraceId,
                                       String lastError,
                                       Instant lastCycleTime) {
        this(strategyId, ProductLine.LINEAR_PERPETUAL, symbols, accountIds, status, configuredEnabled,
                runtimePaused, cycleSequence, submittedOrders, canceledOrders, rejectedOrders, skippedCycles,
                lastTraceId, lastError, lastCycleTime);
    }
}
