package com.surprising.marketmaker.provider.service;

import com.surprising.account.api.model.PositionResponse;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.marketmaker.api.model.MarketMakerStrategyStatus;
import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.marketmaker.provider.model.MarketMakerAdminModels.MarketMakerAdminMetricsResponse;
import com.surprising.marketmaker.provider.model.MarketMakerAdminModels.MarketMakerAnomaly;
import com.surprising.marketmaker.provider.model.MarketMakerAdminModels.MarketMakerLiquidityMetric;
import com.surprising.marketmaker.provider.model.MarketMakerAdminModels.MarketMakerMetricWarning;
import com.surprising.marketmaker.provider.model.MarketMakerAdminModels.MarketMakerStrategyMetric;
import com.surprising.marketmaker.provider.model.QuotePlan;
import com.surprising.marketmaker.provider.model.ReferenceOrderBookSnapshot;
import com.surprising.marketmaker.provider.model.StrategyRuntimeState;
import com.surprising.price.api.model.MarkPriceResponse;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.OrderBookSnapshotResponse;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderSide;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 做市后台指标汇总、盘口健康检查与异常诊断协作器。 */
final class MarketMakerMetricsAssembler {
    private final MarketMakerService service;
    private final MarketMakerProperties properties;
    private final QuotePlanner quotePlanner;
    private final ReferenceMarketProvider referenceMarketProvider;
    private final MarketMakerMarketFeed marketFeed;
    private final MarketMakerTradeExecutor tradeExecutor;
    private final String nodeId;

    MarketMakerMetricsAssembler(MarketMakerService service,
                                MarketMakerProperties properties,
                                QuotePlanner quotePlanner,
                                ReferenceMarketProvider referenceMarketProvider,
                                MarketMakerMarketFeed marketFeed,
                                MarketMakerTradeExecutor tradeExecutor,
                                String nodeId) {
        this.service = service;
        this.properties = properties;
        this.quotePlanner = quotePlanner;
        this.referenceMarketProvider = referenceMarketProvider;
        this.marketFeed = marketFeed;
        this.tradeExecutor = tradeExecutor;
        this.nodeId = nodeId;
    }

    MarketMakerAdminMetricsResponse adminMetrics(int limit, ProductLine productLine) {
        int boundedLimit = Math.max(1, Math.min(limit, 500));
        Instant now = Instant.now();
        List<MarketMakerStrategyMetric> rows = new ArrayList<>();
        List<MarketMakerAnomaly> anomalies = new ArrayList<>();
        List<MarketMakerMetricWarning> warnings = new ArrayList<>();
        for (MarketMakerProperties.Strategy strategy : service.strategiesSnapshot(productLine)) {
            for (String configuredSymbol : strategy.getInstrumentIds()) {
                if (rows.size() >= boundedLimit) {
                    break;
                }
                String instrumentId = MarketMakerParameterSupport.normalizeSymbol(configuredSymbol);
                for (long accountId : strategy.getAccountIds()) {
                    if (rows.size() >= boundedLimit) {
                        break;
                    }
                    rows.add(strategyMetric(strategy, instrumentId, accountId, now, anomalies, warnings));
                }
            }
        }
        return new MarketMakerAdminMetricsResponse(now, nodeId,
                MakerMetricsSupport.totals(service.strategies(productLine).strategies(), rows, anomalies),
                rows, anomalies, warnings);
    }

