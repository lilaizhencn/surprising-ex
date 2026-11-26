package com.surprising.marketmaker.provider.service;

import com.surprising.account.api.model.PositionResponse;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.marketmaker.provider.client.MakerAeronTradingGateway;
import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.marketmaker.provider.model.ReferenceOrderBookSnapshot;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.api.model.MarkPriceResponse;
import com.surprising.price.api.model.PriceStatus;
import com.surprising.product.api.ProductLine;
import com.surprising.product.api.utils.InstrumentIds;
import com.surprising.trading.api.model.MakerQuoteInputs;
import com.surprising.trading.api.model.OrderBookSnapshotResponse;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.PositionSide;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 做市行情、合约快照、持仓与活动挂单本地缓存协作器。 */
final class MarketMakerMarketFeed {
    private final MarketMakerProperties properties;
    private final InstrumentSnapshotCache instrumentSnapshotCache;
    private final MarketMakerMarkPriceCache markPriceCache;
    private final MakerAeronTradingGateway aeronGateway;
    private final ReferenceMarketProvider referenceMarketProvider;
    private final Map<String, CachedOpenOrders> openOrderSnapshots = new ConcurrentHashMap<>();

    MarketMakerMarketFeed(MarketMakerProperties properties,
                          InstrumentSnapshotCache instrumentSnapshotCache,
                          MarketMakerMarkPriceCache markPriceCache,
                          MakerAeronTradingGateway aeronGateway,
                          ReferenceMarketProvider referenceMarketProvider) {
        this.properties = properties;
        this.instrumentSnapshotCache = instrumentSnapshotCache;
        this.markPriceCache = markPriceCache;
        this.aeronGateway = aeronGateway;
        this.referenceMarketProvider = referenceMarketProvider;
    }

    InstrumentResponse currentInstrument(ProductLine productLine, String instrumentId) {
        if (productLine == null || instrumentSnapshotCache == null
                || !instrumentSnapshotCache.initialized(productLine)) {
            throw new IllegalStateException("做市合约 JVM 快照尚未就绪: " + productLine);
        }
        return instrumentSnapshotCache.current(productLine, InstrumentIds.parse(instrumentId))
                .orElseThrow(() -> new IllegalStateException("合约快照中不存在品种: " + productLine + "/" + instrumentId));
    }

    List<OrderResponse> openOrders(ProductLine productLine, long accountId, String instrumentId, Instant now) {
        String key = orderSnapshotKey(productLine, accountId, instrumentId);
        CachedOpenOrders cached = openOrderSnapshots.get(key);
        Duration interval = properties.getQuoting().getOrderReconciliationInterval();
        if (cached != null && interval != null && cached.refreshedAt().plus(interval).isAfter(now)) {
            return cached.orders();
        }
        List<OrderResponse> orders = aeronGateway.openOrders(productLine, accountId, instrumentId,
                properties.getQuoting().getMaxOpenOrdersPerAccountSymbol());
        openOrderSnapshots.put(key, new CachedOpenOrders(orders, now));
        return orders;
    }

    void updateCachedOrders(ProductLine productLine, long accountId, String instrumentId,
                            List<Long> removedIds, List<OrderResponse> replacements) {
        if (removedIds.isEmpty() && replacements.isEmpty()) return;
        String key = orderSnapshotKey(productLine, accountId, instrumentId);
        openOrderSnapshots.computeIfPresent(key, (ignored, cached) -> {
            var replacedIds = new HashSet<>(removedIds);
            for (var order : replacements) replacedIds.add(order.orderId());
            List<OrderResponse> orders = new ArrayList<>(cached.orders().size() + replacements.size());
            for (var order : cached.orders()) {
                if (!replacedIds.contains(order.orderId())) orders.add(order);
            }
            orders.addAll(replacements);
            return new CachedOpenOrders(List.copyOf(orders), cached.refreshedAt());
        });
    }

    void invalidateOrderSnapshot(ProductLine productLine, long accountId, String instrumentId) {
        openOrderSnapshots.remove(orderSnapshotKey(productLine, accountId, instrumentId));
    }

    private String orderSnapshotKey(ProductLine productLine, long accountId, String instrumentId) {
        return productLine.name() + ":" + accountId + ":" + instrumentId;
    }

    MarkPriceResponse latestMarkPrice(ProductLine productLine, String instrumentId, InstrumentResponse instrument) {
        long instrumentChangeId = instrument.changeId();
        var cached = markPriceCache.fresh(instrumentId);
        if (cached.isPresent()) {
            return toMarkPriceResponse(cached.get(), instrumentId, instrumentChangeId);
        }
        if (properties.getReferenceMarket().isEnabled()) {
            ReferenceOrderBookSnapshot ref = referenceMarketProvider.snapshot(instrumentId, productLine, instrument);
            if (ref != null && ref.midPriceTicks() > 0 && instrument.priceTickUnits() > 0) {
                long midUnits = Math.multiplyExact(ref.midPriceTicks(), instrument.priceTickUnits());
                return new MarkPriceResponse(instrumentId, null, midUnits, null,
                        null, null, null, null, null,
                        BigDecimal.ZERO, null, 0L, BigDecimal.ZERO,
                        0L, null, null, 0L, PriceStatus.HEALTHY,
                        ref.receivedAt() != null ? ref.receivedAt() : Instant.now());
            }
        }
        return toMarkPriceResponse(markPriceCache.requireFresh(instrumentId), instrumentId, instrumentChangeId);
    }

