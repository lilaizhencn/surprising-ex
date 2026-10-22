package com.surprising.marketmaker.provider.model;

import java.time.Instant;
import java.util.List;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.marketmaker.api.model.MarketMakerStrategyStatus;
import com.surprising.marketmaker.provider.model.StrategyConfigOverride;
import com.surprising.marketmaker.provider.repository.MarketMakerRunEventRepository.MarketMakerRunEventRecord;

/** 管理指标、运行日志及策略配置的共享数据契约；不拥有做市运行状态。 */
public final class MarketMakerAdminModels {
    private MarketMakerAdminModels() { }

    public record MarketMakerLiquidityMetric(long slippagePpm, long targetNotionalUnits,
            long bidNotionalWithinBandUnits, long askNotionalWithinBandUnits,
            long bookSequence, Instant bookEventTime, boolean fresh) { }

    public record MarketMakerAdminMetricsResponse(Instant generatedAt,
                                                  String nodeId,
                                                  MarketMakerMetricsTotals totals,
                                                  List<MarketMakerStrategyMetric> rows,
                                                  List<MarketMakerAnomaly> anomalies,
                                                  List<MarketMakerMetricWarning> warnings) {
    }

    public record MarketMakerRunLogQueryResponse(Instant generatedAt,
                                                 List<MarketMakerRunEventRecord> events,
                                                 String nextCursor,
                                                 boolean hasMore,
                                                 String sort,
                                                 int limit) {

        public MarketMakerRunLogQueryResponse(Instant generatedAt, List<MarketMakerRunEventRecord> events) {
            this(generatedAt, events, null, false, null, events == null ? 0 : events.size());
        }

        public int count() {
            return events == null ? 0 : events.size();
        }
    }

    public record MarketMakerStrategyConfigResponse(MarketMakerStrategyConfig configured,
                                                    MarketMakerStrategyConfig effective,
                                                    StrategyConfigOverride override) {
    }

    public record MarketMakerStrategyConfig(String strategyId,
                                            ProductLine productLine,
                                            boolean enabled,
                                            List<Long> accountIds,
                                            List<String> instrumentIds,
                                            long baseQuantitySteps,
                                            MarginMode marginMode,
                                            long spreadTicks,
                                            long levelSpacingTicks,
                                            Long maxInventorySteps,
                                            Long maxInventorySkewPpm,
                                            Integer orderLevels) {
    }

    public record MarketMakerStrategyConfigUpdateRequest(Boolean enabled,
                                                         Long baseQuantitySteps,
                                                         String marginMode,
                                                         Long spreadTicks,
                                                         Long levelSpacingTicks,
                                                         Long maxInventorySteps,
                                                         Long maxInventorySkewPpm,
                                                         Integer orderLevels,
                                                         String reason) {
    }

    public record MarketMakerMetricsTotals(long strategyCount,
                                           long enabledStrategies,
                                           long runningStrategies,
                                           long degradedStrategies,
                                           long pausedStrategies,
                                           long disabledStrategies,
                                           long metricRows,
                                           long submittedOrders,
                                           long canceledOrders,
                                           long rejectedOrders,
                                           long skippedCycles,
                                           long anomalyCount,
                                           long criticalAnomalies,
                                           long warnAnomalies) {
    }

    public record MarketMakerStrategyMetric(String strategyId,
                                            ProductLine productLine,
                                            String instrumentId,
                                            long accountId,
                                            MarketMakerStrategyStatus strategyStatus,
                                            String qualityStatus,
                                            boolean configuredEnabled,
                                            boolean runtimePaused,
                                            long cycleSequence,
                                            long submittedOrders,
                                            long canceledOrders,
                                            long rejectedOrders,
                                            long skippedCycles,
                                            long signedInventorySteps,
                                            long absInventorySteps,
                                            long maxInventorySteps,
                                            long inventoryUsagePpm,
                                            long realizedPnlUnits,
                                            Instant positionUpdatedAt,
                                            long ownedOpenOrders,
                                            long ownedBidOrders,
                                            long ownedAskOrders,
                                            long desiredQuoteCount,
                                            long desiredBidQuotes,
                                            long desiredAskQuotes,
                                            long matchedDesiredQuotes,
                                            long missingDesiredQuotes,
                                            long staleOwnedOrders,
                                            long offTargetOwnedOrders,
                                            long bestBidTicks,
                                            long bestAskTicks,
                                            long spreadTicks,
                                            long spreadPpm,
                                            long markPriceTicks,
                                            long quoteCoveragePpm,
                                            String lastError,
                                            Instant lastCycleTime,
                                            String error,
                                            MarketMakerLiquidityMetric liquidity) {
    }

    public record MarketMakerAnomaly(String severity,
                                     String type,
                                     String strategyId,
                                     ProductLine productLine,
                                     String instrumentId,
                                     long accountId,
                                     long metricValue,
                                     long threshold,
                                     String summary) {
    }

    public record MarketMakerMetricWarning(String strategyId,
                                           ProductLine productLine,
                                           String instrumentId,
                                           long accountId,
                                           String message) {
    }

}
