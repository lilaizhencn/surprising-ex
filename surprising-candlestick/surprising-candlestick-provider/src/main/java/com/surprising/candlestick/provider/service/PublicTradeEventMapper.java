package com.surprising.candlestick.provider.service;

import com.surprising.candlestick.api.model.TradeEvent;
import com.surprising.candlestick.api.model.TradeSide;
import com.surprising.candlestick.provider.aggregation.CandleKey;
import com.surprising.candlestick.provider.repository.CandlestickAssetScaleRepository;
import com.surprising.candlestick.provider.repository.CandlestickInstrumentRepository;
import com.surprising.candlestick.provider.repository.CandlestickInstrumentRepository.InstrumentDefinition;
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

    private final CandlestickInstrumentRepository instrumentRepository;
    private final CandlestickAssetScaleRepository assetScaleRepository;
    private final Map<InstrumentKey, InstrumentScale> scales = new ConcurrentHashMap<>();

    public PublicTradeEventMapper(CandlestickInstrumentRepository instrumentRepository,
                                  CandlestickAssetScaleRepository assetScaleRepository) {
        this.instrumentRepository = instrumentRepository;
        this.assetScaleRepository = assetScaleRepository;
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

        InstrumentScale scale = scale(symbol, publicTrade.instrumentVersion());
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

    private InstrumentScale scale(String symbol, long instrumentVersion) {
        if (instrumentVersion <= 0) {
            throw new IllegalArgumentException("public trade instrument version must be positive");
        }
        return scales.computeIfAbsent(new InstrumentKey(symbol, instrumentVersion), this::loadScale);
    }

    private InstrumentScale loadScale(InstrumentKey key) {
        InstrumentDefinition instrument = instrumentRepository.find(key.symbol(), key.instrumentVersion())
                .orElseThrow(() -> new IllegalArgumentException("instrument scale not found for "
                        + key.symbol() + " version " + key.instrumentVersion()));
        long baseScaleUnits = requireScaleUnits(instrument.baseAsset());
        long quoteScaleUnits = requireScaleUnits(instrument.quoteAsset());
        return new InstrumentScale(
                instrument.priceTickUnits(),
                instrument.quantityStepUnits(),
                baseScaleUnits,
                quoteScaleUnits);
    }

    private long requireScaleUnits(String asset) {
        return assetScaleRepository.findScaleUnits(asset)
                .orElseThrow(() -> new IllegalArgumentException("asset scale not found for " + asset));
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

    private record InstrumentKey(String symbol, long instrumentVersion) {
    }

    private record InstrumentScale(long priceTickUnits,
                                   long quantityStepUnits,
                                   long baseScaleUnits,
                                   long quoteScaleUnits) {
    }
}
