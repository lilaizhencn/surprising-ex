package com.surprising.candlestick.provider.service;

import com.surprising.candlestick.api.model.TradeEvent;
import com.surprising.candlestick.api.model.TradeSide;
import com.surprising.candlestick.provider.aggregation.CandleKey;
import com.surprising.trading.api.model.MatchTradeEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class MatchTradeEventMapper {

    private static final int DISPLAY_SCALE = 18;
    private static final long SEQUENCE_TIME_MULTIPLIER = 1_000_000L;

    private final JdbcTemplate jdbcTemplate;
    private final Map<InstrumentKey, InstrumentScale> scales = new ConcurrentHashMap<>();

    public MatchTradeEventMapper(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public TradeEvent toTradeEvent(MatchTradeEvent matchTrade) {
        if (matchTrade == null) {
            throw new IllegalArgumentException("match trade is required");
        }
        String symbol = CandleKey.normalizeSymbol(matchTrade.symbol());
        if (matchTrade.tradeId() < 0) {
            throw new IllegalArgumentException("match trade id must be non-negative");
        }
        if (matchTrade.priceTicks() <= 0 || matchTrade.quantitySteps() <= 0) {
            throw new IllegalArgumentException("match trade price and quantity must be positive");
        }
        if (matchTrade.eventTime() == null) {
            throw new IllegalArgumentException("match trade eventTime is required");
        }

        InstrumentScale scale = scale(symbol, matchTrade.takerInstrumentVersion());
        BigDecimal price = toDecimal(matchTrade.priceTicks(), scale.priceTickUnits(), scale.quoteScaleUnits());
        BigDecimal quantity = toDecimal(matchTrade.quantitySteps(), scale.quantityStepUnits(), scale.baseScaleUnits());
        return new TradeEvent(
                symbol,
                Long.toString(matchTrade.tradeId()),
                sequence(matchTrade),
                matchTrade.eventTime(),
                price,
                quantity,
                side(matchTrade.takerSide() == null ? null : matchTrade.takerSide().name()),
                Long.toString(matchTrade.makerOrderId()),
                Long.toString(matchTrade.takerOrderId()));
    }

    private InstrumentScale scale(String symbol, long instrumentVersion) {
        if (instrumentVersion <= 0) {
            throw new IllegalArgumentException("match trade instrument version must be positive");
        }
        return scales.computeIfAbsent(new InstrumentKey(symbol, instrumentVersion), this::loadScale);
    }

    private InstrumentScale loadScale(InstrumentKey key) {
        return jdbcTemplate.query("""
                SELECT i.price_tick_units,
                       i.quantity_step_units,
                       base_scale.scale_units AS base_scale_units,
                       quote_scale.scale_units AS quote_scale_units
                  FROM instruments i
                  JOIN account_asset_scales base_scale ON base_scale.asset = i.base_asset
                  JOIN account_asset_scales quote_scale ON quote_scale.asset = i.quote_asset
                 WHERE i.symbol = ?
                   AND i.version = ?
                """, (rs, rowNum) -> new InstrumentScale(
                rs.getLong("price_tick_units"),
                rs.getLong("quantity_step_units"),
                rs.getLong("base_scale_units"),
                rs.getLong("quote_scale_units")), key.symbol(), key.instrumentVersion()).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("instrument scale not found for "
                        + key.symbol() + " version " + key.instrumentVersion()));
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

    private long sequence(MatchTradeEvent matchTrade) {
        long timeSequence = Math.multiplyExact(matchTrade.eventTime().toEpochMilli(), SEQUENCE_TIME_MULTIPLIER);
        return Math.addExact(timeSequence, Math.floorMod(matchTrade.tradeId(), SEQUENCE_TIME_MULTIPLIER));
    }

    private record InstrumentKey(String symbol, long instrumentVersion) {
    }

    private record InstrumentScale(long priceTickUnits,
                                   long quantityStepUnits,
                                   long baseScaleUnits,
                                   long quoteScaleUnits) {
    }
}
