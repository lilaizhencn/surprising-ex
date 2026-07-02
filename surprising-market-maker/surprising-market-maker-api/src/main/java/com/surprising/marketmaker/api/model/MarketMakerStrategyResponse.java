package com.surprising.marketmaker.api.model;

import java.time.Instant;
import java.util.List;

public record MarketMakerStrategyResponse(
        String strategyId,
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
}
