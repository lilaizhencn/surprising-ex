package com.surprising.candlestick.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PublicTradeEvent;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PublicTradeEventMapperTest {

    @Test
    void mapsTradeFromTheInstrumentSnapshotAndCachesVersion() {
        InstrumentSnapshotCache snapshotCache = new InstrumentSnapshotCache();
        snapshotCache.replace(com.surprising.product.api.ProductLine.LINEAR_PERPETUAL,
                List.of(instrument()), java.util.Map.of("BTC", 100_000_000L, "USDT", 1_000_000L));
        PublicTradeEventMapper mapper = new PublicTradeEventMapper(snapshotCache);
        PublicTradeEvent trade = new PublicTradeEvent(
                "trade:1",
                1L,
                "BTC-USDT",
                7L,
                OrderSide.BUY,
                6_000_000L,
                250_000L,
                Instant.parse("2026-07-31T00:00:00Z"),
                "trace:1");

        var first = mapper.toTradeEvent(trade);
        var second = mapper.toTradeEvent(trade);

        assertThat(first.price()).isEqualByComparingTo("60");
        assertThat(first.quantity()).isEqualByComparingTo("2.5");
        assertThat(second).isEqualTo(first);
    }

    private InstrumentResponse instrument() {
        Instant now = Instant.parse("2026-07-31T00:00:00Z");
        return new InstrumentResponse("BTC-USDT", 7L, InstrumentType.PERPETUAL, ContractType.LINEAR_PERPETUAL,
                "BTC", "USDT", "USDT", 1_000_000L, "BTC", 10L, 1_000L, 1L, 1_000_000L,
                1L, 1_000_000_000L, 1L, 2, 0, List.of("LIMIT"), List.of("GTC"), true,
                true, true, 100_000_000L, 10_000L, 5_000L, 100L, 500L,
                1_000_000_000L, 300_000L, 250_000_000L, 8, 100L, 3_000L, -3_000L,
                10_000_000L, 3, null, null, null, null, null, null, null,
                InstrumentStatus.TRADING, now, now, now, List.of(), List.of());
    }
}
