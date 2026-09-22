package com.surprising.candlestick.provider.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.candlestick.api.model.CandlePeriod;
import com.surprising.candlestick.api.model.CandleStatus;
import com.surprising.candlestick.api.model.CandleUpdatedEvent;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CandleRollupAccumulatorTest {
    @Test
    void aggregatesClosedMinutesDeterministicallyWhenTheyArriveOutOfOrder() {
        Instant bucket = Instant.parse("2026-08-25T10:00:00Z");
        CandleRollupAccumulator accumulator = CandleRollupAccumulator.create("BTC-USDT", CandlePeriod.M5, bucket);

        accumulator.add(minute("2026-08-25T10:01:00Z", "101", "104", "98", "103", "2", "206", 3, "b", "c", 2, 4));
        accumulator.add(minute("2026-08-25T10:00:00Z", "100", "102", "99", "101", "1", "101", 2, "a", "b", 1, 2));
        accumulator.add(minute("2026-08-25T10:04:00Z", "103", "106", "102", "105", "4", "420", 5, "d", "e", 5, 9));

        CandleUpdatedEvent event = accumulator.event(Instant.parse("2026-08-25T10:05:01Z"));
        assertThat(event.openPrice()).isEqualByComparingTo("100");
        assertThat(event.highPrice()).isEqualByComparingTo("106");
        assertThat(event.lowPrice()).isEqualByComparingTo("98");
        assertThat(event.closePrice()).isEqualByComparingTo("105");
        assertThat(event.baseVolume()).isEqualByComparingTo("7");
        assertThat(event.quoteVolume()).isEqualByComparingTo("727");
        assertThat(event.tradeCount()).isEqualTo(10);
        assertThat(event.firstTradeId()).isEqualTo("a");
        assertThat(event.lastTradeId()).isEqualTo("e");
        assertThat(event.firstSequence()).isEqualTo(1);
        assertThat(event.lastSequence()).isEqualTo(9);
        assertThat(event.status()).isEqualTo(CandleStatus.PARTIAL);

        accumulator.close();
        assertThat(accumulator.event(Instant.parse("2026-08-25T10:06:00Z")).status())
                .isEqualTo(CandleStatus.CLOSED);
        assertThatThrownBy(() -> accumulator.add(minute(
                "2026-08-25T10:03:00Z", "102", "107", "97", "106", "1", "106", 1,
                "late", "late", 10, 10)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("closed candle rollup is immutable");
    }

    private CandleUpdatedEvent minute(String time, String open, String high, String low, String close,
                                       String base, String quote, long count, String firstId, String lastId,
                                       long firstSequence, long lastSequence) {
        Instant openTime = Instant.parse(time);
        return new CandleUpdatedEvent("BTC-USDT", "1m", openTime, openTime.plusSeconds(60),
                new BigDecimal(open), new BigDecimal(high), new BigDecimal(low), new BigDecimal(close),
                new BigDecimal(base), new BigDecimal(quote), count, firstId, lastId, firstSequence, lastSequence,
                CandleStatus.CLOSED, openTime.plusSeconds(59), openTime.plusSeconds(60), 0, lastSequence);
    }
}