    private static MarkPriceResponse toMarkPriceResponse(MarkPriceEvent event, String instrumentId,
                                                         long instrumentChangeId) {
        if (event.instrumentChangeId() != instrumentChangeId) {
            throw new IllegalStateException("mark price instrument version mismatch for " + instrumentId
                    + ": expected=" + instrumentChangeId + ", actual=" + event.instrumentChangeId());
        }
        return new MarkPriceResponse(event.instrumentId(), event.markPrice(), event.markPriceUnits(), event.indexPrice(),
                event.price1(), event.price2(), event.lastTradePrice(), event.bestBidPrice(), event.bestAskPrice(),
                event.fundingRate(), event.nextFundingTime(), event.timeUntilFundingSeconds(), event.basisAverage(),
                event.basisWindowSeconds(), event.clampLow(), event.clampHigh(), event.sequence(), event.status(),
                event.eventTime());
    }

    PositionResponse currentPosition(MarketMakerProperties.Strategy strategy, long accountId,
                                     String instrumentId, InstrumentResponse instrument) {
        var state = aeronGateway.userState(strategy.getProductLine(), accountId);
        if (strategy.getProductLine() == ProductLine.SPOT) {
            if (instrument == null || instrument.baseAsset() == null || instrument.baseAsset().isBlank()) {
                throw new IllegalStateException("现货做市缺少基础资产快照");
            }
            if (instrument.quantityStepUnits() <= 0) {
                throw new IllegalStateException("现货做市数量步长必须为正数");
            }
            long equity = state == null ? 0L : state.balances().stream()
                    .filter(b -> instrument.baseAsset().equals(b.asset()))
                    .mapToLong(b -> Math.addExact(b.availableUnits(), b.lockedUnits()))
                    .findFirst().orElse(0L);
            long inventory = Math.max(0L, equity) / instrument.quantityStepUnits();
            return new PositionResponse(accountId, instrumentId,
                    strategy.getMarginMode(), PositionSide.NET, inventory, 0L, 0L, Instant.now());
        }
        long signed = state == null ? 0L : state.positions().stream()
                .filter(p -> instrumentId.equals(p.instrumentId())
                        && p.marginMode().name().equals(strategy.getMarginMode().name())
                        && p.positionSide().name().equals(PositionSide.NET.name()))
                .mapToLong(com.surprising.aeron.protocol.CorePositionView::signedQuantitySteps)
                .findFirst().orElse(0L);
        return new PositionResponse(accountId, instrumentId,
                strategy.getMarginMode(), PositionSide.NET, signed, 0L, 0L, Instant.now());
    }

    OrderBookSnapshotResponse fetchOrderBook(ProductLine productLine, String instrumentId, int depth) {
        return aeronGateway.orderBook(productLine, instrumentId, depth);
    }

    MakerQuoteInputs.Response fetchQuoteInputs(MarketMakerProperties.Strategy strategy,
                                               String instrumentId, InstrumentResponse instrument) {
        ProductLine productLine = strategy.getProductLine();
        List<MakerQuoteInputs.Account> accountInputs = new ArrayList<>(strategy.getAccountIds().size());
        for (long accountId : strategy.getAccountIds()) {
            var userState = aeronGateway.userState(productLine, accountId);
            long signedInventorySteps;
            if (productLine == ProductLine.SPOT) {
                long equity = userState == null ? 0L : userState.balances().stream()
                        .filter(b -> instrument.baseAsset().equals(b.asset()))
                        .mapToLong(b -> Math.addExact(b.availableUnits(), b.lockedUnits()))
                        .findFirst().orElse(0L);
                signedInventorySteps = Math.max(0L, equity) / Math.max(1L, instrument.quantityStepUnits());
            } else {
                signedInventorySteps = userState == null ? 0L : userState.positions().stream()
                        .filter(p -> instrumentId.equals(p.instrumentId())
                                && p.marginMode().name().equals(strategy.getMarginMode().name())
                                && p.positionSide().name().equals(PositionSide.NET.name()))
                        .mapToLong(com.surprising.aeron.protocol.CorePositionView::signedQuantitySteps)
                        .findFirst().orElse(0L);
            }
            accountInputs.add(new MakerQuoteInputs.Account(accountId, signedInventorySteps, 0L));
        }
        return new MakerQuoteInputs.Response(productLine, instrumentId, instrument.changeId(), accountInputs);
    }

    private record CachedOpenOrders(List<OrderResponse> orders, Instant refreshedAt) {
    }
}