    private MarketMakerStrategyMetric strategyMetric(MarketMakerProperties.Strategy strategy,
                                                     String instrumentId, long accountId, Instant now,
                                                     List<MarketMakerAnomaly> anomalies,
                                                     List<MarketMakerMetricWarning> warnings) {
        StrategyRuntimeState state = service.state(strategy);
        MarketMakerStrategyStatus strategyStatus = service.status(strategy, state);
        String strategyId = strategy.getStrategyId();
        ProductLine productLine = strategy.getProductLine();
        String accountPrefix = service.accountPrefix(strategy, instrumentId, accountId);
        List<MarketMakerAnomaly> rowAnomalies = new ArrayList<>();
        if (!strategy.isEnabled()) {
            rowAnomalies.add(MakerMetricsSupport.anomaly("INFO", "STRATEGY_DISABLED", strategyId, productLine,
                    instrumentId, accountId, 0, 1, "strategy is disabled by configuration"));
        }
        if (state.paused()) {
            rowAnomalies.add(MakerMetricsSupport.anomaly("INFO", "STRATEGY_PAUSED", strategyId, productLine,
                    instrumentId, accountId, 1, 0, "strategy is paused at runtime"));
        }
        if (state.lastError() != null) {
            rowAnomalies.add(MakerMetricsSupport.anomaly("CRITICAL", "LAST_CYCLE_FAILED", strategyId, productLine,
                    instrumentId, accountId, 1, 0, state.lastError()));
        }
        try {
            List<OrderResponse> openOrders = marketFeed.openOrders(productLine, accountId, instrumentId, now);
            List<OrderResponse> ownedLive = openOrders.stream()
                    .filter(order -> service.ownsOrder(accountPrefix, order))
                    .filter(MakerQuoteMatchingSupport::isLive)
                    .toList();
            InstrumentResponse instrument = marketFeed.currentInstrument(productLine, instrumentId);
            PositionResponse position = marketFeed.currentPosition(strategy, accountId, instrumentId, instrument);
            OrderBookSnapshotResponse orderBook = marketFeed.fetchOrderBook(productLine, instrumentId,
                    properties.getQuoting().getOrderBookDepth());
            MarkPriceResponse markPrice = marketFeed.latestMarkPrice(productLine, instrumentId, instrument);
            ReferenceOrderBookSnapshot referenceOrderBook = referenceMarketProvider.snapshot(instrumentId, productLine, instrument);
            QuotePlan plan = !service.isTradableForProduct(instrument, productLine)
                    || properties.getReferenceMarket().isEnabled() && referenceOrderBook == null
                    ? new QuotePlan(0L, position.signedQuantitySteps(), List.of(), 0)
                    : quotePlanner.plan(strategy, properties.getQuoting(), properties.getRisk(), instrument,
                    orderBook, markPrice, position.signedQuantitySteps(),
                    tradeExecutor.currentVolatility(strategy, instrumentId), referenceOrderBook, 0L);
            long staleOwned = properties.getReferenceMarket().isEnabled() && referenceOrderBook == null
                    ? ownedLive.size() : 0;
            int desiredQuotes = plan.quotes().size();
            var quoting = properties.getQuoting();
            long matchedDesired = plan.quotes().stream()
                    .filter(quote -> MakerQuoteMatchingSupport.hasLiveQuote(ownedLive, quote, accountPrefix, quoting))
                    .count();
            long offTargetOwned = ownedLive.stream()
                    .filter(order -> plan.quotes().stream()
                            .noneMatch(quote -> MakerQuoteMatchingSupport.matchesQuote(order, quote, accountPrefix, quoting)))
                    .count();
            long missingDesired = Math.max(0, desiredQuotes - matchedDesired);
            long maxInventory = effectiveMaxInventorySteps(strategy);
            long absInventory = Math.abs(position.signedQuantitySteps());
            long inventoryUsagePpm = maxInventory <= 0 ? 0 : Math.min(10_000_000L,
                    Math.round(absInventory * 1_000_000.0d / maxInventory));
            long bestBid = MakerSimulatedTradeSupport.bestBid(orderBook);
            long bestAsk = MakerSimulatedTradeSupport.bestAsk(orderBook);
            long spreadTicks = bestBid > 0 && bestAsk > 0 ? Math.max(0, bestAsk - bestBid) : 0;
            long midTicks = MakerSimulatedTradeSupport.midPriceTicks(orderBook);
            long spreadPpm = midTicks <= 0 || spreadTicks <= 0 ? 0
                    : Math.round(spreadTicks * 1_000_000.0d / midTicks);
            long quoteCoveragePpm = desiredQuotes <= 0 ? 0
                    : Math.round(matchedDesired * 1_000_000.0d / desiredQuotes);
            long markTicks = MakerSimulatedTradeSupport.markPriceTicks(instrument, markPrice);

            if (properties.getReferenceMarket().isEnabled() && referenceOrderBook == null) {
                rowAnomalies.add(MakerMetricsSupport.anomaly("CRITICAL", "REFERENCE_BOOK_UNAVAILABLE", strategyId,
                        productLine, instrumentId, accountId, 0, 1,
                        "fresh external reference order book is required for quoting"));
            }
            if (inventoryUsagePpm >= 1_000_000L) {
                rowAnomalies.add(MakerMetricsSupport.anomaly("CRITICAL", "INVENTORY_LIMIT_REACHED", strategyId,
                        productLine, instrumentId, accountId, absInventory, maxInventory,
                        "signed inventory reached configured limit"));
            } else if (inventoryUsagePpm >= Math.max(0L, effectiveInventorySkewPpm(strategy))) {
                rowAnomalies.add(MakerMetricsSupport.anomaly("WARN", "INVENTORY_SKEW_HIGH", strategyId,
                        productLine, instrumentId, accountId, inventoryUsagePpm, effectiveInventorySkewPpm(strategy),
                        "inventory usage exceeds skew threshold"));
            }
            if (desiredQuotes > 0 && missingDesired > 0) {
                rowAnomalies.add(MakerMetricsSupport.anomaly("WARN", "MISSING_DESIRED_QUOTES", strategyId,
                        productLine, instrumentId, accountId, missingDesired, desiredQuotes,
                        "some desired quote levels are not live"));
            }
            if (plan.suppressedDuplicateQuotes() > 0) {
                rowAnomalies.add(MakerMetricsSupport.anomaly("WARN", "REDUCED_DISTINCT_DEPTH", strategyId,
                        productLine, instrumentId, accountId, desiredQuotes,
                        desiredQuotes + plan.suppressedDuplicateQuotes(),
                        "price bounds cannot represent every configured level as a distinct executable price"));
            }
            if (ownedLive.isEmpty() && strategy.isEnabled() && !state.paused()) {
                rowAnomalies.add(MakerMetricsSupport.anomaly("CRITICAL", "NO_LIVE_QUOTES", strategyId,
                        productLine, instrumentId, accountId, 0, desiredQuotes, "no owned live quotes are present"));
            }
            if (staleOwned > 0) {
                rowAnomalies.add(MakerMetricsSupport.anomaly("WARN", "STALE_QUOTES", strategyId,
                        productLine, instrumentId, accountId, staleOwned, 0,
                        "owned live quotes have no fresh reference book"));
            }
            if (offTargetOwned > 0) {
                rowAnomalies.add(MakerMetricsSupport.anomaly("WARN", "OFF_TARGET_QUOTES", strategyId,
                        productLine, instrumentId, accountId, offTargetOwned, 0,
                        "owned live quotes do not match target levels"));
            }
            if (!service.isTradableForProduct(instrument, productLine)) {
                rowAnomalies.add(MakerMetricsSupport.anomaly("CRITICAL", "INSTRUMENT_NOT_TRADING", strategyId,
                        productLine, instrumentId, accountId, 1, 0, "instrument is unavailable or not TRADING"));
            }
            MarketMakerLiquidityMetric liquidity = MakerMetricsSupport.linearLiquidity(properties, instrument, orderBook, Instant.now());
            if (liquidity != null && liquidity.targetNotionalUnits() > 0) {
                if (!liquidity.fresh()) {
                    rowAnomalies.add(MakerMetricsSupport.anomaly("CRITICAL", "LIQUIDITY_BOOK_STALE", strategyId,
                            productLine, instrumentId, accountId, 0, 1,
                            "fresh two-sided depth is required for liquidity verification"));
                }
                if (liquidity.bidNotionalWithinBandUnits() < liquidity.targetNotionalUnits()
                        || liquidity.askNotionalWithinBandUnits() < liquidity.targetNotionalUnits()) {
                    rowAnomalies.add(MakerMetricsSupport.anomaly("CRITICAL", "INSUFFICIENT_EXECUTABLE_DEPTH", strategyId,
                            productLine, instrumentId, accountId,
                            Math.min(liquidity.bidNotionalWithinBandUnits(), liquidity.askNotionalWithinBandUnits()),
                            liquidity.targetNotionalUnits(),
                            "displayed depth inside the slippage band is below the configured target"));
                }
            }
            anomalies.addAll(rowAnomalies);
            return new MarketMakerStrategyMetric(
                    strategyId, productLine, instrumentId, accountId, strategyStatus, MakerMetricsSupport.qualityStatus(rowAnomalies),
                    strategy.isEnabled(), state.paused(), state.cycleSequence(), state.submittedOrders(),
                    state.canceledOrders(), state.rejectedOrders(), state.skippedCycles(),
                    position.signedQuantitySteps(), absInventory, maxInventory, inventoryUsagePpm,
                    position.realizedPnlUnits(), position.updatedAt(), ownedLive.size(),
                    ownedLive.stream().filter(order -> order.side() == OrderSide.BUY).count(),
                    ownedLive.stream().filter(order -> order.side() == OrderSide.SELL).count(),
                    desiredQuotes,
                    plan.quotes().stream().filter(quote -> quote.side() == OrderSide.BUY).count(),
                    plan.quotes().stream().filter(quote -> quote.side() == OrderSide.SELL).count(),
                    matchedDesired, missingDesired, staleOwned, offTargetOwned, bestBid, bestAsk,
                    spreadTicks, spreadPpm, markTicks, quoteCoveragePpm, state.lastError(), state.lastCycleTime(), null, liquidity);
        } catch (RuntimeException ex) {
            String message = ex.getMessage();
            anomalies.add(MakerMetricsSupport.anomaly("CRITICAL", "METRIC_COLLECTION_FAILED", strategyId,
                    productLine, instrumentId, accountId, 1, 0, message));
            warnings.add(new MarketMakerMetricWarning(strategyId, productLine, instrumentId, accountId, message));
            return new MarketMakerStrategyMetric(
                    strategyId, productLine, instrumentId, accountId, strategyStatus, "CRITICAL", strategy.isEnabled(), state.paused(),
                    state.cycleSequence(), state.submittedOrders(), state.canceledOrders(), state.rejectedOrders(),
                    state.skippedCycles(), 0, 0, effectiveMaxInventorySteps(strategy), 0, 0, null,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    state.lastError(), state.lastCycleTime(), message, null);
        }
    }

    private long effectiveMaxInventorySteps(MarketMakerProperties.Strategy strategy) {
        return strategy.getMaxInventorySteps() == null || strategy.getMaxInventorySteps() <= 0
                ? properties.getRisk().getMaxInventorySteps()
                : strategy.getMaxInventorySteps();
    }

    private long effectiveInventorySkewPpm(MarketMakerProperties.Strategy strategy) {
        return strategy.getMaxInventorySkewPpm() == null
                ? properties.getRisk().getMaxInventorySkewPpm()
                : strategy.getMaxInventorySkewPpm();
    }
}
