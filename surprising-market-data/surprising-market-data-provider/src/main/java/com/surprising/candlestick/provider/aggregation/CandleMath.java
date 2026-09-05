package com.surprising.candlestick.provider.aggregation;

import com.surprising.candlestick.api.model.TradeEvent;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Pure OHLCV aggregation rules.
 *
 * <p>This class does not know Kafka, RocksDB, or PostgreSQL. Keeping the math isolated makes it
 * easy to test out-of-order trades, open/close selection, high/low, volume, and quote volume.</p>
 */
public final class CandleMath {

    private CandleMath() {
    }

    /**
     * Applies one accepted trade to a candle accumulator.
     *
     * <p>Open and close prices are chosen by trade time and sequence, not by arrival order. That
     * keeps the candle correct when Kafka replays or upstream sends a small amount of out-of-order
     * data.</p>
     */
    public static void apply(CandleAccumulator candle, TradeEvent trade, Instant updatedAt) {
        if (trade.price() == null || trade.price().signum() <= 0) {
            throw new IllegalArgumentException("trade price must be positive");
        }
        if (trade.quantity() == null || trade.quantity().signum() <= 0) {
            throw new IllegalArgumentException("trade quantity must be positive");
        }
        Instant tradeTime = trade.tradeTime();
        if (tradeTime == null) {
            throw new IllegalArgumentException("tradeTime is required");
        }

        BigDecimal price = trade.price();
        BigDecimal quantity = trade.quantity();

        if (candle.getTradeCount() == 0) {
            candle.setOpenPrice(price);
            candle.setHighPrice(price);
            candle.setLowPrice(price);
            candle.setClosePrice(price);
            candle.setFirstTradeId(trade.tradeId());
            candle.setLastTradeId(trade.tradeId());
            candle.setFirstSequence(trade.sequence());
            candle.setLastSequence(trade.sequence());
            candle.setFirstTradeTime(tradeTime);
            candle.setLastTradeTime(tradeTime);
        } else {
            if (price.compareTo(candle.getHighPrice()) > 0) {
                candle.setHighPrice(price);
            }
            if (price.compareTo(candle.getLowPrice()) < 0) {
                candle.setLowPrice(price);
            }
            if (isBeforeFirstTrade(trade, candle)) {
                candle.setOpenPrice(price);
                candle.setFirstTradeId(trade.tradeId());
                candle.setFirstSequence(trade.sequence());
                candle.setFirstTradeTime(tradeTime);
            }
            if (isAfterLastTrade(trade, candle)) {
                candle.setClosePrice(price);
                candle.setLastTradeId(trade.tradeId());
                candle.setLastSequence(trade.sequence());
                candle.setLastTradeTime(tradeTime);
            }
        }

        candle.setBaseVolume(candle.getBaseVolume().add(quantity));
        candle.setQuoteVolume(candle.getQuoteVolume().add(price.multiply(quantity)));
        candle.setTradeCount(candle.getTradeCount() + 1);
        candle.setUpdatedAt(updatedAt);
    }

    private static boolean isBeforeFirstTrade(TradeEvent trade, CandleAccumulator candle) {
        int timeCompare = trade.tradeTime().compareTo(candle.getFirstTradeTime());
        if (timeCompare < 0) {
            return true;
        }
        return timeCompare == 0 && trade.sequence() < safeSequence(candle.getFirstSequence());
    }

    private static boolean isAfterLastTrade(TradeEvent trade, CandleAccumulator candle) {
        int timeCompare = trade.tradeTime().compareTo(candle.getLastTradeTime());
        if (timeCompare > 0) {
            return true;
        }
        return timeCompare == 0 && trade.sequence() >= safeSequence(candle.getLastSequence());
    }

    private static long safeSequence(Long sequence) {
        return sequence == null ? Long.MIN_VALUE : sequence;
    }
}
