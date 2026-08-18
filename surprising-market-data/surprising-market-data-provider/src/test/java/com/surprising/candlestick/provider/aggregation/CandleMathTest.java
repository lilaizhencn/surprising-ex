package com.surprising.candlestick.provider.aggregation;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.candlestick.api.model.CandlePeriod;
import com.surprising.candlestick.api.model.TradeEvent;
import com.surprising.candlestick.api.model.TradeSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CandleMathTest {

    @Test
    void aggregatesOutOfOrderTradesWithoutChangingWrongOpenOrClose() {
        Instant bucket = Instant.parse("2026-06-30T10:15:00Z");
        CandleAccumulator candle = CandleAccumulator.create("BTC-USDT", CandlePeriod.M1, bucket);

        applyOnce(candle, trade("t2", 2, "2026-06-30T10:15:20Z", "101.00", "0.30"), new HashSet<>());
        applyOnce(candle, trade("t1", 1, "2026-06-30T10:15:05Z", "99.00", "0.20"), new HashSet<>());
        applyOnce(candle, trade("t3", 3, "2026-06-30T10:15:50Z", "105.00", "0.10"), new HashSet<>());
        applyOnce(candle, trade("t4", 4, "2026-06-30T10:15:40Z", "98.00", "0.40"), new HashSet<>());

        assertThat(candle.getOpenPrice()).isEqualByComparingTo("99.00");
        assertThat(candle.getHighPrice()).isEqualByComparingTo("105.00");
        assertThat(candle.getLowPrice()).isEqualByComparingTo("98.00");
        assertThat(candle.getClosePrice()).isEqualByComparingTo("105.00");
        assertThat(candle.getBaseVolume()).isEqualByComparingTo("1.00");
        assertThat(candle.getQuoteVolume()).isEqualByComparingTo("99.8000");
        assertThat(candle.getTradeCount()).isEqualTo(4);
        assertThat(candle.getFirstTradeId()).isEqualTo("t1");
        assertThat(candle.getLastTradeId()).isEqualTo("t3");
    }

    @Test
    void simulationDropsDuplicateTradeId() {
        Instant bucket = Instant.parse("2026-06-30T10:15:00Z");
        CandleAccumulator candle = CandleAccumulator.create("ETH-USDT", CandlePeriod.M1, bucket);
        Set<String> seenTrades = new HashSet<>();

        applyOnce(candle, trade("t100", 100, "2026-06-30T10:15:01Z", "10.00", "2.00"), seenTrades);
        applyOnce(candle, trade("t100", 100, "2026-06-30T10:15:01Z", "10.00", "2.00"), seenTrades);

        assertThat(candle.getTradeCount()).isEqualTo(1);
        assertThat(candle.getBaseVolume()).isEqualByComparingTo("2.00");
        assertThat(candle.getQuoteVolume()).isEqualByComparingTo("20.0000");
    }

    @Test
    void floorsPeriodsAtUtcEpochBoundaries() {
        Instant tradeTime = Instant.parse("2026-06-30T10:17:35Z");

        assertThat(CandlePeriod.M1.floor(tradeTime)).isEqualTo(Instant.parse("2026-06-30T10:17:00Z"));
        assertThat(CandlePeriod.M5.floor(tradeTime)).isEqualTo(Instant.parse("2026-06-30T10:15:00Z"));
        assertThat(CandlePeriod.H1.floor(tradeTime)).isEqualTo(Instant.parse("2026-06-30T10:00:00Z"));
    }

    private void applyOnce(CandleAccumulator candle, TradeEvent trade, Set<String> seenTrades) {
        if (seenTrades.add(trade.idempotencyKey())) {
            CandleMath.apply(candle, trade, Instant.parse("2026-06-30T10:16:00Z"));
        }
    }

    private TradeEvent trade(String id, long sequence, String time, String price, String quantity) {
        return new TradeEvent(
                "BTC-USDT",
                id,
                sequence,
                Instant.parse(time),
                new BigDecimal(price),
                new BigDecimal(quantity),
                TradeSide.BUY,
                "maker-" + id,
                "taker-" + id);
    }
}
