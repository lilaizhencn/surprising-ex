package com.surprising.marketmaker.provider.service;

import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.marketmaker.api.model.MarketMakerStrategyResponse;
import com.surprising.marketmaker.api.model.MarketMakerStrategyStatus;
import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.marketmaker.provider.model.MarketMakerAdminModels.MarketMakerAnomaly;
import com.surprising.marketmaker.provider.model.MarketMakerAdminModels.MarketMakerLiquidityMetric;
import com.surprising.marketmaker.provider.model.MarketMakerAdminModels.MarketMakerMetricsTotals;
import com.surprising.marketmaker.provider.model.MarketMakerAdminModels.MarketMakerStrategyMetric;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.OrderBookLevel;
import com.surprising.trading.api.model.OrderBookSnapshotResponse;
import com.surprising.trading.api.model.OrderSide;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** Pure market-maker admin metric, liquidity band, and anomaly calculations. */
final class MakerMetricsSupport {

    private MakerMetricsSupport() {
    }

    static MarketMakerLiquidityMetric linearLiquidity(MarketMakerProperties properties,
                                                      InstrumentResponse instrument,
                                                      OrderBookSnapshotResponse book,
                                                      Instant now) {
        if (instrument.contractType() != ContractType.LINEAR_PERPETUAL
                && instrument.contractType() != ContractType.LINEAR_DELIVERY) {
            return null;
        }
        long ppm = properties.getQuoting().getLiquiditySlippagePpm();
        boolean fresh = book.eventTime() != null && !book.eventTime().isAfter(now)
                && !book.eventTime().plus(properties.getReferenceMarket().getMaxAge()).isBefore(now)
                && MakerSimulatedTradeSupport.bestBid(book) > 0
                && MakerSimulatedTradeSupport.bestAsk(book) > MakerSimulatedTradeSupport.bestBid(book);
        return new MarketMakerLiquidityMetric(ppm, properties.getQuoting().getLinearLiquidityTargetNotionalUnits(),
                bandNotional(book.bids(), OrderSide.SELL, ppm, instrument.notionalMultiplierUnits()),
                bandNotional(book.asks(), OrderSide.BUY, ppm, instrument.notionalMultiplierUnits()),
                book.sequence(), book.eventTime(), fresh);
    }

    static long bandNotional(List<OrderBookLevel> levels, OrderSide takingSide, long ppm, long multiplier) {
        if (levels == null || levels.isEmpty()) return 0;
        levels = levels.stream().sorted(takingSide == OrderSide.SELL
                ? Comparator.comparingLong(OrderBookLevel::priceTicks).reversed()
                : Comparator.comparingLong(OrderBookLevel::priceTicks)).toList();
        long best = levels.getFirst().priceTicks();
        if (best <= 0 || multiplier <= 0) throw new IllegalStateException("invalid linear liquidity units");
        var threshold = BigInteger.valueOf(best).multiply(BigInteger.valueOf(ppm));
        var notional = BigInteger.ZERO;
        long previous = best;
        for (OrderBookLevel level : levels) {
            long price = level.priceTicks();
            if (price <= 0 || level.quantitySteps() < 0
                    || takingSide == OrderSide.BUY && price < previous
                    || takingSide == OrderSide.SELL && price > previous) {
                throw new IllegalStateException("invalid liquidity book ordering or amount");
            }
            previous = price;
            var distance = BigInteger.valueOf(takingSide == OrderSide.BUY ? price - best : best - price)
                    .multiply(BigInteger.valueOf(1_000_000L));
            if (distance.compareTo(threshold) > 0) break;
            notional = notional.add(BigInteger.valueOf(price)
                    .multiply(BigInteger.valueOf(level.quantitySteps()))
                    .multiply(BigInteger.valueOf(multiplier)));
        }
        return notional.longValueExact();
    }

    static MarketMakerMetricsTotals totals(List<MarketMakerStrategyResponse> strategies,
                                           List<MarketMakerStrategyMetric> rows,
                                           List<MarketMakerAnomaly> anomalies) {
        return new MarketMakerMetricsTotals(
                strategies.size(),
                strategies.stream().filter(MarketMakerStrategyResponse::configuredEnabled).count(),
                strategies.stream().filter(item -> item.status() == MarketMakerStrategyStatus.RUNNING).count(),
                strategies.stream().filter(item -> item.status() == MarketMakerStrategyStatus.DEGRADED).count(),
                strategies.stream().filter(item -> item.status() == MarketMakerStrategyStatus.PAUSED).count(),
                strategies.stream().filter(item -> item.status() == MarketMakerStrategyStatus.DISABLED).count(),
                rows.size(),
                strategies.stream().mapToLong(MarketMakerStrategyResponse::submittedOrders).sum(),
                strategies.stream().mapToLong(MarketMakerStrategyResponse::canceledOrders).sum(),
                strategies.stream().mapToLong(MarketMakerStrategyResponse::rejectedOrders).sum(),
                strategies.stream().mapToLong(MarketMakerStrategyResponse::skippedCycles).sum(),
                anomalies.size(),
                anomalies.stream().filter(item -> "CRITICAL".equals(item.severity())).count(),
                anomalies.stream().filter(item -> "WARN".equals(item.severity())).count());
    }

    static String qualityStatus(List<MarketMakerAnomaly> anomalies) {
        if (anomalies.stream().anyMatch(item -> "CRITICAL".equals(item.severity()))) return "CRITICAL";
        if (anomalies.stream().anyMatch(item -> "WARN".equals(item.severity()))) return "WARN";
        if (anomalies.stream().anyMatch(item -> "INFO".equals(item.severity()))) return "INFO";
        return "OK";
    }

    static MarketMakerAnomaly anomaly(String severity,
                                      String type,
                                      String strategyId,
                                      ProductLine productLine,
                                      String instrumentId,
                                      long accountId,
                                      long metricValue,
                                      long threshold,
                                      String summary) {
        return new MarketMakerAnomaly(severity, type, strategyId, productLine, instrumentId,
                accountId, metricValue, threshold, summary);
    }
}
