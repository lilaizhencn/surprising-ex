package com.surprising.candlestick.provider.service;

import com.surprising.candlestick.api.model.TradeEvent;
import com.surprising.candlestick.api.model.TradeSide;
import com.surprising.candlestick.provider.aggregation.CandleKey;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.candlestick.provider.config.CandlestickProperties;
import com.surprising.trading.api.model.PublicTradeEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 将撮合公开成交事件转换为 K 线聚合事件，并在服务层组合品种与资产精度。
 */
@Service
public class PublicTradeEventMapper {

    private static final int DISPLAY_SCALE = 18;

    private final InstrumentSnapshotCache snapshotCache;
    private com.surprising.instrument.api.client.InstrumentRpcApi instrumentRpc;
    private final com.surprising.product.api.ProductLine productLine;
    private final Map<InstrumentKey, InstrumentScale> scales = new ConcurrentHashMap<>();

    public PublicTradeEventMapper(InstrumentSnapshotCache snapshotCache) {
        this(snapshotCache, com.surprising.product.api.ProductLine.LINEAR_PERPETUAL);
    }

    public PublicTradeEventMapper(InstrumentSnapshotCache snapshotCache,
                                  CandlestickProperties properties) {
        this(snapshotCache,
                properties == null || properties.getKafka() == null
                        ? com.surprising.product.api.ProductLine.LINEAR_PERPETUAL
                        : properties.getKafka().getProductLine());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PublicTradeEventMapper(InstrumentSnapshotCache cache, CandlestickProperties properties,
            com.surprising.instrument.api.client.InstrumentRpcApi rpc) {
        this(cache,properties); this.instrumentRpc=rpc;
    }

    private PublicTradeEventMapper(InstrumentSnapshotCache snapshotCache,
                                   com.surprising.product.api.ProductLine productLine) {
        this.snapshotCache = snapshotCache;
        this.productLine = productLine == null
                ? com.surprising.product.api.ProductLine.LINEAR_PERPETUAL : productLine;
    }

    public TradeEvent toTradeEvent(PublicTradeEvent publicTrade) {
        if (publicTrade == null) {
            throw new IllegalArgumentException("public trade is required");
        }
        String symbol = CandleKey.normalizeSymbol(publicTrade.symbol());
        if (publicTrade.tradeId() == null || publicTrade.tradeId().isBlank()) {
            throw new IllegalArgumentException("public trade id is required");
        }
        if (publicTrade.sequence() < 0) {
            throw new IllegalArgumentException("public trade sequence must be non-negative");
        }
        if (publicTrade.priceTicks() <= 0 || publicTrade.quantitySteps() <= 0) {
            throw new IllegalArgumentException("public trade price and quantity must be positive");
        }
        if (publicTrade.eventTime() == null) {
            throw new IllegalArgumentException("public trade eventTime is required");
        }

        InstrumentScale scale = scale(symbol, publicTrade.instrumentChangeId());
        BigDecimal price = toDecimal(publicTrade.priceTicks(), scale.priceTickUnits(), scale.quoteScaleUnits());
        BigDecimal quantity = toDecimal(publicTrade.quantitySteps(), scale.quantityStepUnits(), scale.baseScaleUnits());
        return new TradeEvent(
                symbol,
                publicTrade.tradeId(),
                publicTrade.sequence(),
                publicTrade.eventTime(),
                price,
                quantity,
                side(publicTrade.takerSide() == null ? null : publicTrade.takerSide().name()),
                null,
                null);
    }

    private InstrumentScale scale(String symbol, long instrumentChangeId) {
        if (instrumentChangeId <= 0) {
            throw new IllegalArgumentException("public trade instrument version must be positive");
        }
        return scales.computeIfAbsent(new InstrumentKey(symbol, instrumentChangeId), this::loadScale);
    }

    private InstrumentScale loadScale(InstrumentKey key) {
        if (snapshotCache == null || !snapshotCache.initialized(productLine)) {
            throw new IllegalStateException("K 线合约 JVM 快照尚未就绪");
        }
        var instrument = snapshotCache.current(productLine, key.symbol(), key.instrumentChangeId()).orElse(null);
        if (instrument==null) {
            if (instrumentRpc==null) throw new IllegalArgumentException("committed trade audit encoding unavailable");
            var units=instrumentRpc.tradeEncoding(productLine,key.symbol(),key.instrumentChangeId());
            return new InstrumentScale(units.priceTickUnits(),units.quantityStepUnits(),units.baseScaleUnits(),units.quoteScaleUnits());
        }
        long baseScaleUnits = snapshotCache.scale(productLine, instrument.baseAsset())
                .orElseThrow(() -> new IllegalArgumentException("asset scale not found for " + instrument.baseAsset()));
        long quoteScaleUnits = snapshotCache.scale(productLine, instrument.quoteAsset())
                .orElseThrow(() -> new IllegalArgumentException("asset scale not found for " + instrument.quoteAsset()));
        return new InstrumentScale(instrument.priceTickUnits(), instrument.quantityStepUnits(),
                baseScaleUnits, quoteScaleUnits);
    }

    private BigDecimal toDecimal(long steps, long unitSize, long scaleUnits) {
        if (unitSize <= 0 || scaleUnits <= 0) {
            throw new IllegalArgumentException("instrument scale values must be positive");
        }
        return BigDecimal.valueOf(steps)
                .multiply(BigDecimal.valueOf(unitSize))
                .divide(BigDecimal.valueOf(scaleUnits), DISPLAY_SCALE, RoundingMode.HALF_UP)
                .stripTrailingZeros();
    }

    private TradeSide side(String takerSide) {
        if (takerSide == null || takerSide.isBlank()) {
            return TradeSide.UNKNOWN;
        }
        try {
            return TradeSide.valueOf(takerSide.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return TradeSide.UNKNOWN;
        }
    }

    private record InstrumentKey(String symbol, long instrumentChangeId) {
    }

    private record InstrumentScale(long priceTickUnits,
                                   long quantityStepUnits,
                                   long baseScaleUnits,
                                   long quoteScaleUnits) {
    }
}
